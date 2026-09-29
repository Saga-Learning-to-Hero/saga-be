package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(
		description =
				"One bucket of personal V23 commits: a single day inside the selected sprint (startDate == "
						+ "endDate) or, with no sprint, one ISO calendar week. committedAt only; no createdAt fallback. "
						+ "Provider offsets were stripped before persistence, so buckets follow stored wall-clock values.")
public record StudentDashboardWeeklyCommitResponse(
		LocalDate startDate, LocalDate endDate, long commits) {}
