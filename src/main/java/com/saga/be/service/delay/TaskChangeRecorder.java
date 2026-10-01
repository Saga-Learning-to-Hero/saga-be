package com.saga.be.service.delay;

import com.saga.be.entity.delay.TaskChangeLog;
import com.saga.be.entity.enums.DelayCaseEnums.TaskChangeField;
import com.saga.be.entity.jira.Task;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Detects due-date, story-point and assignee changes Jira sync makes to an existing task, so a
 * delay explanation ("the schedule moved", "work was added", "reassigned late") can be checked.
 */
public final class TaskChangeRecorder {

	/** The watched fields of a task before an update. */
	public record Snapshot(String dueDate, String storyPoint, String assignee) {}

	private TaskChangeRecorder() {}

	public static Snapshot snapshot(Task task) {
		return new Snapshot(dueDate(task), storyPoint(task), assignee(task));
	}

	/** One log row per watched field whose value differs from {@code before}. */
	public static List<TaskChangeLog> diff(Task task, Snapshot before, LocalDateTime changedAt) {
		List<TaskChangeLog> out = new ArrayList<>();
		add(out, task, TaskChangeField.DUE_DATE, before.dueDate(), dueDate(task), changedAt);
		add(out, task, TaskChangeField.STORY_POINT, before.storyPoint(), storyPoint(task), changedAt);
		add(out, task, TaskChangeField.ASSIGNEE, before.assignee(), assignee(task), changedAt);
		return out;
	}

	private static void add(
			List<TaskChangeLog> out, Task task, TaskChangeField field, String oldValue, String newValue, LocalDateTime at) {
		if (Objects.equals(oldValue, newValue)) {
			return;
		}
		TaskChangeLog log = new TaskChangeLog();
		log.setTask(task);
		log.setField(field);
		log.setOldValue(oldValue);
		log.setNewValue(newValue);
		log.setChangedAt(at == null ? LocalDateTime.now() : at);
		out.add(log);
	}

	private static String dueDate(Task task) {
		return task.getDueDate() == null ? null : task.getDueDate().toLocalDate().toString();
	}

	private static String storyPoint(Task task) {
		return task.getStoryPoint() == null ? null : String.valueOf(task.getStoryPoint());
	}

	/** The SAGA student when mapped, else the Jira account id. */
	private static String assignee(Task task) {
		if (task.getAssigneeStudent() != null && task.getAssigneeStudent().getId() != null) {
			return "student:" + task.getAssigneeStudent().getId();
		}
		return task.getAssigneeExternalId() == null ? null : "jira:" + task.getAssigneeExternalId();
	}
}
