package com.saga.be.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.jira.Sprint;
import com.saga.be.service.task.TaskSchedulePolicy.Issue;
import com.saga.be.service.task.TaskSchedulePolicy.SprintWindow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TaskSchedulePolicyTest {

	private static final SprintWindow SPRINT_5 = new SprintWindow(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 3));

	@Test
	void datesInsideTheSprintIncludingItsFirstAndLastDayAreFine() {
		assertThat(TaskSchedulePolicy.issues(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 3), SPRINT_5)).isEmpty();
	}

	@Test
	void startAfterDueIsReportedWithOrWithoutASprint() {
		assertThat(TaskSchedulePolicy.issues(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 21), null))
				.containsExactly(Issue.START_AFTER_DUE);
		assertThat(TaskSchedulePolicy.issues(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 25), null)).isEmpty();
	}

	@Test
	void everyWayOfLeavingTheSprintIsNamed() {
		assertThat(TaskSchedulePolicy.issues(LocalDate.of(2026, 9, 19), null, SPRINT_5)).containsExactly(Issue.START_BEFORE_SPRINT);
		assertThat(TaskSchedulePolicy.issues(LocalDate.of(2026, 10, 4), null, SPRINT_5)).containsExactly(Issue.START_AFTER_SPRINT);
		assertThat(TaskSchedulePolicy.issues(null, LocalDate.of(2026, 9, 19), SPRINT_5)).containsExactly(Issue.DUE_BEFORE_SPRINT);
		assertThat(TaskSchedulePolicy.issues(null, LocalDate.of(2026, 10, 4), SPRINT_5)).containsExactly(Issue.DUE_AFTER_SPRINT);
	}

	@Test
	void missingDatesOrSprintBoundsAreNotChecked() {
		assertThat(TaskSchedulePolicy.issues(null, null, SPRINT_5)).isEmpty();
		assertThat(TaskSchedulePolicy.issues(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1), null)).isEmpty();
		assertThat(TaskSchedulePolicy.issues(LocalDate.of(2020, 1, 1), null, new SprintWindow(null, LocalDate.of(2026, 10, 3))))
				.isEmpty();
	}

	@Test
	void closedSprintEndsOnItsCompleteDateAndBacklogHasNoWindow() {
		Sprint closed = new Sprint();
		closed.setState("closed");
		closed.setStartDate(LocalDateTime.of(2026, 9, 6, 9, 0));
		closed.setEndDate(LocalDateTime.of(2026, 9, 20, 17, 0));
		closed.setCompleteDate(LocalDateTime.of(2026, 9, 18, 10, 0));

		assertThat(TaskSchedulePolicy.windowOf(closed))
				.isEqualTo(new SprintWindow(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 18)));
		assertThat(TaskSchedulePolicy.windowOf(null)).isNull();
	}
}
