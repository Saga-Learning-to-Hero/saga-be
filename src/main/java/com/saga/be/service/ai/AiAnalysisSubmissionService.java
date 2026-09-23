package com.saga.be.service.ai;

import com.saga.be.ai.AiModelProvider;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import com.saga.be.entity.traceability.TaskGitCommitLink;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** GitHub HTTP completes before the short canonical-run persistence transaction is opened.
 * COMMIT_INTELLIGENCE requires a COURSE PRIMARY credential; there is no platform fallback for
 * this analysis type, whether the request is a manual "Analyze" click or automatic. */
@Service @Profile("!test")
public class AiAnalysisSubmissionService {
	public static final String POLICY_VERSION = "commit-intelligence-v1";
	public static final String PROMPT_VERSION = "commit-intelligence-v1";
	public static final String SCHEMA_VERSION = "ai-2-schema-v1";
	private final ProjectDataAuthorization authorization; private final GitCommitRepository commits; private final TaskGitCommitLinkRepository links; private final JiraTaskFailoverItemRepository failover; private final AiAnalysisRunRepository runs; private final AiAnalysisEvidenceRepository evidence; private final AiAnalysisProviderDecisionRepository decisions; private final AiCommitEvidenceSnapshotBuilder snapshots; private final AiGitHubCommitEvidenceAcquirer githubEvidence; private final AiAnalysisExecutor executor; private final List<AiModelProvider> providers; private final TransactionTemplate transactions; private final AiCredentialResolver credentialResolver; private final CourseAiSettingsService courseSettings;
	public AiAnalysisSubmissionService(ProjectDataAuthorization authorization, GitCommitRepository commits, TaskGitCommitLinkRepository links, JiraTaskFailoverItemRepository failover, AiAnalysisRunRepository runs, AiAnalysisEvidenceRepository evidence, AiAnalysisProviderDecisionRepository decisions, AiCommitEvidenceSnapshotBuilder snapshots, AiGitHubCommitEvidenceAcquirer githubEvidence, AiAnalysisExecutor executor, List<AiModelProvider> providers, PlatformTransactionManager transactionManager, AiCredentialResolver credentialResolver, CourseAiSettingsService courseSettings) { this.authorization = authorization; this.commits = commits; this.links = links; this.failover = failover; this.runs = runs; this.evidence = evidence; this.decisions = decisions; this.snapshots = snapshots; this.githubEvidence = githubEvidence; this.executor = executor; this.providers = providers; this.transactions = new TransactionTemplate(transactionManager); this.credentialResolver = credentialResolver; this.courseSettings = courseSettings; }

	public Submission submit(UUID userId, UUID projectId, UUID gitCommitId) {
		authorization.requireReader(userId, projectId);
		return submit(projectId, gitCommitId, AiInvocationOrigin.USER_REQUEST);
	}

	/** Automation entry point: no interactive user, so no {@code ProjectDataAuthorization} check
	 * (the caller is the system itself reacting to a persisted commit). Silently returns empty
	 * when automation is off or no course credential is configured -- never creates a doomed run,
	 * never throws. */
	public Optional<Submission> submitAutomatic(UUID projectId, UUID gitCommitId) {
		GitCommit commit = commits.findAnalysisTargetById(gitCommitId).orElse(null);
		if (commit == null || !commit.getRepo().getProject().getId().equals(projectId)) return Optional.empty();
		Course course = commit.getRepo().getProject().getCourse();
		if (course == null || !courseSettings.get(course.getId()).automationEnabled()) return Optional.empty();
		var resolution = credentialResolver.resolve(course.getId(), AiAnalysisType.COMMIT_INTELLIGENCE, AiProviderRole.PRIMARY, AiInvocationOrigin.AUTOMATION);
		if (resolution.outcome() != AiCredentialResolver.Outcome.COURSE) return Optional.empty();
		return Optional.of(submit(projectId, gitCommitId, AiInvocationOrigin.AUTOMATION));
	}

	private Submission submit(UUID projectId, UUID gitCommitId, AiInvocationOrigin origin) {
		AiModelProvider provider = providers.stream().filter(p -> p.role() == AiProviderRole.PRIMARY).findFirst().orElseThrow(() -> new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_PROVIDER_FAILED, HttpStatus.SERVICE_UNAVAILABLE, "AI provider is not configured."));
		GitCommit commit = commits.findAnalysisTargetById(gitCommitId).orElseThrow(() -> new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_NOT_FOUND, HttpStatus.NOT_FOUND, "Commit was not found."));
		if (!commit.getRepo().getProject().getId().equals(projectId)) throw new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_PROJECT_MISMATCH, HttpStatus.NOT_FOUND, "Commit does not belong to this project.");
		UUID courseId = commit.getRepo().getProject().getCourse() == null ? null : commit.getRepo().getProject().getCourse().getId();
		var resolution = credentialResolver.resolve(courseId, AiAnalysisType.COMMIT_INTELLIGENCE, AiProviderRole.PRIMARY, origin);
		if (resolution.outcome() != AiCredentialResolver.Outcome.COURSE) throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_UNAVAILABLE, HttpStatus.CONFLICT, "This course has no PRIMARY AI credential configured; Commit Intelligence never falls back to the platform key.");
		List<TaskGitCommitLink> taskLinks = links.findAnalysisEvidenceByGitCommitId(gitCommitId, projectId); List<UUID> taskIds = taskLinks.stream().map(link -> link.getTask().getId()).toList(); List<JiraTaskFailoverItem> lineage = taskIds.isEmpty() ? List.of() : failover.findSuccessfulLineageByTaskIds(taskIds);
		List<AiEvidenceDraft> draft = new ArrayList<>(snapshots.build(commit, taskLinks, lineage));
		draft.addAll(githubEvidence.acquire(new AiGitHubCommitEvidenceAcquirer.Target(commit.getRepo().getId(), commit.getRepo().getInstallation() == null ? null : commit.getRepo().getInstallation().getInstallationId(), commit.getRepo().getOwnerLogin(), commit.getRepo().getName(), commit.getShaHash())));
		String revision = commit.getShaHash() == null || commit.getShaHash().isBlank() ? commit.getId().toString() : commit.getShaHash(); String evidenceHash = hashEvidence(draft); String idempotency = AiHashes.sha256(String.join("|", projectId.toString(), AiArtifactType.COMMIT.name(), gitCommitId.toString(), revision, AiAnalysisType.COMMIT_INTELLIGENCE.name(), evidenceHash, POLICY_VERSION, PROMPT_VERSION, SCHEMA_VERSION, provider.providerConfigHash(), credentialIdentity(resolution)));
		return Objects.requireNonNull(transactions.execute(status -> persist(commit, draft, evidenceHash, idempotency, revision, provider, resolution)));
	}
	private Submission persist(GitCommit commit, List<AiEvidenceDraft> draft, String evidenceHash, String idempotency, String revision, AiModelProvider provider, AiCredentialResolver.Resolution resolution) {
		AiAnalysisRun existing = runs.findByIdempotencyKey(idempotency).orElse(null); if (existing != null) { enqueueAfterCommit(existing.getId()); return new Submission(existing, false); }
		AiAnalysisRun run = new AiAnalysisRun(); run.setProject(commit.getRepo().getProject()); run.setArtifactType(AiArtifactType.COMMIT); run.setArtifactId(commit.getId()); run.setArtifactRevision(revision); run.setAnalysisType(AiAnalysisType.COMMIT_INTELLIGENCE); run.setStatus(AiAnalysisStatus.QUEUED); run.setEvidenceHash(evidenceHash); run.setPolicyVersion(POLICY_VERSION); run.setPromptVersion(PROMPT_VERSION); run.setSchemaVersion(SCHEMA_VERSION); run.setProviderConfigHash(provider.providerConfigHash()); run.setIdempotencyKey(idempotency);
		try { run = runs.saveAndFlush(run); } catch (DataIntegrityViolationException ex) { AiAnalysisRun concurrent = runs.findByIdempotencyKey(idempotency).orElseThrow(() -> ex); enqueueAfterCommit(concurrent.getId()); return new Submission(concurrent, false); }
		int ordinal = 0; List<AiAnalysisEvidence> rows = new ArrayList<>(); for (AiEvidenceDraft item : draft) { AiAnalysisEvidence row = new AiAnalysisEvidence(); row.setAnalysisRun(run); row.setEvidenceType(item.type()); row.setSourceRef(item.sourceRef()); row.setContentHash(item.contentHash()); row.setPayloadJson(item.payloadJson()); row.setMetadataJson(item.metadataJson()); row.setOrdinalIndex(ordinal++); rows.add(row); } evidence.saveAll(rows);
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision(); decision.setAnalysisRun(run); decision.setProviderRole(AiProviderRole.PRIMARY); decision.setProviderKey(provider.providerKey()); decision.setProviderConfigHash(provider.providerConfigHash()); decision.setCredentialSource(AiCredentialSource.COURSE); decision.setCourseCredentialId(resolution.courseCredentialId()); decision.setCredentialFingerprint(resolution.credentialFingerprint()); decision.setModelId(provider.modelId()); decision.setRoute(AiProviderRoute.NORMAL); decision.setStatus(AiProviderDecisionStatus.PENDING); decisions.save(decision); enqueueAfterCommit(run.getId()); return new Submission(run, true);
	}
	private static String credentialIdentity(AiCredentialResolver.Resolution resolution) { return resolution.outcome() + "|" + (resolution.credentialFingerprint() == null ? "" : resolution.credentialFingerprint()); }
	private static String hashEvidence(List<AiEvidenceDraft> draft) { return AiHashes.sha256(draft.stream().map(row -> row.type() + "|" + row.sourceRef() + "|" + row.contentHash()).reduce("", (a, b) -> a + "\n" + b)); }
	private void enqueueAfterCommit(UUID runId) { if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { executor.enqueue(runId); } }); else executor.enqueue(runId); }
	public record Submission(AiAnalysisRun run, boolean created) {}
}
