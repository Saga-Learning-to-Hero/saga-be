package com.saga.be.dto.project;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProjectSprintResponse(
		UUID id,
		String externalSprintId,
		String name,
		String state,
		String goal,
		LocalDateTime startDate,
		LocalDateTime endDate,
		LocalDateTime completeDate) {}
