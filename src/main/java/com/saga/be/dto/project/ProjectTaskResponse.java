package com.saga.be.dto.project;

import java.time.LocalDate;
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
		/**
		 * Jira's {@code duedate} -- a factual business deadline, NEVER to be confused with {@link
		 * #createdAt()}/{@link #updatedAt()}/{@link #externalUpdatedAt()} (all row/provider
		 * modification timestamps) or the Sprint's own start/end dates. Null when Jira has no due
		 * date set on this issue.
		 */
		LocalDate dueDate,
		/**
		 * Jira's Start Date custom field -- a factual planned-start date, NEVER to be confused with
		 * {@link #createdAt()}/{@link #updatedAt()}/{@link #externalUpdatedAt()} (row/provider
		 * modification timestamps) or the Sprint's own start/end dates. Null when Jira has no Start
		 * Date set on this issue, or the site has no discoverable Start Date field at all.
		 */
		LocalDate startDate,
		/**
		 * Raw {@code task_git_commit_link} cardinality, including known merge commits. Task Evidence
		 * COMMIT total excludes only known merges ({@code parent_count > 1}).
		 */
		long linkedCommitCount,
		LocalDateTime externalUpdatedAt,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		/** Native SAGA parent (id + title). Null when unset or the parent row is deleted. Distinct from {@link #parent()}. */
		ParentTask parentTask,
		/**
		 * Direct active children only. Null on list/create/patch (omitted from JSON). Populated on
		 * detail. Never recursive.
		 */
		@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
		List<Subtask> subtasks) {

	public record Assignee(String accountId, String displayName, UUID studentId) {}

	public record PriorityDetail(String id, String name) {}

	public record Parent(String externalId, String externalKey) {}

	public record ParentTask(UUID id, String title) {}

	public record Subtask(UUID id, String title, String status) {}
}
