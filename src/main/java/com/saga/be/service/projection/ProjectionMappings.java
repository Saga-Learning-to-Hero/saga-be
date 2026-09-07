package com.saga.be.service.projection;

import com.saga.be.entity.enums.Priority;
import com.saga.be.entity.enums.SagaCompletionState;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TaskType;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Locale;

public final class ProjectionMappings {

	private ProjectionMappings() {}

	static TaskStatus taskStatus(String statusCategory, String statusName) {
		String category = statusCategory == null ? "" : statusCategory.toLowerCase(Locale.ROOT);
		if ("done".equals(category)) {
			return TaskStatus.DONE;
		}
		if ("indeterminate".equals(category)) {
			return TaskStatus.IN_PROGRESS;
		}
		if ("new".equals(category)) {
			return TaskStatus.TODO;
		}
		String name = statusName == null ? "" : statusName.toLowerCase(Locale.ROOT);
		if (name.contains("block")) {
			return TaskStatus.BLOCKED;
		}
		if (name.contains("review")) {
			return TaskStatus.IN_REVIEW;
		}
		if (name.contains("done") || name.contains("closed") || name.contains("resolved")) {
			return TaskStatus.DONE;
		}
		if (name.contains("progress")) {
			return TaskStatus.IN_PROGRESS;
		}
		return TaskStatus.TODO;
	}

	static TaskType taskType(String issueTypeName) {
		if (issueTypeName == null || issueTypeName.isBlank()) {
			return TaskType.TASK;
		}
		String name = issueTypeName.toLowerCase(Locale.ROOT);
		if (name.contains("bug")) {
			return TaskType.BUG;
		}
		if (name.contains("story")) {
			return TaskType.STORY;
		}
		if (name.contains("epic")) {
			return TaskType.EPIC;
		}
		if (name.contains("sub")) {
			return TaskType.SUBTASK;
		}
		return TaskType.TASK;
	}

	static Priority priority(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		return switch (name.trim().toLowerCase(Locale.ROOT)) {
			case "highest" -> Priority.HIGHEST;
			case "high" -> Priority.HIGH;
			case "medium", "moderate" -> Priority.MEDIUM;
			case "low" -> Priority.LOW;
			case "lowest" -> Priority.LOWEST;
			default -> null;
		};
	}

	static SagaCompletionState completion(TaskStatus status) {
		if (status == TaskStatus.DONE) {
			return SagaCompletionState.COMPLETED_NO_DUE_DATE;
		}
		if (status == TaskStatus.IN_PROGRESS || status == TaskStatus.IN_REVIEW) {
			return SagaCompletionState.IN_PROGRESS;
		}
		return SagaCompletionState.NOT_STARTED;
	}

	public static LocalDateTime parseInstant(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value).toLocalDateTime();
		} catch (Exception ignored) {
			try {
				return LocalDateTime.parse(value);
			} catch (Exception ignoredAgain) {
				return null;
			}
		}
	}
}
