package com.saga.be.graph;

import java.util.List;
import java.util.UUID;

public record ProjectGraphSnapshot(
		UUID projectId,
		String projectName,
		TeamNode team,
		List<StudentNode> students,
		List<SprintNode> sprints,
		List<TaskNode> tasks,
		List<CommitNode> commits,
		List<TaskCommitLink> links,
		List<ReviewEdge> reviews,
		List<TaskHierarchyLink> hierarchy) {

	public record TeamNode(UUID id, String name) {}

	public record StudentNode(
			UUID id, String name, String studentCode, String avatar, String role) {}

	public record SprintNode(UUID id, String name, String state) {}

	public record TaskNode(
			UUID id,
			UUID sprintId,
			UUID assigneeStudentId,
			String key,
			String title,
			String status,
			Integer storyPoint,
			String weightType,
			boolean classified,
			boolean anomaly,
			int linkedCommitCount,
			/** EPIC / STORY / TASK / BUG / SUBTASK / REQUEST (SAGA's normalized type, display only). */
			String issueType,
			/** The Jira type name as the team sees it, e.g. "Feature". */
			String issueTypeName,
			String issueTypeId,
			/** SUBTASK / STANDARD / EPIC / ABOVE_EPIC / UNKNOWN (not reported by Jira yet). */
			String issueTypeLevel,
			/** Jira's raw hierarchyLevel (-1 / 0 / 1 / 2+). */
			Integer jiraHierarchyLevel,
			UUID jiraIntegrationId,
			String parentExternalId,
			String parentExternalKey,
			/** RESOLVED / UNRESOLVED; null for a top-level item (no Jira parent). */
			String parentResolution,
			/** PARENT_NOT_SYNCED / PARENT_SOURCE_REVOKED when UNRESOLVED. */
			String parentResolutionReason,
			/** No Jira parent at all: linked to the Project by HAS_WORK_ITEM. */
			boolean topLevel) {}

	public record CommitNode(
			UUID id,
			String sha,
			String message,
			String authorSubject,
			UUID authorStudentId,
			boolean unmapped) {}

	public record TaskCommitLink(UUID taskId, UUID commitId) {}

	/** Jira parent -> child (Epic -> Story/Task/Bug, Task -> Subtask), both tasks of this project. */
	public record TaskHierarchyLink(UUID parentTaskId, UUID childTaskId) {}

	public record ReviewEdge(UUID reviewerStudentId, UUID revieweeStudentId, UUID sprintId, int stars) {}
}
