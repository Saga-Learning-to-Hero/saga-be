package com.saga.be.dto.project;

import java.util.List;

public record ProjectTaskOptionsResponse(
		List<IssueTypeOption> issueTypes,
		List<PriorityOption> priorities,
		List<AssignableUserOption> assignableUsers,
		EstimationOption estimation,
		List<SprintOption> sprints,
		/** The only labels a task may be given: the four SAGA contribution markers, at most one per task. */
		List<String> labels) {

	/**
	 * {@code level}: "SUBTASK", "STANDARD" (Task/Story/Feature/Bug...), "EPIC" or "ABOVE_EPIC".
	 * {@code hierarchyLevel} is Jira's raw value (-1 / 0 / 1 / 2+), null when Jira omits it.
	 * An edit may only switch between two STANDARD types; a SUBTASK is created under a STANDARD
	 * parent task.
	 */
	public record IssueTypeOption(
			String id, String name, String description, boolean subtask, Integer hierarchyLevel, String level) {}

	public record PriorityOption(String id, String name) {}

	public record AssignableUserOption(String accountId, String displayName) {}

	public record EstimationOption(boolean supported, String fieldId, String fieldName) {}

	public record SprintOption(String id, String name, String state) {}
}
