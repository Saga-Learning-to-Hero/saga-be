package com.saga.be.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateProjectTaskRequest(
		@NotBlank @Size(max = 255) String summary,
		@Size(max = 10000) String description,
		String issueTypeId,
		String assigneeAccountId,
		String priorityId,
		Integer storyPoints,
		Long sprintId,
		String sprintExternalId,
		/** Null = no labels set on create (Jira's own default). Non-null (including empty) is sent as-is. */
		List<String> labels) {

	/** Legacy overload (no labels) for existing callers/tests -- equivalent to {@code labels=null}. */
	public CreateProjectTaskRequest(
			String summary,
			String description,
			String issueTypeId,
			String assigneeAccountId,
			String priorityId,
			Integer storyPoints,
			Long sprintId,
			String sprintExternalId) {
		this(summary, description, issueTypeId, assigneeAccountId, priorityId, storyPoints, sprintId, sprintExternalId, null);
	}

	/** Prefer sprintId; sprintExternalId kept for compatibility. */
	public String resolvedSprintId() {
		if (sprintId != null) {
			return String.valueOf(sprintId);
		}
		return sprintExternalId == null || sprintExternalId.isBlank() ? null : sprintExternalId.trim();
	}
}
