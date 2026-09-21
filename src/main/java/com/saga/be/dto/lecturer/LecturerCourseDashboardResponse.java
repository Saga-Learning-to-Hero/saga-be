package com.saga.be.dto.lecturer;

import com.saga.be.entity.enums.ContributionConfigMode;
import com.saga.be.entity.enums.DashboardScope;
import com.saga.be.entity.enums.LecturerDashboardRiskLevel;
import com.saga.be.entity.enums.LecturerDashboardRiskReasonCode;
import com.saga.be.entity.enums.LecturerDashboardRiskUnit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "One-request lecturer dashboard for a course. Current sprint is resolved per project.")
public record LecturerCourseDashboardResponse(
		UUID courseId,
		String courseCode,
		String subjectCode,
		String subjectName,
		String classCode,
		String semesterCode,
		DashboardScope scope,
		Instant generatedAt,
		RiskPolicy riskPolicy,
		Summary summary,
		TaskStatusTotals taskStatusTotals,
		List<TeamCard> teams) {

	public record RiskPolicy(
			int inactivityWarningDays,
			int inactivityCriticalDays,
			double scheduleLagWarningPercentagePoints,
			double scheduleLagCriticalPercentagePoints,
			double peerReviewWarningElapsedPercent) {}

	public record Summary(
			long enrolledStudents,
			long unassignedStudents,
			long totalTeams,
			long healthyTeams,
			long warningTeams,
			long criticalTeams,
			long unknownTeams,
			long teamsWithoutProject,
			long teamsWithoutActiveSprint,
			long teamsWithSyncFailure) {}

	public record TaskStatusTotals(
			long total,
			long todo,
			long inProgress,
			long inReview,
			long done,
			long blocked,
			long overdue,
			Double completionPercent) {}

	public record TeamCard(
			UUID teamId,
			int teamNo,
			String teamName,
			UUID projectId,
			String projectName,
			int memberCount,
			CurrentSprint currentSprint,
			Progress progress,
			Activity activity,
			Traceability traceability,
			PeerReview peerReview,
			Sync sync,
			Configuration configuration,
			PreviousSprintComparison previousSprintComparison,
			Risk risk,
			@Schema(description = "Always null. This endpoint does not join notification history.")
			Reminder reminder) {}

	public record CurrentSprint(
			UUID sprintId,
			String sprintName,
			String state,
			LocalDate startDate,
			LocalDate endDate,
			Double elapsedPercent) {}

	public record Progress(
			long totalTasks,
			long todo,
			long inProgress,
			long inReview,
			long done,
			long blocked,
			long overdue,
			Double completionPercent,
			Double scheduleGapPercentagePoints) {}

	public record Activity(
			Instant lastActivityAt,
			Integer inactiveDays,
			long totalActivities,
			List<ActivityDay> series) {}

	public record ActivityDay(
			LocalDate date, long commits, long tasks, long peerReviews, long documents, long totalActivities) {}

	public record Traceability(
			long completedTasks,
			long completedTasksWithCommit,
			long completedTasksWithoutCommit,
			long linkedCommits,
			long unlinkedCommits,
			Double taskCommitLinkRate) {}

	public record PeerReview(
			long expectedReviews,
			long submittedReviews,
			Double completionRate,
			long pendingStudentCount,
			List<UUID> pendingStudentProfileIds,
			Instant deadlineAt) {}

	public record Sync(
			String jiraStatus,
			String jiraSyncStatus,
			Instant jiraLastSuccessfulSyncAt,
			String githubStatus,
			String githubSyncStatus,
			Instant githubLastSuccessfulSyncAt) {}

	public record Configuration(ContributionConfigMode contributionMode, boolean contributionWeightsConfigured) {}

	public record PreviousSprintComparison(
			UUID sprintId,
			String sprintName,
			Double completionDeltaPercentagePoints,
			Double activityDeltaPercent,
			Double traceabilityDeltaPercentagePoints) {}

	public record Risk(LecturerDashboardRiskLevel level, List<RiskReason> reasons) {}

	public record RiskReason(
			LecturerDashboardRiskReasonCode code,
			LecturerDashboardRiskLevel severity,
			Double actualValue,
			Double thresholdValue,
			LecturerDashboardRiskUnit unit,
			List<UUID> affectedStudentProfileIds) {}

	public record Reminder(Instant lastSentAt, String lastTargetType, UUID lastNotificationId) {}
}
