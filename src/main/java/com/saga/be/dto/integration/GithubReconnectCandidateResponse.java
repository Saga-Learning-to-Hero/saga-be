package com.saga.be.dto.integration;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Historical GitHub App installation candidate for project reconnect selection.")
public record GithubReconnectCandidateResponse(
		@Schema(description = "GitHub App installation id", example = "158866076") Long installationId,
		@Schema(description = "Provider account login when known", example = "Saga-Learning-to-Hero")
				String accountLogin,
		@Schema(description = "Provider account type when known", example = "Organization") String accountType) {}
