package com.saga.be.service.admin.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class AdminDashboardRedisCacheStore implements AdminDashboardCacheStore {

	/**
	 * v2 because Phase B added {@code weeklyTimeline}. Temporal decoration does not change the stored
	 * JSON shape, so this gate keeps v2.
	 */
	static final String KEY_PREFIX = "saga:admin:dashboard:summary:v2:";
	static final Duration CACHE_TTL = Duration.ofSeconds(600);
	static final Duration LOCK_TTL = Duration.ofSeconds(45);

	private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>();
	private static final DefaultRedisScript<Long> PUBLISH_IF_OWNER = new DefaultRedisScript<>();

	static {
		COMPARE_AND_DELETE.setResultType(Long.class);
		COMPARE_AND_DELETE.setScriptText(
				"""
				if redis.call('GET', KEYS[1]) == ARGV[1] then
				  return redis.call('DEL', KEYS[1])
				else
				  return 0
				end
				""");
		PUBLISH_IF_OWNER.setResultType(Long.class);
		PUBLISH_IF_OWNER.setScriptText(
				"""
				if redis.call('GET', KEYS[1]) == ARGV[1] then
				  redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
				  redis.call('DEL', KEYS[1])
				  return 1
				else
				  return 0
				end
				""");
	}

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;

	public AdminDashboardRedisCacheStore(StringRedisTemplate redis, ObjectMapper mapper) {
		this.redis = redis;
		this.mapper = mapper;
	}

	static String cacheKey(UUID semesterId) {
		return KEY_PREFIX + semesterId;
	}

	static String lockKey(UUID semesterId) {
		return KEY_PREFIX + semesterId + ":lock";
	}

	@Override
	public Optional<AdminDashboardCachedPayload> get(UUID semesterId) {
		String json;
		try {
			json = redis.opsForValue().get(cacheKey(semesterId));
		} catch (RedisConnectionFailureException | RedisSystemException ex) {
			throw ex;
		}
		if (json == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(mapper.readValue(json, AdminDashboardCachedPayload.class));
		} catch (Exception ex) {
			return Optional.empty();
		}
	}

	@Override
	public boolean publishIfOwner(UUID semesterId, String ownerToken, AdminDashboardCachedPayload payload) {
		String json;
		try {
			json = mapper.writeValueAsString(payload);
		} catch (JsonProcessingException ex) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.SERVICE_UNAVAILABLE,
					"Unable to persist admin dashboard cache.");
		}
		Long published;
		try {
			published = redis.execute(
					PUBLISH_IF_OWNER,
					List.of(lockKey(semesterId), cacheKey(semesterId)),
					ownerToken,
					json,
					String.valueOf(CACHE_TTL.toSeconds()));
		} catch (RedisConnectionFailureException | RedisSystemException ex) {
			throw ex;
		}
		return published != null && published > 0;
	}

	@Override
	public Optional<Long> ttlSeconds(UUID semesterId) {
		Long expire;
		try {
			expire = redis.getExpire(cacheKey(semesterId), TimeUnit.SECONDS);
		} catch (RedisConnectionFailureException | RedisSystemException ex) {
			throw ex;
		}
		if (expire == null || expire < 0) {
			return Optional.empty();
		}
		return Optional.of(expire);
	}

	@Override
	public boolean tryLock(UUID semesterId, String ownerToken) {
		Boolean placed;
		try {
			placed = redis.opsForValue().setIfAbsent(lockKey(semesterId), ownerToken, LOCK_TTL);
		} catch (RedisConnectionFailureException | RedisSystemException ex) {
			throw ex;
		}
		return Boolean.TRUE.equals(placed);
	}

	@Override
	public boolean unlock(UUID semesterId, String ownerToken) {
		Long deleted;
		try {
			deleted = redis.execute(COMPARE_AND_DELETE, List.of(lockKey(semesterId)), ownerToken);
		} catch (RedisConnectionFailureException | RedisSystemException ex) {
			throw ex;
		}
		return deleted != null && deleted > 0;
	}
}
