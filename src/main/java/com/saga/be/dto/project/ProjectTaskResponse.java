package com.saga.be.dto.project;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ProjectTaskResponse(
		UUID id,
		String externalId,
		String externalKey,
		String title,
		String description,
		String status,
		String jiraStatusId,
		String jiraStatusName,
		String issueTypeName,
		/** Flat alias of {@link Assignee#accountId()} for existing clients. */
		String assigneeExternalId,
		/** Flat alias of {@link Assignee#displayName()}. */
		String assigneeDisplayName,
		UUID assigneeStudentId,
		Assignee assignee,
		/** Flat priority name (enum / Jira name). Prefer {@link #priorityDetail()}. */
		String priority,
		PriorityDetail priorityDetail,
		Integer storyPoint,
		ProjectTaskSprintResponse sprint,
		/**
		 * Jira's own parent issue identity (Subtask -> parent, or Team-managed Story/Task -> Epic).
		 * Null when this issue has no Jira parent. Factual provider hierarchy, not filtered by
		 * {@link #issueTypeName()} -- FE decides how to render based on both.
		 */
		Parent parent,
		/** Jira labels on this issue, parsed from the canonical stored representation. Never null (empty list = no labels). */
		List<String> labels,
		long linkedCommitCount,
		LocalDateTime externalUpdatedAt,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {

	public record Assignee(String accountId, String displayName, UUID studentId) {}

	public record PriorityDetail(String id, String name) {}

	public record Parent(String externalId, String externalKey) {}
}
