package com.saga.be.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Student first-password setup. newPassword must equal confirmPassword. Min 10, max 128 characters.")
public record PasswordSetupRequest(
		@Schema(example = "example-password") @NotBlank String newPassword,
		@Schema(example = "example-password") @NotBlank String confirmPassword) {}
