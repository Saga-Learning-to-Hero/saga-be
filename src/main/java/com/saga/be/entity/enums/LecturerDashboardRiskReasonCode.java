package com.saga.be.entity.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Machine-readable reason for a lecturer dashboard risk.")
public enum LecturerDashboardRiskReasonCode {
	NO_PROJECT,
	NO_ACTIVE_SPRINT,
	INACTIVE,
	SCHEDULE_LAG,
	BLOCKED_TASKS,
	OVERDUE_TASKS,
	MISSING_TASK_COMMIT_LINK,
	JIRA_SYNC_FAILED,
	GITHUB_SYNC_FAILED,
	CONTRIBUTION_CONFIG_MISSING,
	PEER_REVIEW_INCOMPLETE,
	DATA_UNAVAILABLE
}

