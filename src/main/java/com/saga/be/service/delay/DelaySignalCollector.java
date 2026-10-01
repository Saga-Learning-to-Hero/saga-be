package com.saga.be.service.delay;

import com.saga.be.entity.delay.TaskChangeLog;
import com.saga.be.entity.enums.DelayCaseEnums.TaskChangeField;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.repository.TaskAttachmentRepository;
import com.saga.be.repository.TaskChangeLogRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/** Gathers {@link DelaySignals} for one task from data SAGA already stores. */
@Component
@org.springframework.context.annotation.Profile("!test")
public class DelaySignalCollector {

	static final int REASSIGN_WINDOW_DAYS = 3;
	static final int LOAD_WINDOW_DAYS = 7;

	private final TaskRepository tasks;
	private final TaskGitCommitLinkRepository commitLinks;
	private final TaskWorkSessionRepository workSessions;
	private final TaskFileRepository files;
	private final TaskWebLinkRepository webLinks;
	private final TaskAttachmentRepository attachments;
	private final TaskChangeLogRepository changeLogs;

	public DelaySignalCollector(
			TaskRepository tasks,
			TaskGitCommitLinkRepository commitLinks,
			TaskWorkSessionRepository workSessions,
			TaskFileRepository files,
			TaskWebLinkRepository webLinks,
			TaskAttachmentRepository attachments,
			TaskChangeLogRepository changeLogs) {
		this.tasks = tasks;
		this.commitLinks = commitLinks;
		this.workSessions = workSessions;
		this.files = files;
		this.webLinks = webLinks;
		this.attachments = attachments;
		this.changeLogs = changeLogs;
	}

	/** {@code dueDate} is the missed due date of the case (the task's may have moved since). */
	public DelaySignals collect(Task task, LocalDateTime dueDate) {
		Object[] commits = firstRow(commitLinks.summarizeNonMergeCommitsByTask(task.getId()));
		Object[] sessions = firstRow(workSessions.summarizeByTask(task.getId()));
		long evidence = files.countByTask_Id(task.getId())
				+ webLinks.countByTask_Id(task.getId())
				+ attachments.countByTask_Id(task.getId());
		LocalDateTime startedAt = task.getStartDate() != null ? task.getStartDate() : task.getCreatedAt();
		List<TaskChangeLog> history = changeLogs.findByTask_IdOrderByChangedAtAsc(task.getId());
		long otherOpen = task.getProject() == null || task.getAssigneeStudent() == null
				? 0
				: tasks.countOpenTasksOfAssigneeDueBetween(
						task.getProject().getId(),
						task.getAssigneeStudent().getId(),
						task.getId(),
						dueDate.minusDays(LOAD_WINDOW_DAYS),
						dueDate.plusDays(LOAD_WINDOW_DAYS));
		return new DelaySignals(
				dueDate.toLocalDate(),
				startedAt,
				task.getStatus() == TaskStatus.BLOCKED,
				count(commits[0]),
				time(commits[1]),
				time(commits[2]),
				count(sessions[0]),
				time(sessions[1]),
				time(sessions[2]),
				evidence,
				changedAfter(history, TaskChangeField.DUE_DATE, startedAt),
				storyPointIncreasedAfter(history, startedAt),
				reassignedNear(
						history,
						dueDate,
						task.getAssigneeStudent() == null ? null : "student:" + task.getAssigneeStudent().getId()),
				otherOpen);
	}

	static boolean changedAfter(List<TaskChangeLog> history, TaskChangeField field, LocalDateTime startedAt) {
		return history.stream()
				.anyMatch(log -> log.getField() == field && (startedAt == null || !log.getChangedAt().isBefore(startedAt)));
	}

	static boolean storyPointIncreasedAfter(List<TaskChangeLog> history, LocalDateTime startedAt) {
		return history.stream()
				.filter(log -> log.getField() == TaskChangeField.STORY_POINT)
				.filter(log -> startedAt == null || !log.getChangedAt().isBefore(startedAt))
				.anyMatch(log -> number(log.getNewValue()) > number(log.getOldValue()));
	}

	/**
	 * The task was handed to this person (first assignment or a reassignment) within the window
	 * before the due date. {@code assigneeKey} is the {@link TaskChangeRecorder} value of the person
	 * explaining; null matches any new assignee.
	 */
	static boolean reassignedNear(List<TaskChangeLog> history, LocalDateTime dueDate, String assigneeKey) {
		LocalDateTime from = dueDate.minusDays(REASSIGN_WINDOW_DAYS);
		// The due date is the start of the due day; the whole day still counts.
		LocalDateTime to = dueDate.plusDays(1);
		return history.stream()
				.filter(log -> log.getField() == TaskChangeField.ASSIGNEE)
				.filter(log -> assigneeKey == null || assigneeKey.equals(log.getNewValue()))
				.anyMatch(log -> !log.getChangedAt().isBefore(from) && log.getChangedAt().isBefore(to));
	}

	private static double number(String value) {
		if (value == null || value.isBlank()) {
			return 0;
		}
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	private static Object[] firstRow(List<Object[]> rows) {
		return rows == null || rows.isEmpty() || rows.getFirst() == null ? new Object[] {0L, null, null} : rows.getFirst();
	}

	private static long count(Object value) {
		return value instanceof Number number ? number.longValue() : 0L;
	}

	private static LocalDateTime time(Object value) {
		return value instanceof LocalDateTime time ? time : null;
	}
}
