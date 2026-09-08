package com.saga.be.dto.task;

import java.time.LocalDateTime;
import java.util.UUID;

public record TaskWebLinkResponse(
		UUID id,
		UUID taskId,
		String url,
		String title,
		String source,
		UUID createdByUserId,
		LocalDateTime createdAt) {}
