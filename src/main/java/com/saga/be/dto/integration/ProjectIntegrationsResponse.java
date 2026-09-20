package com.saga.be.dto.integration;

import java.util.List;
import java.util.UUID;

/**
 * Project integration summary. {@code jira} remains a legacy singular field: populated only when
 * exactly one Jira source exists. When multiple sources exist, {@code jira} is null and clients
 * must use {@code jiraSources}. Never arbitrarily picks among multiple sources.
 */
public record ProjectIntegrationsResponse(
		GithubIntegrationSummary github, JiraIntegrationSummary jira, List<JiraSourceSummary> jiraSources) {

	/** Backward-compatible constructor for one-source / no-source callers (empty jiraSources). */
	public ProjectIntegrationsResponse(GithubIntegrationSummary github, JiraIntegrationSummary jira) {
		this(github, jira, jira == null ? List.of() : List.of());
	}

	public record GithubIntegrationSummary(
			Long installationId, String accountLogin, String status, List<ConnectedRepo> repositories) {}

	public record ConnectedRepo(UUID id, Long repositoryId, String fullName, String role, String status) {}

	public record JiraIntegrationSummary(
			String cloudId, String siteName, String projectKey, String boardId, String status) {}

	public record JiraProjectOption(String id, String key, String name) {}

	public record JiraBoardOption(String id, String name, String type) {}
}
