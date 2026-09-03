package com.saga.be.service.team;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTeamPreviewStore implements TeamPreviewStore {

	private final Map<String, TeamPreviewSnapshot> rows = new ConcurrentHashMap<>();

	@Override
	public void save(String token, TeamPreviewSnapshot snapshot, Duration ttl) {
		rows.put(token, snapshot);
	}

	@Override
	public Optional<TeamPreviewSnapshot> find(String token) {
		return Optional.ofNullable(rows.get(token));
	}

	@Override
	public void delete(String token) {
		rows.remove(token);
	}
}
