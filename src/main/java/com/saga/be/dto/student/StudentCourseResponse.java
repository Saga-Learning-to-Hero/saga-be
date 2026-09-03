package com.saga.be.dto.student;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "One ACTIVE course enrollment for the authenticated student.")
public record StudentCourseResponse(
		@Schema(description = "Course id used by subsequent student team/project APIs.") UUID courseId,
		@Schema(example = "SE1234") String courseCode,
		@Schema(example = "SWP391") String subjectCode,
		@Schema(example = "Software Development Project") String subjectName,
		@Schema(example = "SE18B01") String classCode,
		@Schema(example = "FA26") String semesterCode,
		@Schema(example = "Fall 2026") String semesterName,
		@Schema(description = "Always ACTIVE for this endpoint.", example = "ACTIVE") String enrollmentStatus,
		@Schema(description = "Null until the lecturer assigns the student to a team.") UUID teamId,
		@Schema(description = "Null until the student is assigned to a team.") Integer teamNo,
		@Schema(description = "Null until the student is assigned to a team.") String teamName,
		@Schema(description = "Null until the Team Leader creates the project.") UUID projectId) {}
