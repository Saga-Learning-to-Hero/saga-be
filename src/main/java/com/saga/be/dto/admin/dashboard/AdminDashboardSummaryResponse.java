package com.saga.be.dto.admin.dashboard;

import java.util.List;

/**
 * Phase A+B admin dashboard contract. projectHealthDistribution, sprintMilestones,
 * integrationsHealth, and unconnectedTeamsAlert are omitted until later phases — they are
 * not present as empty placeholders.
 */
public record AdminDashboardSummaryResponse(
		AdminDashboardSelectedSemesterResponse selectedSemester,
		List<AdminDashboardAvailableSemesterResponse> availableSemesters,
		AdminDashboardKpisResponse kpis,
		List<AdminDashboardWeeklyPointResponse> weeklyTimeline,
		AdminDashboardCacheMetadataResponse cacheMetadata) {}
