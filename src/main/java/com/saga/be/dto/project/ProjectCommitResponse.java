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
		String authorAvatarUrl,
		String headRef,
		LocalDateTime committedAt,
		LocalDateTime createdAt,
		Integer parentCount,
		Boolean isMerge,
		@io.swagger.v3.oas.annotations.media.Schema(description = "AI review badge (PASS / WARNING / ...); null when not loaded")
		com.saga.be.dto.ai.CommitAiReviewDtos.Summary aiReview) {

	/** Without the AI review badge (callers that do not show it). */
	public ProjectCommitResponse(
			UUID id, UUID repoId, String repositoryFullName, String sha, String message, String authorExternalId,
			UUID authorStudentId, String authorAvatarUrl, String headRef, LocalDateTime committedAt, LocalDateTime createdAt,
			Integer parentCount, Boolean isMerge) {
		this(id, repoId, repositoryFullName, sha, message, authorExternalId, authorStudentId, authorAvatarUrl, headRef,
				committedAt, createdAt, parentCount, isMerge, null);
	}

	public ProjectCommitResponse withAiReview(com.saga.be.dto.ai.CommitAiReviewDtos.Summary review) {
		return new ProjectCommitResponse(id, repoId, repositoryFullName, sha, message, authorExternalId, authorStudentId,
				authorAvatarUrl, headRef, committedAt, createdAt, parentCount, isMerge, review);
	}
}
