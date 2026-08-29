package com.saga.be.dto.academic;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PhaseInput(
		@NotBlank @Size(max = 64) String code,
		@NotBlank @Size(max = 255) String name,
		String description,
		@NotNull Integer orderIndex,
		List<String> learningOutcomeCodes,
		List<@Valid ActivityInput> activities,
		List<@Valid DeliverableInput> deliverables) {}
