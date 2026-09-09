package com.saga.be.integration.oauth;

import com.saga.be.dto.integration.GithubReconnectCandidateResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryGithubReconnectCandidateStore implements GithubReconnectCandidateStore {

	private final ConcurrentHashMap<String, Held> values = new ConcurrentHashMap<>();

	@Override
	public void save(UUID userId, UUID projectId, List<GithubReconnectCandidateResponse> candidates, Duration ttl) {
		Duration safeTtl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(10) : ttl;
		values.put(key(userId, projectId), new Held(List.copyOf(candidates), Instant.now().plus(safeTtl)));
	}

	@Override
	public List<GithubReconnectCandidateResponse> find(UUID userId, UUID projectId) {
		Held held = values.get(key(userId, projectId));
		if (held == null || Instant.now().isAfter(held.expiresAt())) {
			values.remove(key(userId, projectId));
			return List.of();
		}
		return held.candidates();
	}

	@Override
	public void clear(UUID userId, UUID projectId) {
		values.remove(key(userId, projectId));
	}

	private static String key(UUID userId, UUID projectId) {
		return userId + ":" + projectId;
	}

	private record Held(List<GithubReconnectCandidateResponse> candidates, Instant expiresAt) {}
}
