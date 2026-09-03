package com.saga.be.dto.team;

import java.util.UUID;

public record LecturerTeamMemberResponse(
		UUID courseEnrollmentId,
		UUID studentProfileId,
		String studentCode,
		String fullName,
		String email,
		String role) {}
