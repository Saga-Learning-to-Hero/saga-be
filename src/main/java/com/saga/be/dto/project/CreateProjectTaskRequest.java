package com.saga.be.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectTaskRequest(
		@NotBlank @Size(max = 255) String summary,
		@Size(max = 10000) String description,
		String issueTypeId,
		String assigneeAccountId,
		String priorityId,
		Integer storyPoints,
		Long sprintId,
		String sprintExternalId) {

	/** Prefer sprintId; sprintExternalId kept for compatibility. */
	public String resolvedSprintId() {
		if (sprintId != null) {
			return String.valueOf(sprintId);
		}
		return sprintExternalId == null || sprintExternalId.isBlank() ? null : sprintExternalId.trim();
	}
}
