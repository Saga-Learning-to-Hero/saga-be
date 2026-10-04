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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** GitHub HTTP completes before the short canonical-run persistence transaction is opened.
 * COMMIT_INTELLIGENCE is paid by the team's own key when its leader set one, otherwise by a COURSE
 * PRIMARY credential; there is no platform fallback for this analysis type, whether the request is
 * a manual "Analyze" click or automatic. Merge commits are never reviewed. */
@Service @Profile("!test")
public class AiAnalysisSubmissionService {
	public static final String POLICY_VERSION = "commit-intelligence-v1";
	/** v4: a message is CLEAR only with a type, a task key and what changed; named strengths (GOOD_) for good
	 * code. Reviews made with an older prompt count as outdated and the leader's backfill re-runs them.
	 * Needs saga-ai serving v4 (it still serves v1-v3). */
	public static final String PROMPT_VERSION = "commit-intelligence-v4";
	/** At most this many linked tasks go to the AI (saga-ai caps a request at 100 evidence items). */
	static final int MAX_TASKS_IN_EVIDENCE = 8;
	private TeamAiCredentialService teamKeys;
	private TaskCommitManualLinkRepository manualLinks;
	private AiCommitReviewContextBuilder reviewContext;

	@Autowired(required = false) public void setTeamKeys(TeamAiCredentialService teamKeys) { this.teamKeys = teamKeys; }
	@Autowired(required = false) public void setManualLinks(TaskCommitManualLinkRepository manualLinks) { this.manualLinks = manualLinks; }
	@Autowired(required = false) public void setReviewContext(AiCommitReviewContextBuilder reviewContext) { this.reviewContext = reviewContext; }
	private CommitReviewEvents reviewEvents;
	@Autowired(required = false) public void setReviewEvents(CommitReviewEvents reviewEvents) { this.reviewEvents = reviewEvents; }
	public static final String SCHEMA_VERSION = "ai-2-schema-v1";
	private final ProjectDataAuthorization authorization; private final GitCommitRepository commits; private final TaskGitCommitLinkRepository links; private final JiraTaskFailoverItemRepository failover; private final AiAnalysisRunRepository runs; private final AiAnalysisEvidenceRepository evidence; private final AiAnalysisProviderDecisionRepository decisions; private final AiCommitEvidenceSnapshotBuilder snapshots; private final AiGitHubCommitEvidenceAcquirer githubEvidence; private final AiAnalysisExecutor executor; private final List<AiModelProvider> providers; private final TransactionTemplate transactions; private final AiCredentialResolver credentialResolver; private final CourseAiSettingsService courseSettings;
	public AiAnalysisSubmissionService(ProjectDataAuthorization authorization, GitCommitRepository commits, TaskGitCommitLinkRepository links, JiraTaskFailoverItemRepository failover, AiAnalysisRunRepository runs, AiAnalysisEvidenceRepository evidence, AiAnalysisProviderDecisionRepository decisions, AiCommitEvidenceSnapshotBuilder snapshots, AiGitHubCommitEvidenceAcquirer githubEvidence, AiAnalysisExecutor executor, List<AiModelProvider> providers, PlatformTransactionManager transactionManager, AiCredentialResolver credentialResolver, CourseAiSettingsService courseSettings) { this.authorization = authorization; this.commits = commits; this.links = links; this.failover = failover; this.runs = runs; this.evidence = evidence; this.decisions = decisions; this.snapshots = snapshots; this.githubEvidence = githubEvidence; this.executor = executor; this.providers = providers; this.transactions = AiSubmissionTransactions.requiresNew(transactionManager); this.credentialResolver = credentialResolver; this.courseSettings = courseSettings; }

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
		if (commit.looksLikeMerge()) return Optional.empty();
		// The team pays with its own key: reviewed whatever the course automation setting is.
		if (teamKey(projectId).isPresent()) return Optional.of(submit(projectId, gitCommitId, AiInvocationOrigin.AUTOMATION));
		Course course = commit.getRepo().getProject().getCourse();
		if (course == null || !courseSettings.get(course.getId()).automationEnabled()) return Optional.empty();
		var resolution = credentialResolver.resolveCourseForProject(projectId, course.getId(), AiAnalysisType.COMMIT_INTELLIGENCE, AiProviderRole.PRIMARY, AiInvocationOrigin.AUTOMATION);
		if (resolution.outcome() != AiCredentialResolver.Outcome.COURSE) return Optional.empty();
		return Optional.of(submit(projectId, gitCommitId, AiInvocationOrigin.AUTOMATION));
	}

	private Submission submit(UUID projectId, UUID gitCommitId, AiInvocationOrigin origin) {
		AiModelProvider provider = providers.stream().filter(p -> p.role() == AiProviderRole.PRIMARY).findFirst().orElseThrow(() -> new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_PROVIDER_FAILED, HttpStatus.SERVICE_UNAVAILABLE, "AI provider is not configured."));
		GitCommit commit = commits.findAnalysisTargetById(gitCommitId).orElseThrow(() -> new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_NOT_FOUND, HttpStatus.NOT_FOUND, "Commit was not found."));
		if (!commit.getRepo().getProject().getId().equals(projectId)) throw new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_PROJECT_MISMATCH, HttpStatus.NOT_FOUND, "Commit does not belong to this project.");
		requireNotMerge(commit);
		UUID courseId = commit.getRepo().getProject().getCourse() == null ? null : commit.getRepo().getProject().getCourse().getId();
		TeamAiCredentialService.TeamKeyRef team = teamKey(projectId).orElse(null);
		AiCredentialResolver.Resolution resolution = null;
		if (team == null) {
			resolution = credentialResolver.resolveCourseForProject(projectId, courseId, AiAnalysisType.COMMIT_INTELLIGENCE, AiProviderRole.PRIMARY, origin);
			if (resolution.outcome() != AiCredentialResolver.Outcome.COURSE) throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_UNAVAILABLE, HttpStatus.CONFLICT, "Nhóm chưa nhập key AI và chưa được giảng viên cho dùng key của lớp.");
		}
		List<TaskGitCommitLink> taskLinks = withManualLinks(projectId, commit, links.findAnalysisEvidenceByGitCommitId(gitCommitId, projectId)); List<UUID> taskIds = taskLinks.stream().map(link -> link.getTask().getId()).toList(); List<JiraTaskFailoverItem> lineage = taskIds.isEmpty() ? List.of() : failover.findSuccessfulLineageByTaskIds(taskIds);
		List<AiEvidenceDraft> draft = new ArrayList<>(snapshots.build(commit, taskLinks, lineage));
		if (reviewContext != null) draft.addAll(reviewContext.build(projectId));
		List<AiEvidenceDraft> githubDraft = githubEvidence.acquire(new AiGitHubCommitEvidenceAcquirer.Target(commit.getRepo().getId(), commit.getRepo().getInstallation() == null ? null : commit.getRepo().getInstallation().getInstallationId(), commit.getRepo().getOwnerLogin(), commit.getRepo().getName(), commit.getShaHash()));
		// The push webhook does not carry parents; GitHub's commit detail does. A merge found here is
		// remembered on the commit and never reviewed.
		Integer parents = AiGitHubCommitEvidenceAcquirer.parentCountOf(githubDraft);
		if (parents != null && commit.getParentCount() == null) {
			transactions.executeWithoutResult(status -> commits.setParentCountIfUnknown(commit.getId(), parents));
			commit.setParentCount(parents);
		}
		requireNotMerge(commit);
		draft.addAll(githubDraft);
		String revision = commit.getShaHash() == null || commit.getShaHash().isBlank() ? commit.getId().toString() : commit.getShaHash(); String evidenceHash = hashEvidence(draft); String idempotency = AiHashes.sha256(String.join("|", projectId.toString(), AiArtifactType.COMMIT.name(), gitCommitId.toString(), revision, AiAnalysisType.COMMIT_INTELLIGENCE.name(), evidenceHash, POLICY_VERSION, PROMPT_VERSION, com.saga.be.ai.AiSystemContract.OUTPUT_REVISION, SCHEMA_VERSION, provider.providerConfigHash(), team != null ? team.identity() : credentialIdentity(resolution)));
		final AiCredentialResolver.Resolution courseResolution = resolution;
		try { return Objects.requireNonNull(transactions.execute(status -> persist(commit, draft, evidenceHash, idempotency, revision, provider, courseResolution, team, origin))); }
		catch (AiRetryLineage.RetryAttemptConflict ex) { AiAnalysisRun concurrent = ex.winner(runs); if (AiRetryLineage.shouldEnqueueExisting(concurrent)) enqueueAfterCommit(concurrent.getId()); return new Submission(concurrent, false); }
	}
	private Submission persist(GitCommit commit, List<AiEvidenceDraft> draft, String evidenceHash, String idempotency, String revision, AiModelProvider provider, AiCredentialResolver.Resolution resolution, TeamAiCredentialService.TeamKeyRef team, AiInvocationOrigin origin) {
		AiAnalysisRun existing = AiRetryLineage.effective(runs, idempotency); if (existing != null && !AiRetryLineage.createsCommitReviewRetry(existing, origin)) { if (AiRetryLineage.shouldEnqueueExisting(existing)) enqueueAfterCommit(existing.getId()); return new Submission(existing, false); }
		int retryAttempt = AiRetryLineage.nextAttempt(existing);
		AiAnalysisRun run = new AiAnalysisRun(); run.setProject(commit.getRepo().getProject()); run.setArtifactType(AiArtifactType.COMMIT); run.setArtifactId(commit.getId()); run.setArtifactRevision(revision); run.setAnalysisType(AiAnalysisType.COMMIT_INTELLIGENCE); run.setStatus(AiAnalysisStatus.QUEUED); run.setEvidenceHash(evidenceHash); run.setPolicyVersion(POLICY_VERSION); run.setPromptVersion(PROMPT_VERSION); run.setSchemaVersion(SCHEMA_VERSION); run.setProviderConfigHash(provider.providerConfigHash()); run.setIdempotencyKey(idempotency);
		AiRetryLineage.initialize(run, idempotency, retryAttempt);
		try { run = runs.saveAndFlush(run); } catch (DataIntegrityViolationException ex) { throw AiRetryLineage.conflict(idempotency, retryAttempt, ex); }
		if (reviewEvents != null) reviewEvents.queued(commit.getRepo().getProject().getId());
		int ordinal = 0; List<AiAnalysisEvidence> rows = new ArrayList<>(); for (AiEvidenceDraft item : draft) { AiAnalysisEvidence row = new AiAnalysisEvidence(); row.setAnalysisRun(run); row.setEvidenceType(item.type()); row.setSourceRef(item.sourceRef()); row.setContentHash(item.contentHash()); row.setPayloadJson(item.payloadJson()); row.setMetadataJson(item.metadataJson()); row.setOrdinalIndex(ordinal++); rows.add(row); } evidence.saveAll(rows);
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision(); decision.setAnalysisRun(run); decision.setProviderRole(AiProviderRole.PRIMARY); decision.setProviderKey(provider.providerKey()); decision.setProviderConfigHash(provider.providerConfigHash()); decision.setCredentialSource(AiCredentialSource.COURSE);
		if (team != null) {
			// Wire format stays COURSE (saga-ai forwards the sealed key); the team key is recorded here.
			decision.setTeamCredentialId(team.id()); decision.setCredentialFingerprint(team.fingerprint()); decision.setAiProvider(team.binding().provider()); decision.setModelId(team.binding().modelId());
		} else {
			decision.setCourseCredentialId(resolution.courseCredentialId()); decision.setCredentialFingerprint(resolution.credentialFingerprint()); decision.setModelId(provider.modelId()); AiCredentialResolver.applyBinding(decision, resolution);
		} decision.setRoute(AiProviderRoute.NORMAL); decision.setStatus(AiProviderDecisionStatus.PENDING); decisions.save(decision); enqueueAfterCommit(run.getId()); return new Submission(run, true);
	}
	private Optional<TeamAiCredentialService.TeamKeyRef> teamKey(UUID projectId) { return teamKeys == null ? Optional.empty() : teamKeys.usableKey(projectId); }

	/** A merge commit only joins work that already exists. */
	static void requireNotMerge(GitCommit commit) {
		if (commit.looksLikeMerge()) throw new IntegrationException(IntegrationErrorCode.AI_COMMIT_MERGE_NOT_REVIEWED, HttpStatus.UNPROCESSABLE_ENTITY, "Merge commit chỉ gộp code đã có nên SAGA không đánh giá AI cho merge commit.");
	}

	/** Tasks attached by hand join the AI's task context (never persisted as TaskGitCommitLink, so
	 * never scored); bounded so the request stays within saga-ai's evidence limit. */
	private List<TaskGitCommitLink> withManualLinks(UUID projectId, GitCommit commit, List<TaskGitCommitLink> automatic) {
		List<TaskGitCommitLink> out = new ArrayList<>(automatic);
		if (manualLinks != null) {
			Set<UUID> seen = new HashSet<>(); for (TaskGitCommitLink link : automatic) seen.add(link.getTask().getId());
			for (var manual : manualLinks.findFetchedByProjectAndCommitIds(projectId, List.of(commit.getId()))) {
				if (manual.getTask().getJiraIntegration() == null || !seen.add(manual.getTask().getId())) continue;
				TaskGitCommitLink view = new TaskGitCommitLink(); view.setTask(manual.getTask()); view.setGitCommit(commit); view.setLinkSource(TraceLinkSource.MANUAL); view.setConfidence("MANUAL");
				out.add(view);
			}
		}
		return out.size() <= MAX_TASKS_IN_EVIDENCE ? out : List.copyOf(out.subList(0, MAX_TASKS_IN_EVIDENCE));
	}

	private static String credentialIdentity(AiCredentialResolver.Resolution resolution) { return resolution.outcome() + "|" + (resolution.identityFingerprint() == null ? "" : resolution.identityFingerprint()); }
	private static String hashEvidence(List<AiEvidenceDraft> draft) { return AiHashes.sha256(draft.stream().map(row -> row.type() + "|" + row.sourceRef() + "|" + row.contentHash()).reduce("", (a, b) -> a + "\n" + b)); }
	private void enqueueAfterCommit(UUID runId) { if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { executor.enqueue(runId); } }); else executor.enqueue(runId); }
	public record Submission(AiAnalysisRun run, boolean created) {}
}
