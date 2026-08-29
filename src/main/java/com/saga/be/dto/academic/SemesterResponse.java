package com.saga.be.dto.academic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record SemesterResponse(
		UUID id,
		String code,
		String name,
		LocalDate startDate,
		LocalDate endDate,
		boolean active,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {}
