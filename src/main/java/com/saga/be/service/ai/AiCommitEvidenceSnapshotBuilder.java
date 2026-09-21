package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.entity.enums.AiEvidenceType;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/** Builds an immutable local-DB-only manifest. It deliberately contains no provider reads or code diff. */
@Component
@Profile("!test")
public class AiCommitEvidenceSnapshotBuilder {
	private final ObjectMapper mapper;
	public AiCommitEvidenceSnapshotBuilder(ObjectMapper mapper) { this.mapper = mapper; }

	public List<AiEvidenceDraft> build(GitCommit commit, List<TaskGitCommitLink> links, List<JiraTaskFailoverItem> lineage) {
		List<AiEvidenceDraft> out = new ArrayList<>();
		out.add(new AiEvidenceDraft(AiEvidenceType.COMMIT_MESSAGE, "git_commit:" + commit.getId() + ":message", json(Map.of("message", nullable(commit.getMessage()))), null));
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("gitCommitId", commit.getId()); metadata.put("repositoryId", commit.getRepo().getId()); metadata.put("sha", commit.getShaHash());
		metadata.put("authorExternalId", commit.getAuthorExternalId()); metadata.put("authorStudentId", commit.getAuthorStudent() == null ? null : commit.getAuthorStudent().getId());
		metadata.put("committedAt", commit.getCommittedAt()); metadata.put("parentCount", commit.getParentCount()); metadata.put("additions", commit.getAdditions()); metadata.put("deletions", commit.getDeletions()); metadata.put("filesChanged", commit.getFilesChanged());
		metadata.put("headRef", commit.getHeadRef()); metadata.put("signatureVerified", commit.getSignatureVerified()); metadata.put("verificationReason", commit.getVerificationReason());
		out.add(new AiEvidenceDraft(AiEvidenceType.METADATA, "git_commit:" + commit.getId(), json(metadata), null));
		Map<UUID, JiraTaskFailoverItem> sourceLineage = new HashMap<>();
		for (JiraTaskFailoverItem item : lineage) sourceLineage.put(item.getSourceTask().getId(), item);
		for (TaskGitCommitLink link : links) {
			var task = link.getTask(); Map<String, Object> taskData = new LinkedHashMap<>();
			taskData.put("taskId", task.getId()); taskData.put("title", task.getTitle()); taskData.put("description", task.getDescription()); taskData.put("status", task.getStatus());
			taskData.put("jiraIntegrationId", task.getJiraIntegration().getId()); taskData.put("externalId", task.getExternalId()); taskData.put("externalKey", task.getExternalKey());
			taskData.put("linkSource", link.getLinkSource()); taskData.put("linkConfidence", link.getConfidence()); taskData.put("jiraKeySnapshot", link.getJiraKeySnapshot());
			JiraTaskFailoverItem item = sourceLineage.get(task.getId()); taskData.put("currentOperational", item == null); taskData.put("supersededByTaskId", item == null ? null : item.getTargetTask().getId());
			out.add(new AiEvidenceDraft(AiEvidenceType.TASK_FIELD, "task:" + task.getId(), json(taskData), null));
		}
		out.add(new AiEvidenceDraft(AiEvidenceType.EXCLUSION_MANIFEST, "local-commit-evidence-limitations", json(Map.of("codeDiffAvailable", false, "changedFilePathsAvailable", false, "fileContentAtRevisionAvailable", false, "providerReadsPerformed", false)), null));
		return List.copyOf(out);
	}
	private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("AI evidence serialization failed", ex); } }
	private static Object nullable(Object value) { return value == null ? "" : value; }
}
