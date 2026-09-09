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
		return claim(userId, projectId).map(PendingJiraClaim::pending);
	}

	@Override
	public Optional<PendingJiraClaim> claim(UUID userId, UUID projectId) {
		Held held = values.remove(key(userId, projectId));
		if (held == null || Instant.now().isAfter(held.expiresAt())) {
			return Optional.empty();
		}
		return Optional.of(new PendingJiraClaim(held.pending(), held.expiresAt(), null));
	}

	@Override
	public boolean restoreIfAbsent(PendingJiraClaim claim) {
		if (claim == null || claim.pending() == null || claim.expiresAt() == null) {
			return false;
		}
		Instant now = Instant.now();
		if (!now.isBefore(claim.expiresAt())) {
			return false;
		}
		Held previous = values.putIfAbsent(
				key(claim.pending().userId(), claim.pending().projectId()),
				new Held(claim.pending(), claim.expiresAt()));
		return previous == null;
	}

	private static String key(UUID userId, UUID projectId) {
		return userId + ":" + projectId;
	}

	private record Held(PendingJiraConnect pending, Instant expiresAt) {}
}
