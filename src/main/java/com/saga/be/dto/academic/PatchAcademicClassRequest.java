package com.saga.be.dto.academic;

import jakarta.validation.constraints.Size;

public record PatchAcademicClassRequest(
		@Size(max = 64) String classCode,
		@Size(max = 255) String name) {}
