package com.saga.be.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectSprintRequest(
		@NotBlank @Size(max = 255) String name,
		@Size(max = 1000) String goal,
		String startDate,
		String endDate) {}
