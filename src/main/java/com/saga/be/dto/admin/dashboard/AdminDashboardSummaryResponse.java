package com.saga.be.dto.admin.dashboard;

import java.util.List;

/**
 * Phase A+B+C admin dashboard contract. projectHealthDistribution, sprintMilestones,
 * and integrationsHealth are omitted until later phases — they are not present as empty
 * placeholders. {@code unconnectedTeamsAlert} is always a list (empty when none).
 */
public record AdminDashboardSummaryResponse(
		AdminDashboardSelectedSemesterResponse selectedSemester,
		List<AdminDashboardAvailableSemesterResponse> availableSemesters,
		AdminDashboardKpisResponse kpis,
		List<AdminDashboardWeeklyPointResponse> weeklyTimeline,
		List<AdminDashboardUnconnectedTeamResponse> unconnectedTeamsAlert,
		AdminDashboardCacheMetadataResponse cacheMetadata) {}
