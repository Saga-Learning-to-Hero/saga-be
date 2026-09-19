package com.saga.be.dto.admin.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * Redis cache body for semester summary v3. {@code generation} changes on every successful
 * compute so forceRefresh followers can observe a new value without relying on
 * {@code cachedAt} uniqueness. Live TTL fields are not stored here.
 *
 * <p>{@code integrationPulse} is intentionally absent: it lives in the separate global key
 * {@code saga:admin:dashboard:integration-pulse:v1}. Do not bump this payload to v4 for pulse.
 */
public record AdminDashboardCachedPayload(
		String generation,
		Instant cachedAt,
		AdminDashboardSelectedSemesterResponse selectedSemester,
		List<AdminDashboardAvailableSemesterResponse> availableSemesters,
		AdminDashboardKpisResponse kpis,
		List<AdminDashboardWeeklyPointResponse> weeklyTimeline,
		List<AdminDashboardUnconnectedTeamResponse> unconnectedTeamsAlert) {}
