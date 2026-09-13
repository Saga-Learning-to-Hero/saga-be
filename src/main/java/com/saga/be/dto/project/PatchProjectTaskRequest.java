package com.saga.be.dto.project;

import jakarta.validation.constraints.Size;
import java.util.List;

public record PatchProjectTaskRequest(
		@Size(max = 255) String summary,
		@Size(max = 10000) String description,
		String issueTypeId,
		String assigneeAccountId,
		Boolean clearAssignee,
		String priorityId,
		Integer storyPoints,
		String sprintExternalId,
		Boolean moveToBacklog,
		String transitionId,
		String targetStatusId,
		/**
		 * PATCH semantics: omitted (null) -> preserve Jira's current labels. Explicitly {@code []}
		 * -> clear all labels. A non-empty list -> replace with exactly that list.
		 */
		List<String> labels) {

	/** Legacy overload (no labels) for existing callers/tests -- equivalent to {@code labels=null} (preserve). */
	public PatchProjectTaskRequest(
			String summary,
			String description,
			String issueTypeId,
			String assigneeAccountId,
			Boolean clearAssignee,
			String priorityId,
			Integer storyPoints,
			String sprintExternalId,
			Boolean moveToBacklog,
			String transitionId,
			String targetStatusId) {
		this(
				summary, description, issueTypeId, assigneeAccountId, clearAssignee, priorityId, storyPoints,
				sprintExternalId, moveToBacklog, transitionId, targetStatusId, null);
	}
}
