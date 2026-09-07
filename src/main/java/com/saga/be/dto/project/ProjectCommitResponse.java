package com.saga.be.dto.project;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProjectCommitResponse(
		UUID id,
		UUID repoId,
		String repositoryFullName,
		String sha,
		String message,
		String authorExternalId,
		UUID authorStudentId,
		LocalDateTime committedAt,
		LocalDateTime createdAt) {}
