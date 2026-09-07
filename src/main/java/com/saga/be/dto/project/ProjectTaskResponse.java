package com.saga.be.dto.project;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProjectTaskResponse(
		UUID id,
		String externalId,
		String externalKey,
		String title,
		String status,
		String issueTypeName,
		String assigneeExternalId,
		UUID assigneeStudentId,
		long linkedCommitCount,
		LocalDateTime externalUpdatedAt,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {}
