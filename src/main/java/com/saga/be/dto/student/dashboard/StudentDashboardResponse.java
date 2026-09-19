package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(
		description =
				"Phase A+B1+B2+D1+D2 student personal dashboard: identity, optional team/project, integrations, "
						+ "current sprint, personal task/commit metrics, attention preview, recent commits, "
						+ "rolling last-3 ISO weekly commits, and MSR / ghosting / peer-review pending alerts.")
public record StudentDashboardResponse(
		StudentDashboardStudentResponse student,
		StudentDashboardCourseResponse course,
		@Schema(description = "Null when the student is ACTIVE in the course but not assigned to a team.")
				StudentDashboardTeamResponse team,
		@Schema(description = "Null when there is no team project yet.")
				StudentDashboardIntegrationsResponse integrations,
		@Schema(description = "Null when the project has no non-deleted sprint whose Jira state is active.")
				StudentDashboardSprintResponse currentSprint,
		@Schema(description = "Null when there is no team project. Empty zeros when the student has no personal work.")
				StudentDashboardMetricsResponse myMetrics,
		@Schema(description = "Needs-attention preview, cap 10. Empty when no team project or no matching tasks.")
				List<StudentDashboardActiveTaskResponse> myActiveTasks,
		@Schema(description = "Recent personal V23 commits, cap 5. Empty when no team project or no commits.")
				List<StudentDashboardRecentCommitResponse> recentCommits,
		@Schema(
						description =
								"Last 3 ISO calendar weeks (Mon–Sun), oldest first. Empty when no team project. "
										+ "Three zero points when the project has no qualifying committedAt rows.")
				List<StudentDashboardWeeklyCommitResponse> weeklyCommits,
		@Schema(
						description =
								"Never null. Empty with no team / no project. Order: MSR_ANOMALY, GHOSTING_WARNING, "
										+ "PEER_REVIEW_PENDING.")
				List<StudentDashboardAlertResponse> actionableAlerts) {}
