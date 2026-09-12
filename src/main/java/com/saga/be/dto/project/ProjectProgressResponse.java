package com.saga.be.dto.project;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-only factual aggregation of a project's Jira/GitHub projections plus evidence counts.
 * Nothing here is computed contribution/grade — for a weighted per-student contribution
 * percentage, see the existing {@code GET /api/teams/{teamId}/contribution-evaluation} endpoint,
 * which this dashboard intentionally does not duplicate or re-derive.
 */
public record ProjectProgressResponse(
		UUID projectId,
		UUID teamId,
		int teamNo,
		String teamName,
		TaskSummary taskSummary,
		SprintSummary currentSprint,
		CommitSummary commitSummary,
		EvidenceSummary evidenceSummary,
		List<MemberSummary> memberProgress,
		SyncSummary sync,
		LocalDateTime lastActivityAt) {

	/** Counts by the normalized {@code TaskStatus} enum (TODO/IN_PROGRESS/IN_REVIEW/DONE/BLOCKED), never Jira's raw status text. */
	public record TaskSummary(
			long total,
			long todo,
			long inProgress,
			long inReview,
			long done,
			long blocked,
			Double completionPercent) {}

	/**
	 * The single sprint whose raw Jira {@code state} is {@code "active"}; {@code null} if none is
	 * active. Always last-known projected data, never a live Jira read — if {@code sync.jiraStatus}
	 * (on the enclosing response) is not {@code "ACTIVE"}, treat this as stale/last-synced rather
	 * than currently accurate.
	 */
	public record SprintSummary(
			UUID id,
			String externalSprintId,
			String name,
			String state,
			LocalDateTime startDate,
			LocalDateTime endDate,
			long totalTasks,
			long completedTasks) {}

	public record CommitSummary(long total, long linked, LocalDateTime lastCommitAt) {}

	/** Existence counts only — never exposes evidence file/link content, per protected-evidence rules. */
	public record EvidenceSummary(long workSessions, long files, long webLinks, long confirmations) {}

	public record MemberSummary(
			UUID studentId,
			UUID userId,
			String fullName,
			String studentCode,
			String teamRole,
			TaskAttribution taskSummary,
			CommitAttribution commitSummary) {}

	public record TaskAttribution(long assigned, long completed, long incomplete) {}

	/**
	 * {@code total} = commits authored by this student in this project (via the already-resolved
	 * {@code GitCommit.authorStudent} identity mapping). {@code linkedToTasks} = the subset of
	 * those commits with at least one {@code task_git_commit_link} row (a commit linked to several
	 * tasks is still counted once). {@code tasksWithLinkedCommits} = distinct tasks reached by
	 * those links.
	 */
	public record CommitAttribution(
			long total, long linkedToTasks, long tasksWithLinkedCommits, LocalDateTime lastCommitAt) {}

	/** Canonical DB-projected connection status only — never a live Jira/GitHub API call. */
	public record SyncSummary(
			String jiraStatus,
			String githubStatus,
			String jiraSyncStatus,
			LocalDateTime jiraLastSyncAt,
			String githubSyncStatus,
			LocalDateTime githubLastSyncAt) {}
}
