package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiModelProvider;
import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.*;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/**
 * Early risk detection at TASK scope (single task) and aggregate STUDENT/TEAM scope (reusing the
 * same deterministic {@link AiProgressFactsBuilder} facts already built for Progress Narrative, so
 * risk aggregation does not duplicate its own counting logic). No live GitHub/Jira reads.
 */
@Service @Profile("!test")
public class AiRiskAnalysisSubmissionService {
	public static final String POLICY_VERSION = "risk-analysis-v1", PROMPT_VERSION = "risk-analysis-v1", SCHEMA_VERSION = "risk-analysis-schema-v1";
	private final ProjectDataAuthorization auth; private final TaskRepository tasks; private final ProjectRepository projects; private final AiTaskIntelligenceSnapshotBuilder taskSnapshots; private final AiTaskIntelligenceRepository priorTaskIntelligence; private final AiProgressFactsBuilder progressFacts; private final ObjectMapper mapper; private final AiAnalysisRunRepository runs; private final AiAnalysisEvidenceRepository evidence; private final AiAnalysisProviderDecisionRepository decisions; private final AiAnalysisExecutor executor; private final List<AiModelProvider> providers; private final TransactionTemplate tx; private final AiCredentialResolver credentialResolver; private final CourseAiSettingsService courseSettings;

	public AiRiskAnalysisSubmissionService(ProjectDataAuthorization auth, TaskRepository tasks, ProjectRepository projects, AiTaskIntelligenceSnapshotBuilder taskSnapshots, AiTaskIntelligenceRepository priorTaskIntelligence, AiProgressFactsBuilder progressFacts, ObjectMapper mapper, AiAnalysisRunRepository runs, AiAnalysisEvidenceRepository evidence, AiAnalysisProviderDecisionRepository decisions, AiAnalysisExecutor executor, List<AiModelProvider> providers, PlatformTransactionManager manager, AiCredentialResolver credentialResolver, CourseAiSettingsService courseSettings) {
		this.auth = auth; this.tasks = tasks; this.projects = projects; this.taskSnapshots = taskSnapshots; this.priorTaskIntelligence = priorTaskIntelligence; this.progressFacts = progressFacts; this.mapper = mapper; this.runs = runs; this.evidence = evidence; this.decisions = decisions; this.executor = executor; this.providers = providers; this.tx = new TransactionTemplate(manager); this.credentialResolver = credentialResolver; this.courseSettings = courseSettings;
	}

	public record Submission(AiAnalysisRun run, boolean created) {}

	public Submission submitTask(UUID userId, UUID projectId, UUID taskId) {
		auth.requireReader(userId, projectId);
		return submitTask(projectId, taskId, AiInvocationOrigin.USER_REQUEST);
	}

	/** Automation entry point, triggered after a Task Intelligence run completes (section XII.C).
	 * No interactive user; silently returns empty when automation is off or no course PRIMARY
	 * credential is configured. */
	public Optional<Submission> submitTaskAutomatic(UUID projectId, UUID taskId) {
		Task task = tasks.findActiveFetchedByIdAndProject_Id(taskId, projectId).orElse(null);
		if (task == null) return Optional.empty();
		var course = task.getProject().getCourse();
		if (course == null || !courseSettings.get(course.getId()).automationEnabled()) return Optional.empty();
		var resolution = credentialResolver.resolve(course.getId(), AiAnalysisType.RISK_ANALYSIS, AiProviderRole.PRIMARY, AiInvocationOrigin.AUTOMATION);
		if (resolution.outcome() != AiCredentialResolver.Outcome.COURSE) return Optional.empty();
		return Optional.of(submitTask(projectId, taskId, AiInvocationOrigin.AUTOMATION));
	}

	private Submission submitTask(UUID projectId, UUID taskId, AiInvocationOrigin origin) {
		Task task = tasks.findActiveFetchedByIdAndProject_Id(taskId, projectId).orElseThrow(() -> notFound("Task not found."));
		var resolution = requireCourseCredential(task.getProject(), origin);
		AiModelProvider provider = primaryProvider();
		List<AiEvidenceDraft> draft = new ArrayList<>(taskSnapshots.build(task));
		draft.addAll(priorSignals(taskId));
		return submitCommon(task.getProject(), AiArtifactType.TASK, taskId, draft, provider, resolution);
	}

	public Submission submitStudent(UUID userId, UUID projectId, UUID studentId) {
		auth.requireReader(userId, projectId);
		Project project = projects.findById(projectId).orElseThrow(() -> notFound("Project not found."));
		var resolution = requireCourseCredential(project, AiInvocationOrigin.USER_REQUEST);
		AiModelProvider provider = primaryProvider();
		AiProgressFactsBuilder.Facts facts = progressFacts.buildStudent(project, studentId);
		List<AiEvidenceDraft> draft = List.of(new AiEvidenceDraft(AiEvidenceType.METADATA, "progress-facts:student:" + studentId, facts.json(mapper), null));
		return submitCommon(project, AiArtifactType.STUDENT, studentId, draft, provider, resolution);
	}

	public Submission submitTeam(UUID userId, UUID projectId) {
		auth.requireReader(userId, projectId);
		Project project = projects.findById(projectId).orElseThrow(() -> notFound("Project not found."));
		var resolution = requireCourseCredential(project, AiInvocationOrigin.USER_REQUEST);
		AiModelProvider provider = primaryProvider();
		AiProgressFactsBuilder.Facts facts = progressFacts.buildTeam(project);
		List<AiEvidenceDraft> draft = List.of(new AiEvidenceDraft(AiEvidenceType.METADATA, "progress-facts:team:" + projectId, facts.json(mapper), null));
		return submitCommon(project, AiArtifactType.TEAM, projectId, draft, provider, resolution);
	}

	/** RISK_ANALYSIS always requires a COURSE PRIMARY credential -- no platform fallback for any
	 * scope (task/student/team), manual or automatic (section II). */
	private AiCredentialResolver.Resolution requireCourseCredential(Project project, AiInvocationOrigin origin) {
		UUID courseId = project.getCourse() == null ? null : project.getCourse().getId();
		var resolution = credentialResolver.resolve(courseId, AiAnalysisType.RISK_ANALYSIS, AiProviderRole.PRIMARY, origin);
		if (resolution.outcome() != AiCredentialResolver.Outcome.COURSE) throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_UNAVAILABLE, HttpStatus.CONFLICT, "This course has no PRIMARY AI credential configured; Risk Analysis never falls back to the platform key.");
		return resolution;
	}

	private List<AiEvidenceDraft> priorSignals(UUID taskId) {
		var prior = priorTaskIntelligence.findTopByTask_IdOrderByCreatedAtDesc(taskId).orElse(null);
		if (prior == null) return List.of();
		Map<String, Object> signal = new TreeMap<>();
		signal.put("evidenceStrength", prior.getEvidenceStrength() == null ? null : prior.getEvidenceStrength().name());
		signal.put("summary", prior.getSummary());
		signal.put("deviationDetected", prior.isDeviationDetected());
		signal.put("deviationSummary", prior.getDeviationSummary());
		signal.put("asOfRunId", prior.getAnalysisRun() == null ? null : prior.getAnalysisRun().getId());
		String json = json(signal);
		return List.of(new AiEvidenceDraft(AiEvidenceType.METADATA, "prior-task-intelligence:" + taskId, json, null));
	}
	private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }

	private AiModelProvider primaryProvider() { return providers.stream().filter(p -> p.role() == AiProviderRole.PRIMARY).findFirst().orElseThrow(() -> new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_PROVIDER_FAILED, HttpStatus.SERVICE_UNAVAILABLE, "AI provider is not configured.")); }
	private IntegrationException notFound(String message) { return new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_NOT_FOUND, HttpStatus.NOT_FOUND, message); }

	private Submission submitCommon(Project project, AiArtifactType artifactType, UUID artifactId, List<AiEvidenceDraft> draft, AiModelProvider provider, AiCredentialResolver.Resolution resolution) {
		String evidenceHash = hashEvidence(draft);
		String key = AiHashes.sha256(String.join("|", project.getId().toString(), artifactType.name(), artifactId.toString(), evidenceHash, AiAnalysisType.RISK_ANALYSIS.name(), evidenceHash, POLICY_VERSION, PROMPT_VERSION, SCHEMA_VERSION, provider.providerConfigHash(), resolution.outcome().name(), String.valueOf(resolution.credentialFingerprint())));
		return Objects.requireNonNull(tx.execute(status -> persist(project, artifactType, artifactId, evidenceHash, draft, evidenceHash, key, provider, resolution)));
	}

	private Submission persist(Project project, AiArtifactType artifactType, UUID artifactId, String revision, List<AiEvidenceDraft> draft, String evidenceHash, String key, AiModelProvider provider, AiCredentialResolver.Resolution resolution) {
		AiAnalysisRun existing = runs.findByIdempotencyKey(key).orElse(null);
		if (existing != null) { after(existing.getId()); return new Submission(existing, false); }
		AiAnalysisRun run = new AiAnalysisRun();
		run.setProject(project); run.setArtifactType(artifactType); run.setArtifactId(artifactId); run.setArtifactRevision(revision);
		run.setAnalysisType(AiAnalysisType.RISK_ANALYSIS); run.setStatus(AiAnalysisStatus.QUEUED); run.setEvidenceHash(evidenceHash);
		run.setPolicyVersion(POLICY_VERSION); run.setPromptVersion(PROMPT_VERSION); run.setSchemaVersion(SCHEMA_VERSION); run.setProviderConfigHash(provider.providerConfigHash()); run.setIdempotencyKey(key);
		try { run = runs.saveAndFlush(run); } catch (DataIntegrityViolationException ex) { AiAnalysisRun concurrent = runs.findByIdempotencyKey(key).orElseThrow(() -> ex); after(concurrent.getId()); return new Submission(concurrent, false); }
		int ordinal = 0; List<AiAnalysisEvidence> rows = new ArrayList<>();
		for (AiEvidenceDraft item : draft) { AiAnalysisEvidence row = new AiAnalysisEvidence(); row.setAnalysisRun(run); row.setEvidenceType(item.type()); row.setSourceRef(item.sourceRef()); row.setContentHash(item.contentHash()); row.setPayloadJson(item.payloadJson()); row.setMetadataJson(item.metadataJson()); row.setOrdinalIndex(ordinal++); rows.add(row); }
		evidence.saveAll(rows);
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision(); decision.setAnalysisRun(run); decision.setProviderRole(AiProviderRole.PRIMARY); decision.setProviderKey(provider.providerKey()); decision.setProviderConfigHash(provider.providerConfigHash()); decision.setCredentialSource(AiCredentialSource.COURSE); decision.setCourseCredentialId(resolution.courseCredentialId()); decision.setCredentialFingerprint(resolution.credentialFingerprint()); decision.setModelId(provider.modelId()); decision.setRoute(AiProviderRoute.NORMAL); decision.setStatus(AiProviderDecisionStatus.PENDING); decisions.save(decision);
		after(run.getId()); return new Submission(run, true);
	}
	private static String hashEvidence(List<AiEvidenceDraft> draft) { return AiHashes.sha256(draft.stream().map(row -> row.type() + "|" + row.sourceRef() + "|" + row.contentHash()).reduce("", (a, b) -> a + "\n" + b)); }
	private void after(UUID id) { if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { executor.enqueue(id); } }); else executor.enqueue(id); }
}
