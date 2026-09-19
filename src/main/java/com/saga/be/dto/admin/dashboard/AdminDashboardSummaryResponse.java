package com.saga.be.dto.admin.dashboard;

import java.util.List;

/**
 * Phase A+B+C+D admin dashboard contract. projectHealthDistribution, sprintMilestones,
 * and provider health/latency/successRate are omitted — they are not present as empty
 * placeholders. {@code unconnectedTeamsAlert} is always a list (empty when none).
 * {@code integrationPulse} is platform-wide unique-delivery rows for GITHUB and JIRA;
 * it is composed from a separate cache and is not semester-scoped.
 */
public record AdminDashboardSummaryResponse(
		AdminDashboardSelectedSemesterResponse selectedSemester,
		List<AdminDashboardAvailableSemesterResponse> availableSemesters,
		AdminDashboardKpisResponse kpis,
		List<AdminDashboardWeeklyPointResponse> weeklyTimeline,
		List<AdminDashboardUnconnectedTeamResponse> unconnectedTeamsAlert,
		List<AdminDashboardIntegrationPulseResponse> integrationPulse,
		AdminDashboardCacheMetadataResponse cacheMetadata) {}
