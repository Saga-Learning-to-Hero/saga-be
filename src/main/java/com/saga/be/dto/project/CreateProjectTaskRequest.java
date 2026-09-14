package com.saga.be.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record CreateProjectTaskRequest(
		@NotBlank @Size(max = 255) String summary,
		@Size(max = 10000) String description,
		String issueTypeId,
		String assigneeAccountId,
		String priorityId,
		Integer storyPoints,
		Long sprintId,
		String sprintExternalId,
		/** Null = no labels set on create (Jira's own default). Non-null (including empty) is sent as-is. */
		List<String> labels,
		/**
		 * Jira's standard {@code duedate} field. Null = no due date set on create (Jira's own
		 * default). Never confused with a row-modification timestamp -- this is a business deadline.
		 */
		LocalDate dueDate,
		/**
		 * Jira's Start Date custom field (dynamically resolved per site -- see {@link
		 * com.saga.be.integration.jira.JiraIssueWriteClient#resolveStartDateFieldId}). Null = no
		 * start date set on create (Jira's own default).
		 */
		LocalDate startDate) {

	/** Legacy overload (no labels/dueDate/startDate) for existing callers/tests. */
	public CreateProjectTaskRequest(
			String summary,
			String description,
			String issueTypeId,
			String assigneeAccountId,
			String priorityId,
			Integer storyPoints,
			Long sprintId,
			String sprintExternalId) {
		this(
				summary, description, issueTypeId, assigneeAccountId, priorityId, storyPoints, sprintId,
				sprintExternalId, null, null, null);
	}

	/** Legacy overload (no dueDate/startDate) for existing callers/tests. */
	public CreateProjectTaskRequest(
			String summary,
			String description,
			String issueTypeId,
			String assigneeAccountId,
			String priorityId,
			Integer storyPoints,
			Long sprintId,
			String sprintExternalId,
			List<String> labels) {
		this(
				summary, description, issueTypeId, assigneeAccountId, priorityId, storyPoints, sprintId,
				sprintExternalId, labels, null, null);
	}

	/** Legacy overload (no startDate) for existing callers/tests -- equivalent to {@code startDate=null}. */
	public CreateProjectTaskRequest(
			String summary,
			String description,
			String issueTypeId,
			String assigneeAccountId,
			String priorityId,
			Integer storyPoints,
			Long sprintId,
			String sprintExternalId,
			List<String> labels,
			LocalDate dueDate) {
		this(
				summary, description, issueTypeId, assigneeAccountId, priorityId, storyPoints, sprintId,
				sprintExternalId, labels, dueDate, null);
	}

	/** Prefer sprintId; sprintExternalId kept for compatibility. */
	public String resolvedSprintId() {
		if (sprintId != null) {
			return String.valueOf(sprintId);
		}
		return sprintExternalId == null || sprintExternalId.isBlank() ? null : sprintExternalId.trim();
	}
}
