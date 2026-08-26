package com.saga.be.integration.oauth;

import java.time.Instant;
import java.util.UUID;

public final class PendingJiraConnect {

	private final UUID userId;
	private final UUID projectId;
	private final String accessToken;
	private final String refreshToken;
	private final String scope;
	private final Instant createdAt;

	public PendingJiraConnect(
			UUID userId, UUID projectId, String accessToken, String refreshToken, String scope, Instant createdAt) {
		this.userId = userId;
		this.projectId = projectId;
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
		this.scope = scope;
		this.createdAt = createdAt;
	}

	public UUID userId() {
		return userId;
	}

	public UUID projectId() {
		return projectId;
	}

	public String accessToken() {
		return accessToken;
	}

	public String refreshToken() {
		return refreshToken;
	}

	public String scope() {
		return scope;
	}

	public Instant createdAt() {
		return createdAt;
	}

	@Override
	public String toString() {
		return "PendingJiraConnect[userId=" + userId + ", projectId=" + projectId + "]";
	}
}
