package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Local Jira projection. connected is true only when connectionStatus is ACTIVE.")
public record StudentDashboardJiraIntegrationResponse(
		boolean connected, String projectKey, String status, LocalDateTime lastSyncedAt) {}
