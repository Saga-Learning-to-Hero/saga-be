package com.saga.be.dto.project;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Drill-down detail for one Team member's factual progress in one project. Lightweight assigned
 * Task list only (id/key/title/status) — full Task detail remains behind the existing
 * {@code GET /api/projects/{projectId}/tasks/{taskId}} endpoint.
 */
public record ProjectMemberProgressResponse(
		UUID studentId,
		UUID userId,
		String fullName,
		String studentCode,
		String teamRole,
		ProjectProgressResponse.TaskAttribution taskSummary,
		List<AssignedTask> assignedTasks,
		ProjectProgressResponse.CommitAttribution commitSummary,
		EvidenceAttribution evidenceSummary) {

	public record AssignedTask(
			UUID id, String externalKey, String title, String status, LocalDateTime externalUpdatedAt) {}

	public record EvidenceAttribution(long workSessions, long files, long webLinks, long confirmations) {}
}
