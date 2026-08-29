package com.saga.be.dto.academic;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateCourseRequest(
		@NotNull UUID academicClassId,
		@NotNull UUID subjectId,
		@NotNull UUID syllabusVersionId,
		@NotNull UUID lecturerId,
		@Size(max = 64) String courseCode,
		@Size(max = 255) String name) {}
