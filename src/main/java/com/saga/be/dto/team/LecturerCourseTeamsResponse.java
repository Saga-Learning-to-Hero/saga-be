package com.saga.be.dto.team;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record LecturerCourseTeamsResponse(
		UUID courseId,
		List<LecturerTeamResponse> teams,
		@Schema(description = "ACTIVE-enrolled students not on any team, sorted by student code. Never null.")
				List<LecturerUnassignedStudentResponse> unassignedStudents) {}
