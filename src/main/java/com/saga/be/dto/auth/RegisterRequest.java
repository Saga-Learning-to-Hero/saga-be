package com.saga.be.dto.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(
		description =
				"Public Student registration. Role is never accepted from the client. Institutional @fpt.edu.vn / @fe.edu.vn must use Google.")
public record RegisterRequest(
		@Schema(example = "student.personal@example.com") @NotBlank @Email @Size(max = 255) String email,
		@Schema(example = "Example Student") @NotBlank @Size(max = 255) String fullName,
		@Schema(description = "Student code supplied by the student. Never derived from email.", example = "SE123456")
				@NotBlank
				@Size(max = 64)
				String studentCode,
		@Schema(example = "example-password") @NotBlank String password,
		@Schema(example = "example-password") @NotBlank String confirmPassword) {}
