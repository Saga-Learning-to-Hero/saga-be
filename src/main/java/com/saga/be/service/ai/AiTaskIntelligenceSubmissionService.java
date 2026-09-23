package com.saga.be.service.ai;

import com.saga.be.ai.AiModelProvider;
import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import com.saga.be.entity.jira.Task;
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

/** No live GitHub/Jira reads: every evidence row comes from already-synced local rows.
 * TASK_INTELLIGENCE requires a COURSE PRIMARY credential; there is no platform fallback for this
 * analysis type, whether the request is manual or automatic. */
@Service @Profile("!test")
public class AiTaskIntelligenceSubmissionService {
	public static final String POLICY_VERSION = "task-intelligence-v1", PROMPT_VERSION = "task-intelligence-v1", SCHEMA_VERSION = "task-intelligence-schema-v1";
	private final ProjectDataAuthorization auth; private final TaskRepository tasks; private final AiTaskIntelligenceSnapshotBuilder snapshots; private final AiAnalysisRunRepository runs; private final AiAnalysisEvidenceRepository evidence; private final AiAnalysisProviderDecisionRepository decisions; private final AiAnalysisExecutor executor; private final List<AiModelProvider> providers; private final TransactionTemplate tx; private final AiCredentialResolver credentialResolver; private final CourseAiSettingsService courseSettings;

	public AiTaskIntelligenceSubmissionService(ProjectDataAuthorization auth, TaskRepository tasks, AiTaskIntelligenceSnapshotBuilder snapshots, AiAnalysisRunRepository runs, AiAnalysisEvidenceRepository evidence, AiAnalysisProviderDecisionRepository decisions, AiAnalysisExecutor executor, List<AiModelProvider> providers, PlatformTransactionManager manager, AiCredentialResolver credentialResolver, CourseAiSettingsService courseSettings) {
		this.auth = auth; this.tasks = tasks; this.snapshots = snapshots; this.runs = runs; this.evidence = evidence; this.decisions = decisions; this.executor = executor; this.providers = providers; this.tx = new TransactionTemplate(manager); this.credentialResolver = credentialResolver; this.courseSettings = courseSettings;
	}

	public record Submission(AiAnalysisRun run, boolean created) {}

	public Submission submit(UUID userId, UUID projectId, UUID taskId) {
		auth.requireReader(userId, projectId);
		return submit(projectId, taskId, AiInvocationOrigin.USER_REQUEST);
	}

	/** Automation entry point: no interactive user. Silently returns empty when automation is off
	 * or no course PRIMARY credential is configured -- never creates a doomed run. */
	public Optional<Submission> submitAutomatic(UUID projectId, UUID taskId) {
		Task task = tasks.findActiveFetchedByIdAndProject_Id(taskId, projectId).orElse(null);
		if (task == null) return Optional.empty();
		var course = task.getProject().getCourse();
		if (course == null || !courseSettings.get(course.getId()).automationEnabled()) return Optional.empty();
		var resolution = credentialResolver.resolve(course.getId(), AiAnalysisType.TASK_INTELLIGENCE, AiProviderRole.PRIMARY, AiInvocationOrigin.AUTOMATION);
		if (resolution.outcome() != AiCredentialResolver.Outcome.COURSE) return Optional.empty();
		return Optional.of(submit(projectId, taskId, AiInvocationOrigin.AUTOMATION));
	}

	private Submission submit(UUID projectId, UUID taskId, AiInvocationOrigin origin) {
		Task task = tasks.findActiveFetchedByIdAndProject_Id(taskId, projectId).orElseThrow(() -> notFound("Task not found."));
		UUID courseId = task.getProject().getCourse() == null ? null : task.getProject().getCourse().getId();
		var resolution = credentialResolver.resolve(courseId, AiAnalysisType.TASK_INTELLIGENCE, AiProviderRole.PRIMARY, origin);
		if (resolution.outcome() != AiCredentialResolver.Outcome.COURSE) throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_UNAVAILABLE, HttpStatus.CONFLICT, "This course has no PRIMARY AI credential configured; Task Intelligence never falls back to the platform key.");
		AiModelProvider provider = primaryProvider();
		List<AiEvidenceDraft> draft = snapshots.build(task);
		String evidenceHash = hashEvidence(draft);
		String key = idempotency(projectId, taskId, evidenceHash, provider, resolution);
		return Objects.requireNonNull(tx.execute(status -> persist(task.getProject(), taskId, evidenceHash, draft, evidenceHash, key, provider, resolution)));
	}

	private AiModelProvider primaryProvider() { return providers.stream().filter(p -> p.role() == AiProviderRole.PRIMARY).findFirst().orElseThrow(() -> new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_PROVIDER_FAILED, HttpStatus.SERVICE_UNAVAILABLE, "AI provider is not configured.")); }
	private IntegrationException notFound(String message) { return new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_NOT_FOUND, HttpStatus.NOT_FOUND, message); }
	private String idempotency(UUID projectId, UUID taskId, String evidenceHash, AiModelProvider provider, AiCredentialResolver.Resolution resolution) { return AiHashes.sha256(String.join("|", projectId.toString(), AiArtifactType.TASK.name(), taskId.toString(), evidenceHash, AiAnalysisType.TASK_INTELLIGENCE.name(), evidenceHash, POLICY_VERSION, PROMPT_VERSION, SCHEMA_VERSION, provider.providerConfigHash(), resolution.outcome().name(), String.valueOf(resolution.credentialFingerprint()))); }

	private Submission persist(com.saga.be.entity.project.Project project, UUID taskId, String revision, List<AiEvidenceDraft> draft, String evidenceHash, String key, AiModelProvider provider, AiCredentialResolver.Resolution resolution) {
		AiAnalysisRun existing = runs.findByIdempotencyKey(key).orElse(null);
		if (existing != null) { after(existing.getId()); return new Submission(existing, false); }
		AiAnalysisRun run = new AiAnalysisRun();
		run.setProject(project); run.setArtifactType(AiArtifactType.TASK); run.setArtifactId(taskId); run.setArtifactRevision(revision);
		run.setAnalysisType(AiAnalysisType.TASK_INTELLIGENCE); run.setStatus(AiAnalysisStatus.QUEUED); run.setEvidenceHash(evidenceHash);
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
