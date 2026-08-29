package com.saga.be.dto.academic;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateAcademicClassRequest(
		@NotNull UUID semesterId,
		@NotBlank @Size(max = 64) String classCode,
		@Size(max = 255) String name) {}
