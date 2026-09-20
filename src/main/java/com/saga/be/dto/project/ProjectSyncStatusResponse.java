package com.saga.be.dto.project;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProjectSyncStatusResponse(
		UUID projectId,
		String provider,
		String status,
		LocalDateTime startedAt,
		LocalDateTime completedAt,
		Integer itemsProcessed,
		Integer itemsFailed,
		UUID jiraIntegrationId) {

	/** GitHub / legacy constructors without per-source id. */
	public ProjectSyncStatusResponse(
			UUID projectId,
			String provider,
			String status,
			LocalDateTime startedAt,
			LocalDateTime completedAt,
			Integer itemsProcessed,
			Integer itemsFailed) {
		this(projectId, provider, status, startedAt, completedAt, itemsProcessed, itemsFailed, null);
	}
}
