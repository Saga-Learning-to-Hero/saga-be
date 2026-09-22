package com.saga.be.entity.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Unit of the actual/threshold values on a lecturer dashboard risk reason.")
public enum LecturerDashboardRiskUnit {
	DAY,
	PERCENTAGE_POINT,
	TASK,
	REVIEW,
	COUNT
}

