package com.saga.be.dto.team;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssignTeamMemberRequest(
		@Schema(description = "ACTIVE CourseEnrollment id of the student (from unassignedStudents[] or a team's members[]).")
				@NotNull
				UUID courseEnrollmentId) {}
