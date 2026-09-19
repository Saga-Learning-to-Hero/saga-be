package com.saga.be.dto.admin.dashboard;

import java.time.LocalDate;
import java.util.UUID;

public record AdminDashboardAvailableSemesterResponse(
		UUID id,
		String code,
		String name,
		LocalDate startDate,
		LocalDate endDate,
		boolean active,
		SemesterPeriodStatus periodStatus) {}
