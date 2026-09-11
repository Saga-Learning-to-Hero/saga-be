package com.saga.be.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(
		@Schema(example = "student@example.com") @NotBlank String email) {}
