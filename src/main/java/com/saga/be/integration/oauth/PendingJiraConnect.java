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
	/** When set, selection/save must update this existing integration row (reconnect), never create another. */
	private final UUID targetIntegrationId;

	public PendingJiraConnect(
			UUID userId, UUID projectId, String accessToken, String refreshToken, String scope, Instant createdAt) {
		this(userId, projectId, accessToken, refreshToken, scope, createdAt, null);
	}

	public PendingJiraConnect(
			UUID userId,
			UUID projectId,
			String accessToken,
			String refreshToken,
			String scope,
			Instant createdAt,
			UUID targetIntegrationId) {
		this.userId = userId;
		this.projectId = projectId;
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
		this.scope = scope;
		this.createdAt = createdAt;
		this.targetIntegrationId = targetIntegrationId;
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

	public UUID targetIntegrationId() {
		return targetIntegrationId;
	}

	@Override
	public String toString() {
		return "PendingJiraConnect[userId="
				+ userId
				+ ", projectId="
				+ projectId
				+ (targetIntegrationId == null ? "" : ", targetIntegrationId=" + targetIntegrationId)
				+ "]";
	}
}
