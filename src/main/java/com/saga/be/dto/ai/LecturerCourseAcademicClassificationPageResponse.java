package com.saga.be.dto.ai;

import java.util.List;

public record LecturerCourseAcademicClassificationPageResponse(
		List<LecturerCourseAcademicClassificationResponse> items, int page, int size, long total) {}
