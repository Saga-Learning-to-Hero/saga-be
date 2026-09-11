package com.saga.be.dto.project;

import jakarta.validation.constraints.Size;

public record PatchProjectTaskRequest(
		@Size(max = 255) String summary,
		@Size(max = 10000) String description,
		String issueTypeId,
		String assigneeAccountId,
		Boolean clearAssignee,
		String priorityId,
		Integer storyPoints,
		String sprintExternalId,
		Boolean moveToBacklog,
		String transitionId,
		String targetStatusId) {}
