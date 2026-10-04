package com.saga.be.dto.project;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.saga.be.dto.integration.failover.TaskMigrationSummary;

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
		/** Jira's issue type id. Compare this (not a display group) to detect a type change. Null until synced. */
		String issueTypeId,
		/**
		 * Normalized level: SUBTASK (-1) / STANDARD (0) / EPIC (1) / ABOVE_EPIC (2+) / UNKNOWN (Jira has
		 * not reported it yet -- sync the Jira source). Never null. While UNKNOWN, issue type and
		 * parent edits stay locked.
		 */
		String issueTypeLevel,
		/** Jira's raw hierarchyLevel, unchanged (-1 / 0 / 1 / 2+); null when unknown. */
		Integer jiraHierarchyLevel,
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
		/**
		 * File + web-link cardinality on this task (the two student-submitted evidence lists).
		 * Does not include commits — those stay in {@link #linkedCommitCount()}. Counts only;
		 * never the file/link payloads.
		 */
		@Schema(description = "task_file + task_web_link count. Excludes commits. Not the evidence payload.")
		long evidenceCount,
		/**
		 * {@code evidenceCount > 0}. Pipeline/KPI flag so FE does not N+1 list files and web links.
		 */
		@Schema(description = "true when evidenceCount > 0. FE must not infer this from files/web-links lists.")
		boolean hasEvidence,
		LocalDateTime externalUpdatedAt,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		/** Native SAGA parent (id + title). Null when unset or the parent row is deleted. Distinct from {@link #parent()}. */
		ParentTask parentTask,
		/**
		 * Compact Jira source provenance. Null only when the Task row has no integration (should not
		 * occur after V24). REVOKED sources remain visible — lists are not filtered by ACTIVE.
		 */
		TaskJiraSourceSummary source,
		/** Direct failover lineage; source and target history remain visible in this list. */
		TaskMigrationSummary migration,
		/**
		 * Direct active children only. Null on list/create/patch (omitted from JSON). Populated on
		 * detail. Never recursive.
		 */
		@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
		List<Subtask> subtasks,
		@Schema(
						description =
								"Which proof this task needs by its SAGA label and whether it is there. Use this for "
										+ "'missing commit' / 'missing document' warnings -- never guess from the title.")
				EvidenceCheck evidenceCheck,
		@Schema(
						description =
								"Date warnings: start after due, start after the sprint ends, or due before the "
										+ "sprint starts. A start before the sprint is not flagged (carried over / moved "
										+ "in from the backlog) and a due date past the sprint end is allowed "
										+ "(runsPastSprint). Never null.")
				ScheduleCheck scheduleCheck) {

	/**
	 * @param issues START_AFTER_DUE, START_AFTER_SPRINT, DUE_BEFORE_SPRINT; empty = fine (or nothing to check)
	 * @param sprintStartDate the task's sprint start, null when backlog / unset
	 * @param sprintEndDate the task's sprint end (complete date once closed), null when backlog / unset
	 * @param runsPastSprint due date is after the sprint end: the task will carry over into the next sprint (allowed)
	 */
	public record ScheduleCheck(List<String> issues, LocalDate sprintStartDate, LocalDate sprintEndDate, boolean runsPastSprint) {}

	/**
	 * @param categories SAGA markers on the task: CODE, TEST, DOCUMENT, RESEARCH (empty = none)
	 * @param requiresCommit a code/test marker is present: needs a linked non-merge commit
	 * @param requiresDocument a document/research marker is present: needs a file, web link or Jira attachment
	 * @param commitEvidenceCount linked non-merge commits counted as proof
	 * @param documentEvidenceCount uploaded files + web links + Jira attachments
	 * @param status NOT_DONE | SATISFIED | MISSING_COMMIT | MISSING_DOCUMENT | MISSING_COMMIT_AND_DOCUMENT | UNLABELED
	 */
	public record EvidenceCheck(
			List<String> categories,
			boolean requiresCommit,
			boolean requiresDocument,
			long commitEvidenceCount,
			long documentEvidenceCount,
			String status) {}

	public record Assignee(String accountId, String displayName, UUID studentId, String avatarUrl) {}

	public record PriorityDetail(String id, String name) {}

	/**
	 * Jira parent. {@code taskId} is the SAGA task of the same Jira source with that Jira id (null
	 * when not found). {@code resolution}: RESOLVED / UNRESOLVED; {@code resolutionReason} when
	 * UNRESOLVED: PARENT_NOT_SYNCED / PARENT_SOURCE_REVOKED. Both null only on responses that do
	 * not resolve parents.
	 */
	public record Parent(
			String externalId, String externalKey, UUID taskId, String resolution, String resolutionReason) {}

	public record ParentTask(UUID id, String title) {}

	/** A direct Jira child (Epic -> its items, item -> its Subtasks). issueTypeLevel never null (UNKNOWN). */
	public record Subtask(
			UUID id, String title, String status, String externalKey, String issueTypeName, String issueTypeLevel) {}
}
