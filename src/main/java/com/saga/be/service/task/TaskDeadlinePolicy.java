package com.saga.be.service.task;

import com.saga.be.entity.enums.TaskStatus;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Pure, deterministic deadline classification. No AI, no I/O. Reuses the exact same "overdue"
 * definition already used by the lecturer dashboard ({@code TaskRepository.countOverdueBySprintIds}:
 * status is not DONE, a due date exists, and it is strictly before "now") so this feature does not
 * introduce a second, conflicting overdue definition alongside the existing one.
 *
 * <p>{@code dueDate} is a timezone-naive {@link LocalDateTime} (Jira date-picker value stored at
 * local midnight, per {@code Task.java}'s own field convention) and {@code now} must be supplied by
 * the caller using the exact same naive-UTC-wall-clock convention the rest of the codebase already
 * uses for this field (see {@code LecturerCourseDashboardService}), so this class never invents a
 * timezone-aware semantics that doesn't exist elsewhere in the schema.
 */
public final class TaskDeadlinePolicy {

	public enum Status { NONE, DUE_SOON, OVERDUE }

	private TaskDeadlinePolicy() {}

	public static Status classify(TaskStatus status, LocalDateTime dueDate, LocalDateTime now, Duration dueSoonWindow) {
		if (status == TaskStatus.DONE || dueDate == null || now == null) return Status.NONE;
		if (now.isAfter(dueDate)) return Status.OVERDUE;
		Duration window = dueSoonWindow == null || dueSoonWindow.isNegative() ? Duration.ZERO : dueSoonWindow;
		if (!now.plus(window).isBefore(dueDate)) return Status.DUE_SOON;
		return Status.NONE;
	}
}
