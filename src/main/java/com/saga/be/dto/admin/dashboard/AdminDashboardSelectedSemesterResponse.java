package com.saga.be.dto.admin.dashboard;

import java.time.LocalDate;
import java.util.UUID;

public record AdminDashboardSelectedSemesterResponse(
		UUID id,
		String code,
		String name,
		LocalDate startDate,
		LocalDate endDate,
		int totalWeeks,
		Integer currentWeekIndex,
		boolean active) {}
