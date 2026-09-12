package com.saga.be.dto.team;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One lightweight summary row per Team in a Course, for a dashboard list of cards. Deliberately
 * omits per-team Jira/GitHub connection status: computing it per team here would require one
 * {@code ProjectIntegrationService.summary} call per team (N+1 across the course). Click into a
 * team to call {@code GET /api/projects/{projectId}/progress}, which includes full sync/connection
 * status for that one project.
 */
public record LecturerCourseProgressResponse(UUID courseId, List<Entry> teams) {

	public record Entry(
			UUID teamId,
			int teamNo,
			String teamName,
			UUID projectId,
			long totalTasks,
			long completedTasks,
			Double taskCompletionPercent,
			String currentSprintName,
			LocalDateTime lastActivityAt) {}
}
