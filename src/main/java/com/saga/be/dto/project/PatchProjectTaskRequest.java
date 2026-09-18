package com.saga.be.dto.project;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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
		List<String> labels,
		/**
		 * PATCH semantics (mirrors {@code assigneeAccountId}/{@code clearAssignee}): omitted (null,
		 * with {@code clearDueDate} not true) -> preserve Jira's current due date. A value -> set to
		 * exactly that date. {@code clearDueDate=true} -> explicit clear, the only way to null it out
		 * (a bare {@code dueDate=null} is never treated as an accidental clear).
		 */
		LocalDate dueDate,
		Boolean clearDueDate,
		/**
		 * PATCH semantics (mirrors {@code dueDate}/{@code clearDueDate}): omitted (null, with {@code
		 * clearStartDate} not true) -> preserve Jira's current Start Date. A value -> set to exactly
		 * that date via the dynamically-resolved Start Date field. {@code clearStartDate=true} ->
		 * explicit clear (a bare {@code startDate=null} is never treated as an accidental clear).
		 */
		LocalDate startDate,
		Boolean clearStartDate,
		/**
		 * PATCH semantics: omitted (null, with {@code clearParent} not true) -> preserve native
		 * parent. A value -> assign/change native parent. Never written to Jira {@code fields.parent}.
		 */
		java.util.UUID parentTaskId,
		/**
		 * Explicit clear of native parent. A bare {@code parentTaskId=null} is never treated as an
		 * accidental clear. Cannot be combined with a non-null {@code parentTaskId}.
		 */
		Boolean clearParent) {

	/** Legacy overload (no labels/dueDate/startDate) for existing callers/tests -- preserves all. */
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
				sprintExternalId, moveToBacklog, transitionId, targetStatusId, null, null, null, null, null, null,
				null);
	}

	/** Legacy overload (no dueDate/startDate) for existing callers/tests -- preserves both. */
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
			String targetStatusId,
			List<String> labels) {
		this(
				summary, description, issueTypeId, assigneeAccountId, clearAssignee, priorityId, storyPoints,
				sprintExternalId, moveToBacklog, transitionId, targetStatusId, labels, null, null, null, null, null,
				null);
	}

	/** Legacy overload (no startDate) for existing callers/tests -- preserves the current start date. */
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
			String targetStatusId,
			List<String> labels,
			LocalDate dueDate,
			Boolean clearDueDate) {
		this(
				summary, description, issueTypeId, assigneeAccountId, clearAssignee, priorityId, storyPoints,
				sprintExternalId, moveToBacklog, transitionId, targetStatusId, labels, dueDate, clearDueDate, null,
				null, null, null);
	}

	/** Legacy overload (no native parent) for existing callers/tests -- preserves native parent. */
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
			String targetStatusId,
			List<String> labels,
			LocalDate dueDate,
			Boolean clearDueDate,
			LocalDate startDate,
			Boolean clearStartDate) {
		this(
				summary, description, issueTypeId, assigneeAccountId, clearAssignee, priorityId, storyPoints,
				sprintExternalId, moveToBacklog, transitionId, targetStatusId, labels, dueDate, clearDueDate,
				startDate, clearStartDate, null, null);
	}

	public boolean touchesNativeParent() {
		return parentTaskId != null || Boolean.TRUE.equals(clearParent);
	}

	public boolean touchesProviderFields() {
		return summary != null
				|| description != null
				|| (issueTypeId != null && !issueTypeId.isBlank())
				|| assigneeAccountId != null
				|| Boolean.TRUE.equals(clearAssignee)
				|| (priorityId != null && !priorityId.isBlank())
				|| storyPoints != null
				|| (sprintExternalId != null && !sprintExternalId.isBlank())
				|| Boolean.TRUE.equals(moveToBacklog)
				|| (transitionId != null && !transitionId.isBlank())
				|| (targetStatusId != null && !targetStatusId.isBlank())
				|| labels != null
				|| dueDate != null
				|| Boolean.TRUE.equals(clearDueDate)
				|| startDate != null
				|| Boolean.TRUE.equals(clearStartDate);
	}
}
