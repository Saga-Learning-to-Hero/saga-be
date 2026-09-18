package com.saga.be.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "API error envelope")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
		@Schema(description = "Machine-readable code", example = "INVALID_CREDENTIALS") String code,
		@Schema(description = "Human-readable message") String message,
		@Schema(description = "Optional machine-readable recovery/context details") Object details) {

	public ApiErrorResponse(String code, String message) {
		this(code, message, null);
	}
}
