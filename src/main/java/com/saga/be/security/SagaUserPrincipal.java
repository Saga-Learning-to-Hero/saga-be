package com.saga.be.security;

import com.saga.be.entity.enums.AccountRole;
import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

public final class SagaUserPrincipal implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private final UUID userId;
	private final String email;
	private final String username;
	private final String fullName;
	private final String avatarUrl;
	private final AccountRole role;
	private final boolean passwordSetupRequired;

	public SagaUserPrincipal(
			UUID userId,
			String email,
			String username,
			String fullName,
			String avatarUrl,
			AccountRole role,
			boolean passwordSetupRequired) {
		this.userId = userId;
		this.email = email;
		this.username = username;
		this.fullName = fullName;
		this.avatarUrl = avatarUrl;
		this.role = role;
		this.passwordSetupRequired = passwordSetupRequired;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getEmail() {
		return email;
	}

	public String getUsername() {
		return username;
	}

	public String getFullName() {
		return fullName;
	}

	public String getAvatarUrl() {
		return avatarUrl;
	}

	public AccountRole getRole() {
		return role;
	}

	public boolean isPasswordSetupRequired() {
		return passwordSetupRequired;
	}
}
