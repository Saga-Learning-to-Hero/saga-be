package com.saga.be.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
		@Schema(description = "Raw one-time reset token from the emailed link.") @NotBlank String token,
		@Schema(example = "example-password") @NotBlank String newPassword) {}
