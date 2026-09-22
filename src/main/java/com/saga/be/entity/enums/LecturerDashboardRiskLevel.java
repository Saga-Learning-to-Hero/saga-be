package com.saga.be.entity.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Overall lecturer dashboard risk for a team card.")
public enum LecturerDashboardRiskLevel {
	HEALTHY,
	WARNING,
	CRITICAL,
	UNKNOWN
}

