package com.saga.be.dto.integration;

import java.util.List;
import java.util.UUID;

public record ProjectIntegrationsResponse(GithubIntegrationSummary github, JiraIntegrationSummary jira) {

	public record GithubIntegrationSummary(
			Long installationId, String accountLogin, String status, List<ConnectedRepo> repositories) {}

	public record ConnectedRepo(UUID id, Long repositoryId, String fullName, String role, String status) {}

	public record JiraIntegrationSummary(
			String cloudId, String siteName, String projectKey, String boardId, String status) {}

	public record JiraProjectOption(String id, String key, String name) {}

	public record JiraBoardOption(String id, String name, String type) {}
}
