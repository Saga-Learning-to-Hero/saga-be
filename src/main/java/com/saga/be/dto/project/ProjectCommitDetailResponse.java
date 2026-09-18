package com.saga.be.dto.project;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ProjectCommitDetailResponse(
		UUID gitCommitId,
		UUID repoId,
		String repositoryFullName,
		String sha,
		String message,
		String authorName,
		String authorLogin,
		LocalDateTime committedAt,
		String htmlUrl,
		Stats stats,
		List<Parent> parents,
		int parentCount,
		Boolean isMerge,
		boolean filesTruncated,
		List<ChangedFile> files) {

	public record Stats(Integer total, Integer additions, Integer deletions) {}

	public record Parent(String sha) {}

	public record ChangedFile(
			String filename,
			String previousFilename,
			String status,
			Integer additions,
			Integer deletions,
			Integer changes,
			String patch) {}
}
