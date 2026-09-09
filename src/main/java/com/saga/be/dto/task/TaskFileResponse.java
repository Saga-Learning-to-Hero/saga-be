package com.saga.be.dto.task;

import java.time.LocalDateTime;
import java.util.UUID;

public record TaskFileResponse(
		UUID id,
		UUID taskId,
		String filename,
		String mimeType,
		long sizeBytes,
		String source,
		UUID createdByUserId,
		LocalDateTime createdAt) {}
