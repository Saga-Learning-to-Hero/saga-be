package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

@Schema(
		description =
				"Personal task and V23 commit metrics limited to the selected sprint (the same sprint as "
						+ "currentSprint). Tasks are those assigned to the student in that sprint; commits are "
						+ "authored V23 commits whose stored committedAt date falls in [startDate, endDate].")
public record StudentDashboardSprintMetricsResponse(
		UUID sprintId,
		@Schema(description = "Sprint start date. Null when the sprint has no start date.") LocalDate startDate,
		@Schema(description = "Inclusive commit window end: sprint endDate, else completeDate, else today.")
				LocalDate endDate,
		StudentDashboardTaskMetricsResponse tasks,
		@Schema(description = "Null when the sprint has no start date, so no commit window can be defined.")
				StudentDashboardCommitMetricsResponse commits) {}
