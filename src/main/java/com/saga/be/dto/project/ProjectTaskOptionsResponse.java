package com.saga.be.dto.project;

import java.util.List;

public record ProjectTaskOptionsResponse(
		List<IssueTypeOption> issueTypes,
		List<PriorityOption> priorities,
		List<AssignableUserOption> assignableUsers,
		EstimationOption estimation,
		List<SprintOption> sprints) {

	public record IssueTypeOption(String id, String name, String description) {}

	public record PriorityOption(String id, String name) {}

	public record AssignableUserOption(String accountId, String displayName) {}

	public record EstimationOption(boolean supported, String fieldId, String fieldName) {}

	public record SprintOption(String id, String name, String state) {}
}
