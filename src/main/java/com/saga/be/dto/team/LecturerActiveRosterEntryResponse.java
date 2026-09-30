package com.saga.be.dto.team;

import java.util.UUID;

public record LecturerActiveRosterEntryResponse(
		UUID courseEnrollmentId,
		UUID studentProfileId,
		String studentCode,
		String fullName,
		String avatarUrl,
		String email,
		String classCode) {}
