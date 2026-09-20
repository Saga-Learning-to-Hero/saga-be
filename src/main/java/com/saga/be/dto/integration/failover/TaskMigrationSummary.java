package com.saga.be.dto.integration.failover;

import java.util.UUID;

/**
 * Batch-loader summary for Task APIs (Phase 4A foundation). Not wired into every Task response yet.
 */
public record TaskMigrationSummary(MigrationLink migratedFrom, MigrationLink migratedTo, boolean superseded) {
	/** One-hop only: middle lineage nodes truthfully expose both directions without recursion. */
	public record MigrationLink(UUID taskId, String externalKey, UUID jiraIntegrationId, UUID runId) {}
	public static TaskMigrationSummary none() { return new TaskMigrationSummary(null, null, false); }
}
