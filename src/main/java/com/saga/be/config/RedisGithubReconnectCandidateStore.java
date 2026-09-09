package com.saga.be.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.dto.integration.GithubReconnectCandidateResponse;
import com.saga.be.integration.oauth.GithubReconnectCandidateStore;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisGithubReconnectCandidateStore implements GithubReconnectCandidateStore {

	private static final String KEY_PREFIX = "saga:oauth:github:reconnect:candidates:";
	private static final TypeReference<List<GithubReconnectCandidateResponse>> LIST_TYPE = new TypeReference<>() {};

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;
	private final Duration defaultTtl;

	public RedisGithubReconnectCandidateStore(StringRedisTemplate redis, ObjectMapper mapper, Duration defaultTtl) {
		this.redis = redis;
		this.mapper = mapper;
		this.defaultTtl = defaultTtl == null ? Duration.ofMinutes(10) : defaultTtl;
	}

	@Override
	public void save(UUID userId, UUID projectId, List<GithubReconnectCandidateResponse> candidates, Duration ttl) {
		Duration safeTtl = ttl == null || ttl.isNegative() || ttl.isZero() ? defaultTtl : ttl;
		try {
			redis.opsForValue().set(key(userId, projectId), mapper.writeValueAsString(candidates), safeTtl);
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to persist GitHub reconnect candidates", ex);
		}
	}

	@Override
	public List<GithubReconnectCandidateResponse> find(UUID userId, UUID projectId) {
		String json = redis.opsForValue().get(key(userId, projectId));
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			List<GithubReconnectCandidateResponse> parsed = mapper.readValue(json, LIST_TYPE);
			return parsed == null ? List.of() : List.copyOf(parsed);
		} catch (Exception ex) {
			redis.delete(key(userId, projectId));
			return List.of();
		}
	}

	@Override
	public void clear(UUID userId, UUID projectId) {
		redis.delete(key(userId, projectId));
	}

	private static String key(UUID userId, UUID projectId) {
		return KEY_PREFIX + userId + ":" + projectId;
	}
}
