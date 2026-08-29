package com.saga.be.dto.roster;

import java.util.UUID;

public record CourseRosterEntryResponse(
		String kind,
		UUID enrollmentId,
		UUID invitationId,
		UUID studentUserId,
		String studentCode,
		String fullName,
		String email,
		String enrollmentStatus,
		String invitationStatus,
		String accountState) {}
