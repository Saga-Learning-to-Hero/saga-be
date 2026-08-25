package com.saga.be.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Safe registration result. No password hash, session, or Google subject.")
public record RegisterResponse(boolean registered, RegisteredUserDto user) {

	public record RegisteredUserDto(
			UUID id,
			String email,
			String fullName,
			@Schema(description = "Always STUDENT for public registration.", example = "STUDENT") String role) {}
}
