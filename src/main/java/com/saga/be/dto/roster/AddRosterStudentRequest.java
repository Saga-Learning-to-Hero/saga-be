package com.saga.be.dto.roster;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddRosterStudentRequest(
		@NotBlank @Size(max = 255) String fullName,
		@NotBlank @Size(max = 64) String studentCode,
		@NotBlank @Size(max = 255) String email,
		@Schema(
						description =
								"Optional display-only field for FE/workbook parity. Not persisted and not used for identity matching or Git/Jira attribution.",
						requiredMode = Schema.RequiredMode.NOT_REQUIRED)
				@Size(max = 64)
				String memberCode) {}
