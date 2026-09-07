package com.saga.be.dto.roster;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddRosterStudentRequest(
		@NotBlank @Size(max = 255) String fullName,
		@NotBlank @Size(max = 64) String studentCode,
		@NotBlank @Size(max = 255) String email,
		@Size(max = 64) String memberCode) {}
