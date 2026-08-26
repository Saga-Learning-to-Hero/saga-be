package com.saga.be.service.attribution;

import com.saga.be.entity.enums.SagaCompletionState;
import com.saga.be.entity.enums.TaskStatus;
import java.time.LocalDateTime;

public final class TaskCompletionEvaluator {

	private TaskCompletionEvaluator() {}

	public static SagaCompletionState evaluate(
			TaskStatus jiraStatus,
			String jiraStatusCategory,
			LocalDateTime dueDate,
			LocalDateTime completionTimestamp,
			LocalDateTime now) {
		boolean done = isTerminal(jiraStatus, jiraStatusCategory);
		if (done) {
			if (dueDate == null) {
				return SagaCompletionState.COMPLETED_NO_DUE_DATE;
			}
			LocalDateTime completed = completionTimestamp != null ? completionTimestamp : now;
			return completed.isAfter(dueDate) ? SagaCompletionState.COMPLETED_LATE : SagaCompletionState.COMPLETED_ON_TIME;
		}
		if (dueDate != null && now.isAfter(dueDate)) {
			return SagaCompletionState.OVERDUE;
		}
		if (jiraStatus == TaskStatus.TODO || jiraStatus == null) {
			return SagaCompletionState.NOT_STARTED;
		}
		return SagaCompletionState.IN_PROGRESS;
	}

	public static boolean isTerminal(TaskStatus status, String statusCategory) {
		if (status == TaskStatus.DONE) {
			return true;
		}
		if (statusCategory == null) {
			return false;
		}
		String normalized = statusCategory.trim().toLowerCase();
		return normalized.contains("done") || normalized.contains("complete");
	}
}
