package com.saga.be.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
		LocalDate startDate,
		/** Optional native SAGA parent. Null = no native parent. Never written to Jira fields.parent. */
		UUID parentTaskId,
		/**
		 * Optional Jira source for create. Required when the project has more than one Jira
		 * integration; when omitted and exactly one source exists, that source is used.
		 */
		UUID jiraIntegrationId) {

	/** Legacy overload (no labels/dueDate/startDate/parent/source) for existing callers/tests. */
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
				sprintExternalId, null, null, null, null, null);
	}

	/** Legacy overload (no dueDate/startDate/parent/source) for existing callers/tests. */
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
				sprintExternalId, labels, null, null, null, null);
	}

	/** Legacy overload (no startDate/parent/source) for existing callers/tests -- equivalent to {@code startDate=null}. */
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
				sprintExternalId, labels, dueDate, null, null, null);
	}

	/** Legacy overload (no native parent/source) for existing callers/tests. */
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
			LocalDate dueDate,
			LocalDate startDate) {
		this(
				summary, description, issueTypeId, assigneeAccountId, priorityId, storyPoints, sprintId,
				sprintExternalId, labels, dueDate, startDate, null, null);
	}

	/** Legacy overload (no jiraIntegrationId) for existing callers/tests. */
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
			LocalDate dueDate,
			LocalDate startDate,
			UUID parentTaskId) {
		this(
				summary, description, issueTypeId, assigneeAccountId, priorityId, storyPoints, sprintId,
				sprintExternalId, labels, dueDate, startDate, parentTaskId, null);
	}

	/** Prefer sprintId; sprintExternalId kept for compatibility. */
	public String resolvedSprintId() {
		if (sprintId != null) {
			return String.valueOf(sprintId);
		}
		return sprintExternalId == null || sprintExternalId.isBlank() ? null : sprintExternalId.trim();
	}
}
