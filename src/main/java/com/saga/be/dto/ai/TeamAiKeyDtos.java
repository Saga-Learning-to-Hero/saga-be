package com.saga.be.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/** The team's own AI key for commit reviews (leader-managed). The key itself is never returned. */
public final class TeamAiKeyDtos {

	private TeamAiKeyDtos() {}

	public record SaveRequest(
			@Schema(description = "GEMINI | OPENAI | COHERE") String provider,
			@Schema(description = "A model id from status.models of that provider") String modelId,
			@Schema(description = "The provider API key; stored encrypted, never returned") String apiKey) {}

	public record ModelOption(String provider, String modelId, String displayName, boolean freeTierEligible, boolean recommended) {}

	public record Status(
			@Schema(description = "A (not removed) team key exists") boolean configured,
			String provider,
			String modelId,
			@Schema(description = "UNVERIFIED (never used yet) | ACTIVE | DEGRADED (quota/rate limit) | INVALID (key rejected)") String status,
			@Schema(description = "Last 4 characters of the key, leader only") String lastFour,
			String lastErrorCode,
			@Schema(description = "Readable explanation of lastErrorCode") AiFailureResponse lastError,
			LocalDateTime lastSuccessfulUseAt,
			LocalDateTime updatedAt,
			@Schema(description = "The caller is the team leader and may save or remove the key") boolean canManage,
			@Schema(description = "Without a team key, commits are still reviewed with the course key (lecturer turned on course AI automation)") boolean courseFallbackAvailable,
			@Schema(description = "Providers/models the leader may pick (OpenRouter is not offered)") List<ModelOption> models) {}
}
