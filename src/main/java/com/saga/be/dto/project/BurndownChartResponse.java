package com.saga.be.dto.project;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Sprint burndown from projected tasks. Not a contribution/grade formula.")
public record BurndownChartResponse(
		UUID courseId,
		UUID teamId,
		UUID sprintId,
		String sprintName,
		LocalDate startDate,
		LocalDate endDate,
		int totalScope,
		List<BurndownPoint> points) {

	@Schema(
			description =
					"""
					One calendar day of remaining tasks (end-of-day).
					idealRemaining is the even-pace guideline computed by the backend:
					idealRemaining(i) = clamp(round(totalScope * (n-1-i) / (n-1)), 0, totalScope)
					where i is the 0-based day index, n is the inclusive day count, and round is
					Java Math.round (nearest integer, halves away from zero).
					Start day (i=0) is totalScope. End day (i=n-1) is 0.
					A one-day sprint (n=1) does not divide by zero: idealRemaining is 0.
					When totalScope is 0 every point is 0. Sequence is monotone non-increasing;
					plateaus are expected when totalScope < n-1. FE must not recompute this.
					""")
	public record BurndownPoint(
			LocalDate date,
			int idealRemaining,
			int actualRemaining,
			int doneCount) {}
}
