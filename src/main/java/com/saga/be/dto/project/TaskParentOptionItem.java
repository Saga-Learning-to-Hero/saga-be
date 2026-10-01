package com.saga.be.dto.project;

import java.util.UUID;

/**
 * {@code parentTaskId} is the legacy native parent (null on Jira-parent options). {@code
 * issueTypeName}/{@code issueTypeLevel} are set on Jira-parent options (with childLevel).
 */
public record TaskParentOptionItem(
		UUID id,
		String title,
		String status,
		UUID parentTaskId,
		String externalKey,
		String issueTypeName,
		String issueTypeLevel) {}
