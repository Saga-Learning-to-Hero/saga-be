package com.saga.be.dto.academic;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ActivityInput(
		@NotBlank @Size(max = 64) String code,
		@NotBlank @Size(max = 255) String name,
		String description,
		@NotNull Integer orderIndex) {}
