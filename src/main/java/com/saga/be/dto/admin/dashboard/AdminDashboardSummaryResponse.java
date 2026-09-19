package com.saga.be.dto.admin.dashboard;

import java.util.List;

/**
 * Phase A admin dashboard contract. weeklyTimeline, projectHealthDistribution,
 * sprintMilestones, integrationsHealth, and unconnectedTeamsAlert are omitted until later
 * phases — they are not present as empty placeholders.
 */
public record AdminDashboardSummaryResponse(
		AdminDashboardSelectedSemesterResponse selectedSemester,
		List<AdminDashboardAvailableSemesterResponse> availableSemesters,
		AdminDashboardKpisResponse kpis,
		AdminDashboardCacheMetadataResponse cacheMetadata) {}
