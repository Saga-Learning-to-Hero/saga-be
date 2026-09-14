package com.saga.be.dto.project;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Per-sprint factual Task/Commit activity. Personal when scoped to a student (assigned tasks +
 * authored commits); project-wide when a lecturer omits {@code studentId}. Never a live Jira/GitHub
 * call and never a contribution/grade formula.
 */
@Schema(description = "Per-sprint task and commit activity for a project or one student.")
public record SprintActivityResponse(
		@Schema(example = "2026-09-14T10:00:00Z") Instant generatedAt, List<SprintActivity> sprints) {

	@Schema(description = "One projected sprint plus aggregated task/commit counts.")
	public record SprintActivity(
			UUID sprintId,
			String sprintName,
			@Schema(description = "Uppercased Jira sprint state (ACTIVE, CLOSED, FUTURE).", example = "ACTIVE")
					String state,
			LocalDate startDate,
			LocalDate endDate,
			TaskCounts tasks,
			CommitCounts commits) {}

	@Schema(description = "Counts by normalized TaskStatus. total includes BLOCKED (no separate field).")
	public record TaskCounts(long total, long todo, long inProgress, long inReview, long done) {}

	@Schema(
			description =
					"linked = distinct commits attached to a non-deleted task in this sprint. "
							+ "unlinked = commits in the sprint date window that are not linked to a task in this sprint.")
	public record CommitCounts(long linked, long unlinked) {}
}
