package com.saga.be.integration.oauth;

import java.time.Instant;

/**
 * Result of atomically claiming a pending Jira team OAuth grant. Carries enough metadata to
 * restore the grant on JDBC rollback without extending TTL or overwriting a newer grant.
 *
 * <p>{@code restorePayload} is an opaque store-native blob (e.g. Redis ciphertext JSON). It must
 * never be logged.
 */
public final class PendingJiraClaim {

	private final PendingJiraConnect pending;
	private final Instant expiresAt;
	private final String restorePayload;

	public PendingJiraClaim(PendingJiraConnect pending, Instant expiresAt, String restorePayload) {
		this.pending = pending;
		this.expiresAt = expiresAt;
		this.restorePayload = restorePayload;
	}

	public PendingJiraConnect pending() {
		return pending;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	/** Opaque payload for {@link PendingJiraConnectStore#restoreIfAbsent(PendingJiraClaim)}. */
	public String restorePayload() {
		return restorePayload;
	}

	@Override
	public String toString() {
		return "PendingJiraClaim[userId="
				+ pending.userId()
				+ ", projectId="
				+ pending.projectId()
				+ ", expiresAt="
				+ expiresAt
				+ "]";
	}
}
