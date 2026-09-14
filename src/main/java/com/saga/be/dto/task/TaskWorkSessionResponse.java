package com.saga.be.dto.task;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One work session. {@code elapsedSeconds} is derived at read time from {@code startedAt} and
 * either {@code endedAt} (STOPPED) or the server clock (OPEN) -- never a stored ticking value.
 */
public record TaskWorkSessionResponse(
		UUID id,
		UUID taskId,
		LocalDateTime startedAt,
		LocalDateTime endedAt,
		String status,
		long elapsedSeconds) {}
