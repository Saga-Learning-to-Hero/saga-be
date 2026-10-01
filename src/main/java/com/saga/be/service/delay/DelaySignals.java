package com.saga.be.service.delay;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What the system itself can see about a missed deadline -- no AI, only stored facts. Shown to
 * the team leader and lecturer next to the explanation.
 *
 * @param startedAt the task's start date, else when SAGA first saw it
 * @param currentlyBlocked the task's status is BLOCKED (synced from Jira)
 * @param evidenceCount uploaded files + web links + Jira attachments
 * @param dueDateChanged the due date was changed after the task started (history recorded since V38)
 * @param storyPointIncreased story points went up after the task started
 * @param reassignedNearDue the assignee changed within {@link DelaySignalCollector#REASSIGN_WINDOW_DAYS} days before the due date
 * @param otherOpenTasksNearDue the same person's other unfinished tasks due within a week of this one
 */
public record DelaySignals(
		LocalDate dueDate,
		LocalDateTime startedAt,
		boolean currentlyBlocked,
		long commitCount,
		LocalDateTime firstCommitAt,
		LocalDateTime lastCommitAt,
		long workSessionCount,
		LocalDateTime firstWorkAt,
		LocalDateTime lastWorkAt,
		long evidenceCount,
		boolean dueDateChanged,
		boolean storyPointIncreased,
		boolean reassignedNearDue,
		long otherOpenTasksNearDue) {

	/** No commit, no work session and no evidence at all. */
	public boolean noActivity() {
		return commitCount == 0 && workSessionCount == 0 && evidenceCount == 0;
	}
}
