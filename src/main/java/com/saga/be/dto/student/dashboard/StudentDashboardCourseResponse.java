package com.saga.be.dto.student.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Course the student is ACTIVE-enrolled in.")
public record StudentDashboardCourseResponse(
		UUID courseId,
		String courseCode,
		String subjectCode,
		String subjectName,
		String semesterCode) {}
