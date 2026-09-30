package com.saga.be.dto.team;

import java.util.UUID;

/** An ACTIVE-enrolled student of the course who is not on any team yet. */
public record LecturerUnassignedStudentResponse(
		UUID courseEnrollmentId, UUID studentProfileId, String studentCode, String fullName, String email) {}
