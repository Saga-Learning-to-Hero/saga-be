package com.saga.be.dto.student.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
		description =
				"Phase D1 actionable alert. Currently MSR_ANOMALY and PEER_REVIEW_PENDING only. "
						+ "GHOSTING_WARNING is not implemented.")
public record StudentDashboardAlertResponse(
		String id,
		String type,
		String severity,
		String title,
		String message,
		String actionType,
		StudentDashboardAlertTargetIds targetIds,
		@JsonInclude(JsonInclude.Include.NON_NULL)
				@Schema(description = "PEER_REVIEW_PENDING only: ACTIVE teammates minus self minus submitted.")
				Integer remainingPeers) {}
