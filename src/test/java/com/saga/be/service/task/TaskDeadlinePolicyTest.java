package com.saga.be.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.enums.TaskStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TaskDeadlinePolicyTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 10, 12, 0);
	private static final Duration WINDOW = Duration.ofHours(72);

	@Test
	void doneTaskIsNeverFlaggedRegardlessOfDueDate() {
		assertThat(TaskDeadlinePolicy.classify(TaskStatus.DONE, NOW.minusDays(5), NOW, WINDOW)).isEqualTo(TaskDeadlinePolicy.Status.NONE);
	}

	@Test
	void taskWithoutDueDateIsNeverFlagged() {
		assertThat(TaskDeadlinePolicy.classify(TaskStatus.IN_PROGRESS, null, NOW, WINDOW)).isEqualTo(TaskDeadlinePolicy.Status.NONE);
	}

	@Test
	void pastDueDateOnNonDoneTaskIsOverdue() {
		assertThat(TaskDeadlinePolicy.classify(TaskStatus.IN_PROGRESS, NOW.minusMinutes(1), NOW, WINDOW)).isEqualTo(TaskDeadlinePolicy.Status.OVERDUE);
	}

	@Test
	void dueDateWithinWindowIsDueSoon() {
		assertThat(TaskDeadlinePolicy.classify(TaskStatus.TODO, NOW.plusHours(48), NOW, WINDOW)).isEqualTo(TaskDeadlinePolicy.Status.DUE_SOON);
	}

	@Test
	void dueDateExactlyAtWindowBoundaryIsDueSoon() {
		assertThat(TaskDeadlinePolicy.classify(TaskStatus.TODO, NOW.plus(WINDOW), NOW, WINDOW)).isEqualTo(TaskDeadlinePolicy.Status.DUE_SOON);
	}

	@Test
	void dueDateBeyondWindowIsNone() {
		assertThat(TaskDeadlinePolicy.classify(TaskStatus.TODO, NOW.plus(WINDOW).plusMinutes(1), NOW, WINDOW)).isEqualTo(TaskDeadlinePolicy.Status.NONE);
	}

	@Test
	void blockedNonTerminalTaskCanStillBeOverdue() {
		assertThat(TaskDeadlinePolicy.classify(TaskStatus.BLOCKED, NOW.minusHours(1), NOW, WINDOW)).isEqualTo(TaskDeadlinePolicy.Status.OVERDUE);
	}

	@Test
	void nullWindowDefaultsToZeroInsteadOfThrowing() {
		// With a zero window, only a due date at or before "now" counts as due-soon; anything in
		// the future (even one minute away) is neither overdue nor due-soon yet.
		assertThat(TaskDeadlinePolicy.classify(TaskStatus.TODO, NOW, NOW, null)).isEqualTo(TaskDeadlinePolicy.Status.DUE_SOON);
		assertThat(TaskDeadlinePolicy.classify(TaskStatus.TODO, NOW.plusMinutes(1), NOW, null)).isEqualTo(TaskDeadlinePolicy.Status.NONE);
	}
}
