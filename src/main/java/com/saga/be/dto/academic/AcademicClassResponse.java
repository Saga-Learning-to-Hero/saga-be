package com.saga.be.dto.academic;

import java.time.LocalDateTime;
import java.util.UUID;

public record AcademicClassResponse(
		UUID id,
		UUID semesterId,
		String semesterCode,
		String classCode,
		String name,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {}
