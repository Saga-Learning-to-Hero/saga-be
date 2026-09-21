package com.saga.be.service.ai;

import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.*;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Profile("!test")
public class AiAnalysisSubmissionService {
	public static final String POLICY_VERSION = "ai-1";
	public static final String PROMPT_VERSION = "ai-1-system-contract";
	public static final String SCHEMA_VERSION = "ai-1";
	private static final String FAKE_CONFIG_HASH = FakeAiModelProvider.CONFIG_HASH;
	private final ProjectDataAuthorization authorization; private final GitCommitRepository commits; private final TaskGitCommitLinkRepository links; private final JiraTaskFailoverItemRepository failover; private final AiAnalysisRunRepository runs; private final AiAnalysisEvidenceRepository evidence; private final AiAnalysisProviderDecisionRepository decisions; private final AiCommitEvidenceSnapshotBuilder snapshots; private final AiAnalysisExecutor executor;
	public AiAnalysisSubmissionService(ProjectDataAuthorization authorization, GitCommitRepository commits, TaskGitCommitLinkRepository links, JiraTaskFailoverItemRepository failover, AiAnalysisRunRepository runs, AiAnalysisEvidenceRepository evidence, AiAnalysisProviderDecisionRepository decisions, AiCommitEvidenceSnapshotBuilder snapshots, AiAnalysisExecutor executor) { this.authorization = authorization; this.commits = commits; this.links = links; this.failover = failover; this.runs = runs; this.evidence = evidence; this.decisions = decisions; this.snapshots = snapshots; this.executor = executor; }

	@Transactional
	public Submission submit(UUID userId, UUID projectId, UUID gitCommitId) {
		authorization.requireReader(userId, projectId);
		GitCommit commit = commits.findAnalysisTargetById(gitCommitId).orElseThrow(() -> new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_NOT_FOUND, HttpStatus.NOT_FOUND, "Commit was not found."));
		if (!commit.getRepo().getProject().getId().equals(projectId)) throw new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_PROJECT_MISMATCH, HttpStatus.NOT_FOUND, "Commit does not belong to this project.");
		List<TaskGitCommitLink> taskLinks = links.findAnalysisEvidenceByGitCommitId(gitCommitId, projectId);
		List<UUID> taskIds = taskLinks.stream().map(link -> link.getTask().getId()).toList();
		List<JiraTaskFailoverItem> lineage = taskIds.isEmpty() ? List.of() : failover.findSuccessfulLineageByTaskIds(taskIds);
		List<AiEvidenceDraft> draft = snapshots.build(commit, taskLinks, lineage);
		String evidenceHash = AiHashes.sha256(draft.stream().map(row -> row.type() + "|" + row.sourceRef() + "|" + row.contentHash()).reduce("", (a, b) -> a + "\n" + b));
		String artifactRevision = commit.getShaHash() == null || commit.getShaHash().isBlank() ? commit.getId().toString() : commit.getShaHash();
		String idempotency = AiHashes.sha256(String.join("|", projectId.toString(), AiArtifactType.COMMIT.name(), gitCommitId.toString(), artifactRevision, AiAnalysisType.COMMIT_INTELLIGENCE.name(), evidenceHash, POLICY_VERSION, PROMPT_VERSION, SCHEMA_VERSION, FAKE_CONFIG_HASH));
		AiAnalysisRun existing = runs.findByIdempotencyKey(idempotency).orElse(null);
		if (existing != null) { enqueueAfterCommit(existing.getId()); return new Submission(existing, false); }
		AiAnalysisRun run = new AiAnalysisRun(); run.setProject(commit.getRepo().getProject()); run.setArtifactType(AiArtifactType.COMMIT); run.setArtifactId(gitCommitId); run.setArtifactRevision(artifactRevision); run.setAnalysisType(AiAnalysisType.COMMIT_INTELLIGENCE); run.setStatus(AiAnalysisStatus.QUEUED); run.setEvidenceHash(evidenceHash); run.setPolicyVersion(POLICY_VERSION); run.setPromptVersion(PROMPT_VERSION); run.setSchemaVersion(SCHEMA_VERSION); run.setProviderConfigHash(FAKE_CONFIG_HASH); run.setIdempotencyKey(idempotency);
		try { run = runs.saveAndFlush(run); }
		catch (DataIntegrityViolationException ex) { AiAnalysisRun concurrent = runs.findByIdempotencyKey(idempotency).orElseThrow(() -> ex); enqueueAfterCommit(concurrent.getId()); return new Submission(concurrent, false); }
		int ordinal = 0; List<AiAnalysisEvidence> rows = new ArrayList<>();
		for (AiEvidenceDraft item : draft) { AiAnalysisEvidence row = new AiAnalysisEvidence(); row.setAnalysisRun(run); row.setEvidenceType(item.type()); row.setSourceRef(item.sourceRef()); row.setContentHash(item.contentHash()); row.setPayloadJson(item.payloadJson()); row.setMetadataJson(item.metadataJson()); row.setOrdinalIndex(ordinal++); rows.add(row); }
		evidence.saveAll(rows);
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision(); decision.setAnalysisRun(run); decision.setProviderRole(AiProviderRole.PRIMARY); decision.setProviderKey("fake"); decision.setProviderConfigHash(FAKE_CONFIG_HASH); decision.setModelId("fake-ai-1"); decision.setRoute(AiProviderRoute.CHEAP); decision.setStatus(AiProviderDecisionStatus.PENDING); decisions.save(decision);
		enqueueAfterCommit(run.getId()); return new Submission(run, true);
	}

	private void enqueueAfterCommit(UUID runId) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { executor.enqueue(runId); } });
		else executor.enqueue(runId);
	}
	public record Submission(AiAnalysisRun run, boolean created) {}
}
