package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Personal assigned-task buckets for the team project. Not sprint-scoped.")
public record StudentDashboardTaskMetricsResponse(
		long totalAssigned,
		long todo,
		long inProgress,
		long inReview,
		long done,
		long blocked,
		@Schema(description = "DONE / totalAssigned * 100, or null when totalAssigned is 0.") Double completionPercent,
		long totalStoryPoints,
		long completedStoryPoints) {}
