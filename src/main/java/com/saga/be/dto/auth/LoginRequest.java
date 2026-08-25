package com.saga.be.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Local login. Identifier is email when it contains @, otherwise username. Role is never supplied by the client.")
public record LoginRequest(
		@Schema(description = "Email or username.", example = "admin") @NotBlank String identifier,
		@Schema(description = "Local password. Example only — not a real credential.", example = "example-password")
				@NotBlank
				String password) {}
