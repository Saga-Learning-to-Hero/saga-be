package com.saga.be.dto.student.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
		description =
				"Phase D1+D2 actionable alert. MSR_ANOMALY, GHOSTING_WARNING, then PEER_REVIEW_PENDING. "
						+ "GHOSTING_WARNING is commit-only: no attributable V23 committedAt in the last 5 calendar days.")
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
