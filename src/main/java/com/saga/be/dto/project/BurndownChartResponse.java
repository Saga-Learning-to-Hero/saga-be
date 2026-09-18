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

	@Schema(description = "One calendar day of remaining tasks.")
	public record BurndownPoint(LocalDate date, int actualRemaining, int doneCount) {}
}
