package com.saga.be.dto.roster;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Why staff remove a student from a course or a team. Shown to the student by notification and email.")
public record StudentRemovalRequest(
		@Schema(description = "Required, 1-500 characters after trimming.", example = "Student transferred to another class.")
				@NotBlank
				@Size(max = 500)
				String reason) {}
