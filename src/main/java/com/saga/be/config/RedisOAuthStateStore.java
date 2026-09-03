package com.saga.be.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.oauth.OAuthState;
import com.saga.be.integration.oauth.OAuthStateStore;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

public class RedisOAuthStateStore implements OAuthStateStore {

	private static final String KEY_PREFIX = "saga:oauth:state:";

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;
	private final Duration ttl;

	public RedisOAuthStateStore(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl) {
		this.redis = redis;
		this.mapper = mapper;
		this.ttl = ttl;
	}

	@Override
	public void save(OAuthState state, Duration requestedTtl) {
		try {
			redis.opsForValue()
					.set(KEY_PREFIX + state.state(), mapper.writeValueAsString(state), requestedTtl != null ? requestedTtl : ttl);
		} catch (RedisConnectionFailureException | RedisSystemException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.SERVICE_UNAVAILABLE,
					"Unable to persist OAuth state.");
		}
	}

	@Override
	public Optional<OAuthState> consume(String state) {
		String key = KEY_PREFIX + state;
		String json;
		try {
			json = redis.opsForValue().getAndDelete(key);
		} catch (RedisConnectionFailureException | RedisSystemException ex) {
			throw ex;
		}
		if (json == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(mapper.readValue(json, OAuthState.class));
		} catch (Exception ex) {
			return Optional.empty();
		}
	}
}
