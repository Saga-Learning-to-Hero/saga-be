package com.saga.be.service.delay;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.delay.TaskChangeLog;
import com.saga.be.entity.enums.DelayCaseEnums.TaskChangeField;
import com.saga.be.entity.jira.Task;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DelaySignalsAndChangesTest {

	private static final LocalDateTime START = LocalDateTime.of(2026, 9, 20, 0, 0);
	private static final LocalDateTime DUE = LocalDateTime.of(2026, 10, 4, 0, 0);

	@Test
	void recorderLogsOnlyTheWatchedFieldsThatChanged() {
		Task task = new Task();
		task.setDueDate(DUE);
		task.setStoryPoint(3);
		task.setAssigneeExternalId("acc-a");
		TaskChangeRecorder.Snapshot before = TaskChangeRecorder.snapshot(task);

		task.setDueDate(DUE.plusDays(2));
		task.setStoryPoint(5);
		StudentProfile student = new StudentProfile();
		student.setId(UUID.randomUUID());
		task.setAssigneeStudent(student);
		List<TaskChangeLog> logs = TaskChangeRecorder.diff(task, before, DUE.minusDays(1));

		assertThat(logs).extracting(TaskChangeLog::getField)
				.containsExactly(TaskChangeField.DUE_DATE, TaskChangeField.STORY_POINT, TaskChangeField.ASSIGNEE);
		assertThat(logs.get(0).getOldValue()).isEqualTo("2026-10-04");
		assertThat(logs.get(0).getNewValue()).isEqualTo("2026-10-06");
		assertThat(logs.get(1).getNewValue()).isEqualTo("5");
		assertThat(logs.get(2).getOldValue()).isEqualTo("jira:acc-a");
		assertThat(logs.get(2).getNewValue()).isEqualTo("student:" + student.getId());
		assertThat(logs).allMatch(log -> log.getChangedAt().equals(DUE.minusDays(1)) && log.getTask() == task);

		assertThat(TaskChangeRecorder.diff(task, TaskChangeRecorder.snapshot(task), DUE)).isEmpty();
	}

	@Test
	void dueDateAndStoryPointSignalsCountOnlyChangesAfterTheStart() {
		List<TaskChangeLog> history = List.of(
				log(TaskChangeField.DUE_DATE, "2026-10-01", "2026-10-04", START.minusDays(1)),
				log(TaskChangeField.STORY_POINT, "5", "3", START.plusDays(2)));

		assertThat(DelaySignalCollector.changedAfter(history, TaskChangeField.DUE_DATE, START)).isFalse();
		assertThat(DelaySignalCollector.storyPointIncreasedAfter(history, START)).isFalse(); // it went down

		List<TaskChangeLog> grown = List.of(log(TaskChangeField.STORY_POINT, "3", "8", START.plusDays(2)));
		assertThat(DelaySignalCollector.storyPointIncreasedAfter(grown, START)).isTrue();
		assertThat(DelaySignalCollector.storyPointIncreasedAfter(
				List.of(log(TaskChangeField.STORY_POINT, null, "2", START.plusDays(1))), START)).isTrue();
		assertThat(DelaySignalCollector.changedAfter(
				List.of(log(TaskChangeField.DUE_DATE, "2026-10-04", "2026-10-06", START.plusDays(3))),
				TaskChangeField.DUE_DATE,
				START)).isTrue();
	}

	@Test
	void reassignmentCountsWhenTheTaskReachedThisPersonWithinThreeDaysOfTheDueDay() {
		String me = "student:" + UUID.randomUUID();
		List<TaskChangeLog> near = List.of(log(TaskChangeField.ASSIGNEE, "student:other", me, DUE.minusDays(2)));
		List<TaskChangeLog> firstAssignmentOnDueDay = List.of(log(TaskChangeField.ASSIGNEE, null, me, DUE.plusHours(9)));
		List<TaskChangeLog> early = List.of(log(TaskChangeField.ASSIGNEE, "student:other", me, DUE.minusDays(6)));
		List<TaskChangeLog> toSomeoneElse = List.of(log(TaskChangeField.ASSIGNEE, me, "student:other", DUE.minusDays(1)));

		assertThat(DelaySignalCollector.reassignedNear(near, DUE, me)).isTrue();
		assertThat(DelaySignalCollector.reassignedNear(firstAssignmentOnDueDay, DUE, me)).isTrue();
		assertThat(DelaySignalCollector.reassignedNear(early, DUE, me)).isFalse();
		assertThat(DelaySignalCollector.reassignedNear(toSomeoneElse, DUE, me)).isFalse();
		assertThat(DelaySignalCollector.reassignedNear(
				List.of(log(TaskChangeField.ASSIGNEE, null, me, DUE.plusDays(1))), DUE, me)).isFalse();
	}

	@Test
	void noActivityMeansNoCommitWorkSessionOrEvidence() {
		assertThat(new DelaySignals(null, null, false, 0, null, null, 0, null, null, 0, false, false, false, 0).noActivity()).isTrue();
		assertThat(new DelaySignals(null, null, false, 1, null, null, 0, null, null, 0, false, false, false, 0).noActivity()).isFalse();
		assertThat(new DelaySignals(null, null, false, 0, null, null, 0, null, null, 2, false, false, false, 0).noActivity()).isFalse();
	}

	private static TaskChangeLog log(TaskChangeField field, String oldValue, String newValue, LocalDateTime at) {
		TaskChangeLog log = new TaskChangeLog();
		log.setField(field);
		log.setOldValue(oldValue);
		log.setNewValue(newValue);
		log.setChangedAt(at);
		return log;
	}
}
