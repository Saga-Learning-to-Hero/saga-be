package com.saga.be.dto.project;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Task-scoped work-session presence + V23 linked coding commits. Two independent evidence streams
 * for visual context only — no session↔commit causality, no contribution score.
 */
public record TaskWorkSessionTimelineResponse(
		TaskSummary task, WorkSessionsBlock workSessions, CommitsBlock commits) {

	public record TaskSummary(UUID id, String externalKey, String title) {}

	public record WorkSessionsBlock(
			long sessionCount,
			long totalElapsedSeconds,
			List<SessionItem> openSessions,
			int page,
			int size,
			long totalElements,
			int totalPages,
			List<SessionItem> sessions) {}

	public record SessionItem(
			UUID id,
			UUID userId,
			UUID studentId,
			String studentCode,
			String fullName,
			String avatarUrl,
			LocalDateTime startedAt,
			LocalDateTime endedAt,
			String status,
			long elapsedSeconds) {}

	public record CommitsBlock(int page, int size, long totalElements, int totalPages, List<CommitItem> items) {}

	/**
	 * Linked V23 coding commit. {@code committedAt} is the raw persisted field (nullable); never
	 * coalesced with {@code createdAt}. {@code linkedAt} is {@code TaskGitCommitLink.createdAt}.
	 */
	public record CommitItem(
			UUID id,
			String sha,
			String message,
			String repositoryFullName,
			UUID authorStudentId,
			LocalDateTime committedAt,
			LocalDateTime linkedAt,
			String linkSource) {}
}
