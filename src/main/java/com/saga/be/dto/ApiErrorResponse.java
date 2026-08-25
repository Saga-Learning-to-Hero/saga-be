package com.saga.be.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "API error envelope")
public record ApiErrorResponse(
@Schema(description = "Machine-readable code", example = "INVALID_CREDENTIALS")
				String code,
		@Schema(description = "Human-readable message") String message) {}
