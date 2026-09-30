package com.saga.be.dto.project;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ProjectSprintResponse(
		UUID id,
		String externalSprintId,
		String name,
		String state,
		String goal,
		LocalDateTime startDate,
		LocalDateTime endDate,
		LocalDateTime completeDate,
		Source source,
		@Schema(
						description =
								"Other sprints of the same project (any Jira site) whose dates overlap this one. "
										+ "Sprints must run one after another; never null, empty when there is no conflict.")
				List<OverlapRef> overlaps) {

	public ProjectSprintResponse {
		overlaps = overlaps == null ? List.of() : List.copyOf(overlaps);
	}

	public ProjectSprintResponse(
			UUID id,
			String externalSprintId,
			String name,
			String state,
			String goal,
			LocalDateTime startDate,
			LocalDateTime endDate,
			LocalDateTime completeDate,
			Source source) {
		this(id, externalSprintId, name, state, goal, startDate, endDate, completeDate, source, List.of());
	}

	public ProjectSprintResponse(
			UUID id,
			String externalSprintId,
			String name,
			String state,
			String goal,
			LocalDateTime startDate,
			LocalDateTime endDate,
			LocalDateTime completeDate) {
		this(id, externalSprintId, name, state, goal, startDate, endDate, completeDate, null);
	}

	public record Source(UUID jiraIntegrationId, String siteName, String projectKey, String boardId, String connectionStatus) {}

	public record OverlapRef(UUID sprintId, String name, String state, UUID jiraIntegrationId, String siteName) {}
}
