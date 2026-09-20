package com.saga.be.dto.integration;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Non-secret Jira source summary for project-scoped listing. Does not include tokens, webhook
 * secrets, or encrypted credential material.
 */
public record JiraSourceSummary(
		UUID integrationId,
		String cloudId,
		String siteName,
		String jiraProjectId,
		String projectKey,
		String boardId,
		String connectionStatus,
		LocalDateTime lastSuccessfulSyncAt,
		LocalDateTime lastSyncedAt,
		Integer consecutiveFailures,
		String lastErrorCode) {}
