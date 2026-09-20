package com.saga.be.dto.project;

import java.util.UUID;

/**
 * Compact non-secret Jira source provenance embedded on Task reads. Never includes tokens or
 * encrypted fields. Tasks from REVOKED sources remain visible with connectionStatus=REVOKED.
 */
public record TaskJiraSourceSummary(
		UUID integrationId, String siteName, String projectKey, String connectionStatus) {}
