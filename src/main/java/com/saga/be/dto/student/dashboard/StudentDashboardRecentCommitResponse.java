package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Recent personal V23 commit preview.")
public record StudentDashboardRecentCommitResponse(
		String sha,
		String shortSha,
		String message,
		String repositoryName,
		@Schema(description = "Raw GitCommit.committedAt. Null when the provider timestamp is missing. Not createdAt.")
				LocalDateTime committedAt,
		List<String> linkedTaskKeys) {}
