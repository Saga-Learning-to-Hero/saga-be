package com.saga.be.dto.ai;

import java.time.LocalDateTime;

/** Never carries the key or any part of it beyond the last-4 characters; never the ciphertext,
 * nonce, fingerprint, master key or transport key. {@code provider} is OPENAI/GEMINI/OPENROUTER/COHERE. */
public record CourseAiCredentialResponse(boolean configured, String provider, String role, String status, String lastFour, LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime lastSuccessfulUseAt) {}
