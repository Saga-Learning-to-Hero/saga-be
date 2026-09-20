package com.saga.be.dto.integration.failover;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Failover preview request. Pagination is required for complete candidate inspection —
 * a single page is never an execution snapshot for Phase 4B.
 */
public record JiraFailoverPreviewRequest(
		@NotNull UUID targetIntegrationId,
		UUID targetSprintId,
		String defaultIssueTypeId,
		@Min(0) Integer page,
		@Min(1) @Max(JiraFailoverPreviewResponse.ITEM_PAGE_MAX) Integer size) {

	public int resolvedPage() {
		return page == null ? 0 : page;
	}

	public int resolvedSize() {
		if (size == null) {
			return JiraFailoverPreviewResponse.ITEM_PAGE_DEFAULT;
		}
		return Math.min(size, JiraFailoverPreviewResponse.ITEM_PAGE_MAX);
	}
}
