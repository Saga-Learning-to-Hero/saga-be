package com.saga.be.dto.integration.failover;

import jakarta.validation.constraints.NotBlank;

public record JiraFailoverReconcileRequest(@NotBlank String remoteIssueIdOrKey) {}
