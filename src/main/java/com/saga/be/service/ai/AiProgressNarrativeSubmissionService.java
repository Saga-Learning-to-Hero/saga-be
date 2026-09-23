package com.saga.be.service.ai;

import com.saga.be.ai.AiModelProvider;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.*;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/**
 * STUDENT and TEAM scope stay project-owned (reuse {@link ProjectDataAuthorization}, project_id
 * always set). COURSE scope has no single owning project, so it is authorized via
 * {@link LecturerCourseAuthorization} instead and the run's project_id is left null with course_id
 * set (see V30). No live GitHub/Jira/OpenAI reads: facts come only from {@link AiProgressFactsBuilder}.
 */
@Service @Profile("!test")
public class AiProgressNarrativeSubmissionService {
	public static final String POLICY_VERSION = "progress-narrative-v1", PROMPT_VERSION = "progress-narrative-v1", SCHEMA_VERSION = "progress-narrative-schema-v1";
	private final ProjectDataAuthorization projectAuth; private final LecturerCourseAuthorization courseAuth; private final ProjectRepository projects; private final TeamRepository teams; private final AiProgressFactsBuilder facts; private final AiAnalysisRunRepository runs; private final AiAnalysisEvidenceRepository evidence; private final AiAnalysisProviderDecisionRepository decisions; private final AiAnalysisExecutor executor; private final List<AiModelProvider> providers; private final TransactionTemplate tx; private final com.fasterxml.jackson.databind.ObjectMapper mapper;

	public AiProgressNarrativeSubmissionService(ProjectDataAuthorization projectAuth, LecturerCourseAuthorization courseAuth, ProjectRepository projects, TeamRepository teams, AiProgressFactsBuilder facts, AiAnalysisRunRepository runs, AiAnalysisEvidenceRepository evidence, AiAnalysisProviderDecisionRepository decisions, AiAnalysisExecutor executor, List<AiModelProvider> providers, PlatformTransactionManager manager, com.fasterxml.jackson.databind.ObjectMapper mapper) {
		this.projectAuth = projectAuth; this.courseAuth = courseAuth; this.projects = projects; this.teams = teams; this.facts = facts; this.runs = runs; this.evidence = evidence; this.decisions = decisions; this.executor = executor; this.providers = providers; this.tx = new TransactionTemplate(manager); this.mapper = mapper;
	}

	public record Submission(AiAnalysisRun run, boolean created) {}

	public Submission submitStudent(UUID userId, UUID projectId, UUID studentId) {
		projectAuth.requireReader(userId, projectId);
		Project project = projects.findById(projectId).orElseThrow(() -> notFound("Project not found."));
		AiModelProvider provider = primaryProvider();
		AiProgressFactsBuilder.Facts data = facts.buildStudent(project, studentId);
		List<AiEvidenceDraft> draft = List.of(factsRow("student:" + studentId, data));
		return submitCommon(project, null, AiArtifactType.STUDENT, studentId, draft, provider);
	}

	public Submission submitTeam(UUID userId, UUID projectId) {
		projectAuth.requireReader(userId, projectId);
		Project project = projects.findById(projectId).orElseThrow(() -> notFound("Project not found."));
		AiModelProvider provider = primaryProvider();
		AiProgressFactsBuilder.Facts data = facts.buildTeam(project);
		List<AiEvidenceDraft> draft = List.of(factsRow("team:" + projectId, data));
		return submitCommon(project, null, AiArtifactType.TEAM, projectId, draft, provider);
	}

	public Submission submitCourse(UserAccount actor, UUID courseId) {
		Course course = courseAuth.requireCourse(actor, courseId);
		AiModelProvider provider = primaryProvider();
		List<Project> courseProjects = teams.findByCourse_IdOrderByTeamNoAsc(courseId).stream().map(com.saga.be.entity.project.Team::getProject).filter(Objects::nonNull).toList();
		AiProgressFactsBuilder.Facts data = facts.buildCourse(course, courseProjects);
		List<AiEvidenceDraft> draft = List.of(factsRow("course:" + courseId, data));
		return submitCommon(null, course, AiArtifactType.COURSE, courseId, draft, provider);
	}

	private AiEvidenceDraft factsRow(String subjectRef, AiProgressFactsBuilder.Facts data) {
		String json = data.json(mapper);
		return new AiEvidenceDraft(AiEvidenceType.METADATA, "progress-facts:" + subjectRef, json, null);
	}

	private Submission submitCommon(Project project, Course course, AiArtifactType artifactType, UUID artifactId, List<AiEvidenceDraft> draft, AiModelProvider provider) {
		String evidenceHash = hashEvidence(draft);
		String ownerKey = project != null ? project.getId().toString() : "course:" + course.getId();
		String key = AiHashes.sha256(String.join("|", ownerKey, artifactType.name(), artifactId.toString(), evidenceHash, AiAnalysisType.PROGRESS_NARRATIVE.name(), evidenceHash, POLICY_VERSION, PROMPT_VERSION, SCHEMA_VERSION, provider.providerConfigHash()));
		return Objects.requireNonNull(tx.execute(status -> persist(project, course, artifactType, artifactId, evidenceHash, draft, evidenceHash, key, provider)));
	}

	private AiModelProvider primaryProvider() { return providers.stream().filter(p -> p.role() == AiProviderRole.PRIMARY).findFirst().orElseThrow(() -> new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_PROVIDER_FAILED, HttpStatus.SERVICE_UNAVAILABLE, "AI provider is not configured.")); }
	private IntegrationException notFound(String message) { return new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_NOT_FOUND, HttpStatus.NOT_FOUND, message); }

	private Submission persist(Project project, Course course, AiArtifactType artifactType, UUID artifactId, String revision, List<AiEvidenceDraft> draft, String evidenceHash, String key, AiModelProvider provider) {
		AiAnalysisRun existing = runs.findByIdempotencyKey(key).orElse(null);
		if (existing != null) { after(existing.getId()); return new Submission(existing, false); }
		AiAnalysisRun run = new AiAnalysisRun();
		run.setProject(project); run.setCourse(course); run.setArtifactType(artifactType); run.setArtifactId(artifactId); run.setArtifactRevision(revision);
		run.setAnalysisType(AiAnalysisType.PROGRESS_NARRATIVE); run.setStatus(AiAnalysisStatus.QUEUED); run.setEvidenceHash(evidenceHash);
		run.setPolicyVersion(POLICY_VERSION); run.setPromptVersion(PROMPT_VERSION); run.setSchemaVersion(SCHEMA_VERSION); run.setProviderConfigHash(provider.providerConfigHash()); run.setIdempotencyKey(key);
		try { run = runs.saveAndFlush(run); } catch (DataIntegrityViolationException ex) { AiAnalysisRun concurrent = runs.findByIdempotencyKey(key).orElseThrow(() -> ex); after(concurrent.getId()); return new Submission(concurrent, false); }
		int ordinal = 0; List<AiAnalysisEvidence> rows = new ArrayList<>();
		for (AiEvidenceDraft item : draft) { AiAnalysisEvidence row = new AiAnalysisEvidence(); row.setAnalysisRun(run); row.setEvidenceType(item.type()); row.setSourceRef(item.sourceRef()); row.setContentHash(item.contentHash()); row.setPayloadJson(item.payloadJson()); row.setMetadataJson(item.metadataJson()); row.setOrdinalIndex(ordinal++); rows.add(row); }
		evidence.saveAll(rows);
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision(); decision.setAnalysisRun(run); decision.setProviderRole(AiProviderRole.PRIMARY); decision.setProviderKey(provider.providerKey()); decision.setProviderConfigHash(provider.providerConfigHash()); decision.setModelId(provider.modelId()); decision.setRoute(AiProviderRoute.NORMAL); decision.setStatus(AiProviderDecisionStatus.PENDING); decisions.save(decision);
		after(run.getId()); return new Submission(run, true);
	}
	private static String hashEvidence(List<AiEvidenceDraft> draft) { return AiHashes.sha256(draft.stream().map(row -> row.type() + "|" + row.sourceRef() + "|" + row.contentHash()).reduce("", (a, b) -> a + "\n" + b)); }
	private void after(UUID id) { if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { executor.enqueue(id); } }); else executor.enqueue(id); }
}
