package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Entity ids for FE routing. Backend does not return frontend URLs.")
public record StudentDashboardAlertTargetIds(
		UUID courseId,
		UUID teamId,
		UUID projectId,
		@Schema(description = "Present on MSR_ANOMALY only.") UUID taskId,
		@Schema(description = "Present on PEER_REVIEW_PENDING only.") UUID sprintId) {}
