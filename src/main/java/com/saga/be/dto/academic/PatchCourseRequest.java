package com.saga.be.dto.academic;

import jakarta.validation.constraints.Size;
import java.util.UUID;

public record PatchCourseRequest(
		UUID lecturerId,
		UUID syllabusVersionId,
		@Size(max = 64) String courseCode,
		@Size(max = 255) String name) {}
