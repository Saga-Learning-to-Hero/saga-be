package com.saga.be.service.task;

import com.saga.be.entity.jira.Sprint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Date rules for a task, compared as calendar dates:
 *
 * <ul>
 *   <li>its start date may not be after its due date;
 *   <li>when it belongs to a sprint, both dates must fall inside that sprint: from the sprint's
 *       start date to its end date (a closed sprint ends on its real complete date).
 * </ul>
 *
 * SAGA rejects a create/edit that sets dates breaking these rules. Tasks that drift out later
 * (the sprint's dates change, the task is moved to another sprint, or it is edited in Jira) are
 * only reported, never blocked, through {@link #issues}.
 */
public final class TaskSchedulePolicy {

	public enum Issue {
		START_AFTER_DUE,
		START_BEFORE_SPRINT,
		START_AFTER_SPRINT,
		DUE_BEFORE_SPRINT,
		DUE_AFTER_SPRINT
	}

	/** A sprint's date range; either end may be null when Jira has not set it. */
	public record SprintWindow(LocalDate start, LocalDate end) {}

	private TaskSchedulePolicy() {}

	/** Null when there is no sprint (backlog). */
	public static SprintWindow windowOf(Sprint sprint) {
		if (sprint == null || sprint.getDeletedAt() != null) {
			return null;
		}
		boolean closed = "closed".equals(sprint.getState() == null ? "" : sprint.getState().trim().toLowerCase(Locale.ROOT));
		LocalDateTime end = closed && sprint.getCompleteDate() != null ? sprint.getCompleteDate() : sprint.getEndDate();
		return new SprintWindow(
				sprint.getStartDate() == null ? null : sprint.getStartDate().toLocalDate(),
				end == null ? null : end.toLocalDate());
	}

	/** Every rule the given dates break; empty when they are fine or nothing can be checked. */
	public static List<Issue> issues(LocalDate start, LocalDate due, SprintWindow window) {
		List<Issue> issues = new ArrayList<>();
		if (start != null && due != null && start.isAfter(due)) {
			issues.add(Issue.START_AFTER_DUE);
		}
		if (window != null) {
			if (start != null && window.start() != null && start.isBefore(window.start())) {
				issues.add(Issue.START_BEFORE_SPRINT);
			}
			if (start != null && window.end() != null && start.isAfter(window.end())) {
				issues.add(Issue.START_AFTER_SPRINT);
			}
			if (due != null && window.start() != null && due.isBefore(window.start())) {
				issues.add(Issue.DUE_BEFORE_SPRINT);
			}
			if (due != null && window.end() != null && due.isAfter(window.end())) {
				issues.add(Issue.DUE_AFTER_SPRINT);
			}
		}
		return issues;
	}
}
