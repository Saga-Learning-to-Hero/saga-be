package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Authenticated student identity for the course dashboard.")
public record StudentDashboardStudentResponse(
		UUID studentId,
		UUID userId,
		String studentCode,
		String fullName,
		String avatarUrl,
		@Schema(description = "LEADER, MEMBER, MENTOR, or null when not assigned to a team.") String teamRole) {}
