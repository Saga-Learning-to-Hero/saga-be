package com.saga.be.dto.integration.failover;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Explicit source-task selection; preview pages are never an execution authorization. */
public record JiraFailoverExecuteRequest(
		@NotNull UUID targetIntegrationId,
		UUID targetSprintId,
		String defaultIssueTypeId,
		boolean revokeSource,
		@NotEmpty List<@NotNull UUID> sourceTaskIds) {}
