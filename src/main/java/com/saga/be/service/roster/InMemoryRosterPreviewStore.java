package com.saga.be.service.roster;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRosterPreviewStore implements RosterPreviewStore {

	private final Map<String, RosterPreviewSnapshot> rows = new ConcurrentHashMap<>();

	@Override
	public void save(String token, RosterPreviewSnapshot snapshot, Duration ttl) {
		rows.put(token, snapshot);
	}

	@Override
	public Optional<RosterPreviewSnapshot> find(String token) {
		return Optional.ofNullable(rows.get(token));
	}

	@Override
	public void delete(String token) {
		rows.remove(token);
	}
}
