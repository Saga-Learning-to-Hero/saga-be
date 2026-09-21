package com.saga.be.entity.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Lecturer course dashboard aggregation window. Only CURRENT_SPRINT is supported.")
public enum DashboardScope {
	CURRENT_SPRINT
}

