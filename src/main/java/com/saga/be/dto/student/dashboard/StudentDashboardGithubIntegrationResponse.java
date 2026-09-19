package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(
		description =
				"Local GitHub repos. connected is the live boolean (ACTIVE rows only). "
						+ "status is a descriptive aggregate: ACTIVE if any live repo exists; "
						+ "the shared non-ACTIVE IntegrationStatus name if every remaining row matches; "
						+ "MIXED if non-ACTIVE statuses differ; null if no repo rows. "
						+ "Do not infer connectivity from status != null.")
public record StudentDashboardGithubIntegrationResponse(
		boolean connected, long repositoryCount, String status, LocalDateTime lastSyncedAt) {}
