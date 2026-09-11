package com.saga.be.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Canonical self-profile view. Only real, existing {@code UserAccount}/{@code StudentProfile}
 * fields — no password hash, OAuth subject/tokens, or other internal security fields.
 */
@Schema(description = "Current user's own profile. studentCode is present only for STUDENT accounts.")
public record UserProfileResponse(
		UUID id,
		String email,
		String username,
		String fullName,
		String avatarUrl,
		@Schema(description = "STUDENT, LECTURER, or ADMIN") String role,
		@Schema(description = "ACTIVE, INACTIVE, or SUSPENDED — read-only") String accountStatus,
		@Schema(description = "Institution-managed; null for non-STUDENT accounts; never editable here")
				String studentCode) {}
