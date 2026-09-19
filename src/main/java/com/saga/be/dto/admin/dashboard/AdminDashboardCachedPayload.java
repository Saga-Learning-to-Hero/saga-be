package com.saga.be.dto.admin.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * Redis cache body. {@code generation} changes on every successful compute so forceRefresh
 * followers can observe a new value without relying on {@code cachedAt} uniqueness. Live TTL
 * fields are not stored here.
 */
public record AdminDashboardCachedPayload(
		String generation,
		Instant cachedAt,
		AdminDashboardSelectedSemesterResponse selectedSemester,
		List<AdminDashboardAvailableSemesterResponse> availableSemesters,
		AdminDashboardKpisResponse kpis) {}
