package com.saga.be.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * PATCH semantics: a field left {@code null} (omitted from the JSON body) is unchanged.
 * {@code fullName}, if present, must be non-blank. {@code avatarUrl}, if present as an empty/blank
 * string, clears the avatar; only {@code http://}/{@code https://} URLs are otherwise accepted.
 * No id/role/email/studentCode/accountStatus field exists here — mass-assignment of those is
 * impossible because this DTO simply has no such component.
 */
public record UpdateProfileRequest(
		@Schema(example = "Nguyen Van A") @Size(max = 255) String fullName,
		@Schema(example = "https://example.com/avatar.png") @Size(max = 500) String avatarUrl) {}
