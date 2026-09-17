package com.saga.be.dto.project;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Daily activity heatmap for a team or one ACTIVE member. Not a contribution/grade formula.")
public record HeatmapResponse(
		UUID courseId,
		UUID teamId,
		UUID studentId,
		LocalDate startDate,
		LocalDate endDate,
		List<StudentHeatmap> students,
		List<HeatmapCell> days) {

	@Schema(description = "One ACTIVE member plus per-day activity cells covering the requested range.")
	public record StudentHeatmap(
			UUID studentId,
			String studentCode,
			String fullName,
			long commits,
			long peerReviews,
			long comments,
			long documents,
			long tasks,
			long totalActivities,
			long totalScore,
			List<HeatmapCell> cells) {}

	@Schema(description = "One calendar day of activity counts and weighted score.")
	public record HeatmapCell(
			LocalDate date,
			long commits,
			long peerReviews,
			long comments,
			long documents,
			long tasks,
			long totalActivities,
			long totalScore) {}
}
