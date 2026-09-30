package com.saga.be.dto.student;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "One page of the authenticated student's ACTIVE courses, same order as the unpaged list.")
public record StudentCoursePageResponse(List<StudentCourseResponse> items, int page, int size, long total) {}
