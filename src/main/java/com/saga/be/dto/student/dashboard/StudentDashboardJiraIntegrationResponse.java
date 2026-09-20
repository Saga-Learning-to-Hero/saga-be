package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(
		description =
				"Local Jira projection across all sources on the project. connected is true when any source is ACTIVE. "
						+ "status is ACTIVE if any ACTIVE; otherwise a truthful aggregate (single non-ACTIVE status or MIXED). "
						+ "projectKey is set only when exactly one jira_integration row exists (null when multi-source). "
						+ "lastSyncedAt is max lastSuccessfulSyncAt (prefer ACTIVE sources when any are live).")
public record StudentDashboardJiraIntegrationResponse(
		boolean connected, String projectKey, String status, LocalDateTime lastSyncedAt) {}
