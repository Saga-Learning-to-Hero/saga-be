package com.saga.be.dto.admin.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

public record AdminDashboardWeeklyPointResponse(
		int weekIndex,
		String weekLabel,
		LocalDate startDate,
		LocalDate endDate,
		@JsonProperty("isCurrentWeek") boolean isCurrentWeek,
		long commits,
		long tasksCompleted,
		Double traceabilityRate) {}
