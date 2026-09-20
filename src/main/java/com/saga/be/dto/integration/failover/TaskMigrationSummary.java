package com.saga.be.dto.integration.failover;

import java.util.UUID;

/**
 * Batch-loader summary for Task APIs (Phase 4A foundation). Not wired into every Task response yet.
 */
public record TaskMigrationSummary(
		MigrationRole role,
		UUID counterpartTaskId,
		String counterpartExternalKey,
		UUID counterpartJiraIntegrationId,
		UUID runId) {

	public enum MigrationRole {
		NONE,
		SOURCE_SUPERSEDED,
		TARGET
	}

	public static TaskMigrationSummary none() {
		return new TaskMigrationSummary(MigrationRole.NONE, null, null, null, null);
	}
}
