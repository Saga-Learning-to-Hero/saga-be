package com.saga.be.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A failed AI analysis explained for people: show {@code title} and {@code message}, then
 * {@code hint}; offer "Thử lại" only when {@code retryable}. {@code code} stays for support.
 */
public record AiFailureResponse(
		@Schema(description = "Safe failure code, e.g. AI_PROVIDER_TIMEOUT") String code,
		@Schema(description = "Short Vietnamese title") String title,
		@Schema(description = "What happened, in Vietnamese") String message,
		@Schema(description = "What to do next, in Vietnamese") String hint,
		@Schema(description = "Whether simply retrying can help") boolean retryable) {}
