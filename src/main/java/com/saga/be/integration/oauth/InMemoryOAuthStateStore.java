package com.saga.be.integration.oauth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryOAuthStateStore implements OAuthStateStore {

	private final ConcurrentHashMap<String, Held> values = new ConcurrentHashMap<>();

	@Override
	public void save(OAuthState state, Duration ttl) {
		values.put(state.state(), new Held(state, Instant.now().plus(ttl)));
	}

	@Override
	public Optional<OAuthState> consume(String state) {
		Held held = values.remove(state);
		if (held == null) {
			return Optional.empty();
		}
		if (Instant.now().isAfter(held.expiresAt())) {
			return Optional.empty();
		}
		return Optional.of(held.state());
	}

	private record Held(OAuthState state, Instant expiresAt) {}
}
