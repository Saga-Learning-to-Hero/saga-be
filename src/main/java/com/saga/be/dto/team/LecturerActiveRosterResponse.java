package com.saga.be.dto.team;

import java.util.List;
import java.util.UUID;

public record LecturerActiveRosterResponse(
		UUID courseId,
		String classCode,
		int enrolledCount,
		List<LecturerActiveRosterEntryResponse> entries) {}
