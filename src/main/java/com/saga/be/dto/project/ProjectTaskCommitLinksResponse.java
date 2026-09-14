package com.saga.be.dto.project;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Canonical Task ↔ GitCommit links for a project, optionally filtered by SAGA {@code GitRepo.id}
 * and exact branch reachability from the latest successful FULL membership snapshot.
 */
public record ProjectTaskCommitLinksResponse(
		UUID projectId, Filter filter, List<Link> links, int page, int size, long total) {

	public static final String BRANCH_RESOLUTION_REACHABLE_AT_SYNC = "REACHABLE_AT_SYNC";

	public record Filter(
			UUID repoId,
			Long repositoryId,
			String repositoryFullName,
			String branchName,
			String branchResolution,
			/**
			 * {@link java.time.LocalDateTime} copied from {@code git_repo.branch_membership_synced_at}.
			 * Same JSON convention as {@code GitCommit.committedAt}, {@code createdAt}, and sync-job
			 * timestamps: ISO-8601 without a timezone suffix (not {@code Instant}, no {@code Z} unless
			 * that happens to be the local clock value). Null when the request is unfiltered or the
			 * repo has never completed a FULL membership snapshot.
			 */
			LocalDateTime resolvedAt) {}

	public record Link(
			UUID taskId,
			String taskKey,
			UUID commitId,
			String sha,
			String message,
			UUID repoId,
			Long repositoryId,
			String repositoryFullName,
			String headRef,
			List<String> branchNames,
			LocalDateTime linkedAt,
			String linkSource) {}
}
