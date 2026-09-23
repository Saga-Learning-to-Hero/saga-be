package com.saga.be.dto.ai;

import java.time.LocalDateTime;

/** Never carries the key or any part of it beyond the last-4 digits. */
public record CourseAiCredentialResponse(boolean configured, String provider, String role, String status, String lastFour, LocalDateTime updatedAt, LocalDateTime lastSuccessfulUseAt) {}
