package com.saga.be.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Safe admin view of a user account. No password hash, Google subject, or session data.")
public record AdminUserResponse(
		UUID id,
		String email,
		String username,
		String fullName,
		String avatarUrl,
		@Schema(description = "STUDENT, LECTURER, or ADMIN") String role,
		@Schema(description = "ACTIVE, INACTIVE, SUSPENDED, or PENDING") String accountStatus,
		String studentCode,
		UUID lecturerProfileId,
		LocalDateTime createdAt) {}
