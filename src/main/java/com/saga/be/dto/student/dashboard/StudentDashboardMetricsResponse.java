package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Personal project-wide task and V23 commit metrics. Contribution is not included in Phase B1.")
public record StudentDashboardMetricsResponse(
		StudentDashboardTaskMetricsResponse tasks, StudentDashboardCommitMetricsResponse commits) {}
