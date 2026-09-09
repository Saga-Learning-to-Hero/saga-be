package com.saga.be.integration.oauth;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface PendingJiraConnectStore {

	void save(PendingJiraConnect pending, Duration ttl);

	Optional<PendingJiraConnect> get(UUID userId, UUID projectId);

	/**
	 * Destructive single-use take without TTL metadata. Prefer {@link #claim} when compensation on
	 * JDBC rollback is required.
	 */
	Optional<PendingJiraConnect> consume(UUID userId, UUID projectId);

	/**
	 * Atomically claims the pending grant and captures absolute expiry so a rollback can restore
	 * the original remaining lifetime.
	 */
	Optional<PendingJiraClaim> claim(UUID userId, UUID projectId);

	/**
	 * Restores {@code claim} only if no newer pending grant exists for the same user/project and
	 * the original expiry has not passed. Never extends TTL to the full configured oauth TTL.
	 *
	 * @return true if the claim was restored
	 */
	boolean restoreIfAbsent(PendingJiraClaim claim);
}
