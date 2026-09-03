package com.saga.be.dto.project;

import java.time.LocalDateTime;
import java.util.UUID;

public record StudentProjectResponse(
		UUID projectId,
		UUID courseId,
		UUID teamId,
		int teamNo,
		String teamName,
		String name,
		String description,
		ProjectTypeSummary projectType,
		CreatedBy createdBy,
		LocalDateTime createdAt) {

	public record ProjectTypeSummary(UUID id, String code, String name) {}

	public record CreatedBy(UUID userId, String fullName) {}
}
