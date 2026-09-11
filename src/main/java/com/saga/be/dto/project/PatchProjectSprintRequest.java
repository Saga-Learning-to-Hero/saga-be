package com.saga.be.dto.project;

import jakarta.validation.constraints.Size;

public record PatchProjectSprintRequest(
		@Size(max = 255) String name, @Size(max = 1000) String goal, String state, String startDate, String endDate) {}
