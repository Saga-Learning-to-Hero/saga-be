package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Needs-attention personal task preview. Not every assigned task.")
public record StudentDashboardActiveTaskResponse(
		UUID id,
		String externalKey,
		String title,
		String status,
		String priority,
		Integer storyPoints,
		LocalDateTime dueDate,
		@Schema(description = "Raw task_git_commit_link cardinality, including known merges.") long linkedCommitCount,
		@Schema(description = "Links to V23-included commits (parentCount null/0/1).") long evidenceCommitCount,
		boolean hasAnomaly) {}
