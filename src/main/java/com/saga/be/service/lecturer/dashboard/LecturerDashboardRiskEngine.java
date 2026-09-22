package com.saga.be.service.lecturer.dashboard;

import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.Risk;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.RiskPolicy;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.RiskReason;
import com.saga.be.entity.enums.LecturerDashboardRiskLevel;
import com.saga.be.entity.enums.LecturerDashboardRiskReasonCode;
import com.saga.be.entity.enums.LecturerDashboardRiskUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pure risk rules for the lecturer course dashboard. No DB, no override. */
public final class LecturerDashboardRiskEngine {

	public static final int INACTIVITY_WARNING_DAYS = 3;
	public static final int INACTIVITY_CRITICAL_DAYS = 5;
	public static final double SCHEDULE_LAG_WARNING = 20.0;
	public static final double SCHEDULE_LAG_CRITICAL = 35.0;
	public static final double PEER_REVIEW_ELAPSED_WARNING = 80.0;

	public static final RiskPolicy POLICY = new RiskPolicy(
			INACTIVITY_WARNING_DAYS,
			INACTIVITY_CRITICAL_DAYS,
			SCHEDULE_LAG_WARNING,
			SCHEDULE_LAG_CRITICAL,
			PEER_REVIEW_ELAPSED_WARNING);

	private LecturerDashboardRiskEngine() {}

	public record Signals(
			boolean hasProject,
			boolean hasActiveSprint,
			Integer inactiveDays,
			boolean lastActivityKnown,
			Double scheduleGapPercentagePoints,
			long blockedTasks,
			long overdueTasks,
			long completedTasksWithoutCommit,
			boolean jiraSyncFailed,
			boolean githubSyncFailed,
			boolean contributionWeightsConfigured,
			boolean projectGroupMode,
			Double peerElapsedPercent,
			boolean sprintEnded,
			Long expectedReviews,
			Long submittedReviews,
			List<UUID> pendingStudentProfileIds) {}

	public static Risk evaluate(Signals signals) {
		List<RiskReason> reasons = new ArrayList<>();
		if (!signals.hasProject()) {
			reasons.add(reason(
					LecturerDashboardRiskReasonCode.NO_PROJECT,
					LecturerDashboardRiskLevel.CRITICAL,
					1,
					1,
					LecturerDashboardRiskUnit.COUNT,
					List.of()));
			return toRisk(reasons);
		}
		if (!signals.hasActiveSprint()) {
			reasons.add(reason(
					LecturerDashboardRiskReasonCode.NO_ACTIVE_SPRINT,
					LecturerDashboardRiskLevel.WARNING,
					0,
					1,
					LecturerDashboardRiskUnit.COUNT,
					List.of()));
		}
		if (!signals.lastActivityKnown()) {
			reasons.add(reason(
					LecturerDashboardRiskReasonCode.DATA_UNAVAILABLE,
					LecturerDashboardRiskLevel.UNKNOWN,
					null,
					null,
					LecturerDashboardRiskUnit.DAY,
					List.of()));
		} else if (signals.inactiveDays() != null) {
			if (signals.inactiveDays() >= INACTIVITY_CRITICAL_DAYS) {
				reasons.add(reason(
						LecturerDashboardRiskReasonCode.INACTIVE,
						LecturerDashboardRiskLevel.CRITICAL,
						signals.inactiveDays().doubleValue(),
						(double) INACTIVITY_CRITICAL_DAYS,
						LecturerDashboardRiskUnit.DAY,
						List.of()));
			} else if (signals.inactiveDays() >= INACTIVITY_WARNING_DAYS) {
				reasons.add(reason(
						LecturerDashboardRiskReasonCode.INACTIVE,
						LecturerDashboardRiskLevel.WARNING,
						signals.inactiveDays().doubleValue(),
						(double) INACTIVITY_WARNING_DAYS,
						LecturerDashboardRiskUnit.DAY,
						List.of()));
			}
		}
		if (signals.scheduleGapPercentagePoints() != null) {
			double gap = signals.scheduleGapPercentagePoints();
			if (gap >= SCHEDULE_LAG_CRITICAL) {
				reasons.add(reason(
						LecturerDashboardRiskReasonCode.SCHEDULE_LAG,
						LecturerDashboardRiskLevel.CRITICAL,
						gap,
						SCHEDULE_LAG_CRITICAL,
						LecturerDashboardRiskUnit.PERCENTAGE_POINT,
						List.of()));
			} else if (gap >= SCHEDULE_LAG_WARNING) {
				reasons.add(reason(
						LecturerDashboardRiskReasonCode.SCHEDULE_LAG,
						LecturerDashboardRiskLevel.WARNING,
						gap,
						SCHEDULE_LAG_WARNING,
						LecturerDashboardRiskUnit.PERCENTAGE_POINT,
						List.of()));
			}
		}
		if (signals.blockedTasks() >= 1) {
			reasons.add(reason(
					LecturerDashboardRiskReasonCode.BLOCKED_TASKS,
					LecturerDashboardRiskLevel.WARNING,
					(double) signals.blockedTasks(),
					1,
					LecturerDashboardRiskUnit.TASK,
					List.of()));
		}
		if (signals.overdueTasks() >= 1) {
			reasons.add(reason(
					LecturerDashboardRiskReasonCode.OVERDUE_TASKS,
					LecturerDashboardRiskLevel.WARNING,
					(double) signals.overdueTasks(),
					1,
					LecturerDashboardRiskUnit.TASK,
					List.of()));
		}
		if (signals.completedTasksWithoutCommit() >= 1) {
			reasons.add(reason(
					LecturerDashboardRiskReasonCode.MISSING_TASK_COMMIT_LINK,
					LecturerDashboardRiskLevel.WARNING,
					(double) signals.completedTasksWithoutCommit(),
					1,
					LecturerDashboardRiskUnit.TASK,
					List.of()));
		}
		if (signals.jiraSyncFailed()) {
			reasons.add(reason(
					LecturerDashboardRiskReasonCode.JIRA_SYNC_FAILED,
					LecturerDashboardRiskLevel.CRITICAL,
					1,
					1,
					LecturerDashboardRiskUnit.COUNT,
					List.of()));
		}
		if (signals.githubSyncFailed()) {
			reasons.add(reason(
					LecturerDashboardRiskReasonCode.GITHUB_SYNC_FAILED,
					LecturerDashboardRiskLevel.CRITICAL,
					1,
					1,
					LecturerDashboardRiskUnit.COUNT,
					List.of()));
		}
		if (signals.projectGroupMode() && !signals.contributionWeightsConfigured()) {
			reasons.add(reason(
					LecturerDashboardRiskReasonCode.CONTRIBUTION_CONFIG_MISSING,
					LecturerDashboardRiskLevel.WARNING,
					0,
					1,
					LecturerDashboardRiskUnit.COUNT,
					List.of()));
		}
		if (signals.expectedReviews() != null
				&& signals.submittedReviews() != null
				&& signals.expectedReviews() > 0
				&& signals.submittedReviews() < signals.expectedReviews()) {
			List<UUID> pending =
					signals.pendingStudentProfileIds() == null ? List.of() : signals.pendingStudentProfileIds();
			double actual = signals.expectedReviews() - signals.submittedReviews();
			if (signals.sprintEnded()) {
				reasons.add(reason(
						LecturerDashboardRiskReasonCode.PEER_REVIEW_INCOMPLETE,
						LecturerDashboardRiskLevel.CRITICAL,
						actual,
						0,
						LecturerDashboardRiskUnit.REVIEW,
						pending));
			} else if (signals.peerElapsedPercent() != null
					&& signals.peerElapsedPercent() >= PEER_REVIEW_ELAPSED_WARNING) {
				reasons.add(reason(
						LecturerDashboardRiskReasonCode.PEER_REVIEW_INCOMPLETE,
						LecturerDashboardRiskLevel.WARNING,
						actual,
						PEER_REVIEW_ELAPSED_WARNING,
						LecturerDashboardRiskUnit.REVIEW,
						pending));
			}
		}
		return toRisk(reasons);
	}

	private static Risk toRisk(List<RiskReason> reasons) {
		LecturerDashboardRiskLevel level = LecturerDashboardRiskLevel.HEALTHY;
		for (RiskReason reason : reasons) {
			level = max(level, reason.severity());
		}
		return new Risk(level, List.copyOf(reasons));
	}

	private static LecturerDashboardRiskLevel max(
			LecturerDashboardRiskLevel a, LecturerDashboardRiskLevel b) {
		return rank(a) >= rank(b) ? a : b;
	}

	private static int rank(LecturerDashboardRiskLevel level) {
		return switch (level) {
			case HEALTHY -> 0;
			case WARNING -> 1;
			case CRITICAL -> 2;
			case UNKNOWN -> 3;
		};
	}

	private static RiskReason reason(
			LecturerDashboardRiskReasonCode code,
			LecturerDashboardRiskLevel severity,
			double actual,
			double threshold,
			LecturerDashboardRiskUnit unit,
			List<UUID> affected) {
		return new RiskReason(code, severity, actual, threshold, unit, affected);
	}

	private static RiskReason reason(
			LecturerDashboardRiskReasonCode code,
			LecturerDashboardRiskLevel severity,
			Double actual,
			Double threshold,
			LecturerDashboardRiskUnit unit,
			List<UUID> affected) {
		return new RiskReason(code, severity, actual, threshold, unit, affected);
	}
}
