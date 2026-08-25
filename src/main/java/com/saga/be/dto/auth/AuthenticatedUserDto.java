package com.saga.be.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Authenticated account. Role comes from MySQL, never from the client.")
public record AuthenticatedUserDto(
		UUID id,
		String email,
		String username,
		String fullName,
		String avatarUrl,
		@Schema(description = "STUDENT, LECTURER, or ADMIN", example = "ADMIN") String role) {}
