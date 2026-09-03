package com.saga.be.dto.integration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Select the Jira Cloud site, project, and optional board for this SAGA project.")
public record SelectJiraIntegrationRequest(
		@Schema(
				description = "Atlassian Cloud ID returned by GET /jira/sites",
				example = "aeb21465-f2da-4923-b356-f6f1cfa4fd13",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank
		String cloudId,
		@Schema(
				description = "Jira project ID returned by GET /jira/projects",
				example = "10067",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank
		String jiraProjectId,
		@Schema(
				description = "Optional Jira board ID returned by GET /jira/boards. JSON field is boardId.",
				example = "68")
		String boardId) {}
