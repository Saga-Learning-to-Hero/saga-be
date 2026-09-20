package com.saga.be.dto.integration.failover;

import com.saga.be.entity.enums.JiraFailoverRunStatus;
import java.util.UUID;

public record JiraFailoverExecuteResponse(UUID runId, JiraFailoverRunStatus status, int itemCount) {}
