package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(
		description =
				"Phase A+B1+B2+D1+D2 student personal dashboard: identity, optional team/project, integrations, "
						+ "current sprint, personal task/commit metrics (project-wide and selected-sprint), attention "
						+ "preview, recent commits, commit timeline, and MSR / ghosting / peer-review pending alerts.")
public record StudentDashboardResponse(
		StudentDashboardStudentResponse student,
		StudentDashboardCourseResponse course,
		@Schema(description = "Null when the student is ACTIVE in the course but not assigned to a team.")
				StudentDashboardTeamResponse team,
		@Schema(description = "Null when there is no team project yet.")
				StudentDashboardIntegrationsResponse integrations,
		@Schema(
						description =
								"The selected sprint's stats. With no ?sprintId= query param, this is the project's "
										+ "current active sprint (null when there is none). With ?sprintId=, this is that "
										+ "local Sprint's stats instead (historical/completed sprints allowed). The same "
										+ "sprint also drives sprintMetrics, weeklyCommits, myActiveTasks and recentCommits; "
										+ "myMetrics and actionableAlerts stay current/project-wide.")
				StudentDashboardSprintResponse currentSprint,
		@Schema(
						description =
								"Project-wide, never sprint-scoped. Null when there is no team project. Empty zeros "
										+ "when the student has no personal work.")
				StudentDashboardMetricsResponse myMetrics,
		@Schema(
						description =
								"Needs-attention preview, cap 10: only the selected sprint's tasks when currentSprint "
										+ "is non-null, else project-wide. Empty when no team project or no matching tasks.")
				List<StudentDashboardActiveTaskResponse> myActiveTasks,
		@Schema(
						description =
								"Recent personal V23 commits, cap 5: only commits whose committedAt date falls in the "
										+ "selected sprint's [startDate, endDate] when that window exists, else project-wide. "
										+ "Empty when no team project or no commits.")
				List<StudentDashboardRecentCommitResponse> recentCommits,
		@Schema(
						description =
								"Personal commit timeline, oldest first. When a sprint is selected (currentSprint "
										+ "non-null with a startDate): one point per day (startDate == endDate) from the "
										+ "sprint start to min(sprint end, today), capped to the last 62 days; empty for a "
										+ "sprint that has not started. Otherwise: the last 3 ISO calendar weeks (Mon–Sun). "
										+ "Empty when no team project.")
				List<StudentDashboardWeeklyCommitResponse> weeklyCommits,
		@Schema(
						description =
								"Never null. Empty with no team / no project. Order: MSR_ANOMALY, GHOSTING_WARNING, "
										+ "PEER_REVIEW_PENDING.")
				List<StudentDashboardAlertResponse> actionableAlerts,
		@Schema(description = "Personal metrics for the currentSprint sprint. Null when currentSprint is null.")
				StudentDashboardSprintMetricsResponse sprintMetrics) {}
