package com.saga.be.dto.integration;

import com.saga.be.entity.enums.RepositoryRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Select a GitHub repository that belongs to the project's verified installation.")
public record SelectGitHubRepositoryRequest(
		@Schema(
				description = "GitHub repository ID returned by GET /github/repositories",
				example = "1338790015",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull
		Long repositoryId,
		@Schema(description = "Repository role inside the SAGA project", example = "FRONTEND")
		RepositoryRole role) {}
