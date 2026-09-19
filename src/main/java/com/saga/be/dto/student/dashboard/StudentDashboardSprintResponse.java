package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Current Jira-active sprint plus team task progress. daysRemaining is not included in Phase A.")
public record StudentDashboardSprintResponse(
		UUID id,
		String externalSprintId,
		String name,
		String state,
		LocalDateTime startDate,
		LocalDateTime endDate,
		long totalTasks,
		long completedTasks,
		@Schema(description = "DONE / total * 100, or null when totalTasks is 0.") Double completionPercent) {}
