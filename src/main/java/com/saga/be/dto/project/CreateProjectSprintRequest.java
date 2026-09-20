package com.saga.be.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateProjectSprintRequest(
		@NotBlank @Size(max = 255) String name,
		@Size(max = 1000) String goal,
		String startDate,
		String endDate,
		/**
		 * Optional Jira source for create. Required when the project has more than one Jira
		 * integration; when omitted and exactly one source exists, that source is used.
		 */
		UUID jiraIntegrationId) {

	/** Legacy overload (no jiraIntegrationId) for existing callers/tests. */
	public CreateProjectSprintRequest(String name, String goal, String startDate, String endDate) {
		this(name, goal, startDate, endDate, null);
	}
}
