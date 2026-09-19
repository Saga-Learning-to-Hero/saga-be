package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Student's team in the course. projectId/projectName are null until a project exists.")
public record StudentDashboardTeamResponse(
		UUID teamId,
		int teamNo,
		String teamName,
		UUID projectId,
		String projectName,
		@Schema(description = "ACTIVE-enrollment TeamMember rows only.") long membersCount) {}
