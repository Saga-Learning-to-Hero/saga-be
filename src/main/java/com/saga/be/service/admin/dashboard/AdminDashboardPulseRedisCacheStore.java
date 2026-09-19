package com.saga.be.service.admin.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.dto.admin.dashboard.AdminDashboardIntegrationPulseCachedPayload;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class AdminDashboardPulseRedisCacheStore implements AdminDashboardPulseCacheStore {

	static final String CACHE_KEY = "saga:admin:dashboard:integration-pulse:v1";
	static final String LOCK_KEY = CACHE_KEY + ":lock";
	static final Duration CACHE_TTL = Duration.ofSeconds(60);
	static final Duration LOCK_TTL = Duration.ofSeconds(45);

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;

	public AdminDashboardPulseRedisCacheStore(StringRedisTemplate redis, ObjectMapper mapper) {
		this.redis = redis;
		this.mapper = mapper;
	}

	@Override
	public Optional<AdminDashboardIntegrationPulseCachedPayload> get() {
		String json;
		try {
			json = redis.opsForValue().get(CACHE_KEY);
		} catch (RedisConnectionFailureException | RedisSystemException ex) {
			throw ex;
		}
		if (json == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(mapper.readValue(json, AdminDashboardIntegrationPulseCachedPayload.class));
		} catch (Exception ex) {
			return Optional.empty();
		}
	}

	@Override
	public boolean publishIfOwner(String ownerToken, AdminDashboardIntegrationPulseCachedPayload payload) {
		String json;
		try {
			json = mapper.writeValueAsString(payload);
		} catch (JsonProcessingException ex) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.SERVICE_UNAVAILABLE,
					"Unable to persist admin dashboard integration pulse cache.");
		}
		Long published;
		try {
			published = redis.execute(
					AdminDashboardRedisScripts.PUBLISH_IF_OWNER,
					List.of(LOCK_KEY, CACHE_KEY),
					ownerToken,
					json,
					String.valueOf(CACHE_TTL.toSeconds()));
		} catch (RedisConnectionFailureException | RedisSystemException ex) {
			throw ex;
		}
		return published != null && published > 0;
	}

	@Override
	public Optional<Long> ttlSeconds() {
		Long expire;
		try {
			expire = redis.getExpire(CACHE_KEY, TimeUnit.SECONDS);
		} catch (RedisConnectionFailureException | RedisSystemException ex) {
			throw ex;
		}
		if (expire == null || expire < 0) {
			return Optional.empty();
		}
		return Optional.of(expire);
	}

	@Override
	public boolean tryLock(String ownerToken) {
		Boolean placed;
		try {
			placed = redis.opsForValue().setIfAbsent(LOCK_KEY, ownerToken, LOCK_TTL);
		} catch (RedisConnectionFailureException | RedisSystemException ex) {
			throw ex;
		}
		return Boolean.TRUE.equals(placed);
	}

	@Override
	public boolean unlock(String ownerToken) {
		Long deleted;
		try {
			deleted = redis.execute(AdminDashboardRedisScripts.COMPARE_AND_DELETE, List.of(LOCK_KEY), ownerToken);
		} catch (RedisConnectionFailureException | RedisSystemException ex) {
			throw ex;
		}
		return deleted != null && deleted > 0;
	}
}
