package com.saga.be.dto.integration.failover;

import com.saga.be.entity.enums.JiraFailoverItemStatus;
import com.saga.be.entity.enums.JiraFailoverRunStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record JiraFailoverRunResponse(
		UUID id,
		UUID sourceIntegrationId,
		UUID targetIntegrationId,
		JiraFailoverRunStatus status,
		Map<JiraFailoverItemStatus, Long> itemCounts,
		LocalDateTime createdAt,
		LocalDateTime startedAt,
		LocalDateTime completedAt,
		List<Item> items,
		int page,
		int size,
		int totalItems,
		int totalPages,
		boolean hasNext) {

	public record Item(
			UUID id,
			UUID sourceTaskId,
			String sourceExternalKey,
			String sourceTitle,
			JiraFailoverItemStatus status,
			UUID targetTaskId,
			String targetExternalKey,
			String remoteIssueId,
			String remoteIssueKey,
			String errorCode,
			boolean reconciliationRequired) {}
}
