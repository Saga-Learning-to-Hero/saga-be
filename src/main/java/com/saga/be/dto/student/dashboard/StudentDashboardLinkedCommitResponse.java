package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "One commit linked to a dashboard task (task_git_commit_link), any author, merges included.")
public record StudentDashboardLinkedCommitResponse(
		@Schema(description = "SAGA gitCommitId; GET /api/projects/{projectId}/commits/{gitCommitId} for detail.") UUID id,
		String sha,
		String message,
		String repositoryFullName,
		LocalDateTime committedAt,
		@Schema(description = "Mapped team member (studentProfileId), or null for an unlinked GitHub account.")
				UUID authorStudentId,
		String authorExternalId,
		@Schema(description = "true for a known merge commit (parentCount > 1); null when parentCount is unknown.")
				Boolean isMerge) {}
