package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Personal V23-included authored commits on the team project.")
public record StudentDashboardCommitMetricsResponse(
		long totalCommits,
		long linkedCommits,
		long unlinkedCommits,
		@Schema(description = "linkedCommits / totalCommits * 100, or null when totalCommits is 0.")
				Double traceabilityPercent,
		@Schema(description = "MAX(COALESCE(committedAt, createdAt)) over authored V23 commits. Not the raw provider field.")
				LocalDateTime lastCommittedAt) {}
