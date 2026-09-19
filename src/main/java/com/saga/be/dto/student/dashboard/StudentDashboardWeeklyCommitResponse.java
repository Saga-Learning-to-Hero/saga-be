package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(
		description =
				"One ISO calendar week of personal V23 commits. committedAt only; no createdAt fallback. "
						+ "Provider offsets were stripped before persistence, so buckets follow stored wall-clock values.")
public record StudentDashboardWeeklyCommitResponse(
		LocalDate startDate, LocalDate endDate, long commits) {}
