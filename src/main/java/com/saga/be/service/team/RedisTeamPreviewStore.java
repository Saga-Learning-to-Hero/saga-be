package com.saga.be.service.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisTeamPreviewStore implements TeamPreviewStore {

	private static final String KEY_PREFIX = "saga:team:preview:";

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;

	public RedisTeamPreviewStore(StringRedisTemplate redis, ObjectMapper mapper) {
		this.redis = redis;
		this.mapper = mapper;
	}

	@Override
	public void save(String token, TeamPreviewSnapshot snapshot, Duration ttl) {
		try {
			redis.opsForValue().set(KEY_PREFIX + token, mapper.writeValueAsString(snapshot), ttl);
		} catch (Exception ex) {
			throw new IllegalStateException("Unable to persist team preview.");
		}
	}

	@Override
	public Optional<TeamPreviewSnapshot> find(String token) {
		try {
			String json = redis.opsForValue().get(KEY_PREFIX + token);
			if (json == null) {
				return Optional.empty();
			}
			return Optional.of(mapper.readValue(json, TeamPreviewSnapshot.class));
		} catch (Exception ex) {
			return Optional.empty();
		}
	}

	@Override
	public void delete(String token) {
		redis.delete(KEY_PREFIX + token);
	}
}
