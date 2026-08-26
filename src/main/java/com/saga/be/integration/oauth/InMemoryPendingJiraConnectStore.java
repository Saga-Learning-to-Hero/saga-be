package com.saga.be.integration.oauth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryPendingJiraConnectStore implements PendingJiraConnectStore {

	private final ConcurrentHashMap<String, Held> values = new ConcurrentHashMap<>();

	@Override
	public void save(PendingJiraConnect pending, Duration ttl) {
		values.put(key(pending.userId(), pending.projectId()), new Held(pending, Instant.now().plus(ttl)));
	}

	@Override
	public Optional<PendingJiraConnect> get(UUID userId, UUID projectId) {
		Held held = values.get(key(userId, projectId));
		if (held == null || Instant.now().isAfter(held.expiresAt())) {
			values.remove(key(userId, projectId));
			return Optional.empty();
		}
		return Optional.of(held.pending());
	}

	@Override
	public Optional<PendingJiraConnect> consume(UUID userId, UUID projectId) {
		Held held = values.remove(key(userId, projectId));
		if (held == null || Instant.now().isAfter(held.expiresAt())) {
			return Optional.empty();
		}
		return Optional.of(held.pending());
	}

	private static String key(UUID userId, UUID projectId) {
		return userId + ":" + projectId;
	}

	private record Held(PendingJiraConnect pending, Instant expiresAt) {}
}
