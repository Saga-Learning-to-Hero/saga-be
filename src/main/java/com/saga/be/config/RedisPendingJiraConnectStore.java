package com.saga.be.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saga.be.integration.crypto.TokenEncryptor;
import com.saga.be.integration.oauth.PendingJiraClaim;
import com.saga.be.integration.oauth.PendingJiraConnect;
import com.saga.be.integration.oauth.PendingJiraConnectStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class RedisPendingJiraConnectStore implements PendingJiraConnectStore {

	private static final String KEY_PREFIX = "saga:oauth:pending:jira:";

	/**
	 * Atomically GET value, PTTL, DEL. Returns {value, pttlMillis} or null when missing.
	 */
	private static final DefaultRedisScript<List> CLAIM_SCRIPT = new DefaultRedisScript<>();

	static {
		CLAIM_SCRIPT.setResultType(List.class);
		CLAIM_SCRIPT.setScriptText(
				"""
				local value = redis.call('GET', KEYS[1])
				if not value then
				  return nil
				end
				local ttl = redis.call('PTTL', KEYS[1])
				redis.call('DEL', KEYS[1])
				return {value, ttl}
				""");
	}

	private final StringRedisTemplate redis;
	private final TokenEncryptor encryptor;
	private final ObjectMapper mapper;
	private final Duration ttl;

	public RedisPendingJiraConnectStore(
			StringRedisTemplate redis, TokenEncryptor encryptor, ObjectMapper mapper, Duration ttl) {
		this.redis = redis;
		this.encryptor = encryptor;
		this.mapper = mapper;
		this.ttl = ttl;
	}

	@Override
	public void save(PendingJiraConnect pending, Duration requestedTtl) {
		if (!encryptor.isReady()) {
			throw new IllegalStateException("Token encryption key is required to cache Jira team OAuth.");
		}
		try {
			String json = encode(pending);
			redis.opsForValue()
					.set(key(pending.userId(), pending.projectId()), json, requestedTtl != null ? requestedTtl : ttl);
		} catch (Exception ex) {
			throw new IllegalStateException("Unable to persist pending Jira connection.");
		}
	}

	@Override
	public Optional<PendingJiraConnect> get(UUID userId, UUID projectId) {
		String json = redis.opsForValue().get(key(userId, projectId));
		return decode(json);
	}

	@Override
	public Optional<PendingJiraConnect> consume(UUID userId, UUID projectId) {
		return claim(userId, projectId).map(PendingJiraClaim::pending);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Optional<PendingJiraClaim> claim(UUID userId, UUID projectId) {
		String redisKey = key(userId, projectId);
		List<Object> result = redis.execute(CLAIM_SCRIPT, List.of(redisKey));
		if (result == null || result.isEmpty() || result.get(0) == null) {
			return Optional.empty();
		}
		String json = String.valueOf(result.get(0));
		long pttlMillis = parsePttl(result.size() > 1 ? result.get(1) : null);
		Optional<PendingJiraConnect> pending = decode(json);
		if (pending.isEmpty()) {
			return Optional.empty();
		}
		Instant expiresAt;
		if (pttlMillis > 0) {
			expiresAt = Instant.now().plusMillis(pttlMillis);
		} else if (pttlMillis == -1) {
			// Key had no TTL (unexpected); bound restore to configured oauth TTL from now.
			expiresAt = Instant.now().plus(ttl);
		} else {
			return Optional.empty();
		}
		return Optional.of(new PendingJiraClaim(pending.get(), expiresAt, json));
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
		String payload = claim.restorePayload();
		if (payload == null || payload.isBlank()) {
			return false;
		}
		Duration remaining = Duration.between(now, claim.expiresAt());
		if (remaining.isZero() || remaining.isNegative()) {
			return false;
		}
		Boolean placed = redis.opsForValue()
				.setIfAbsent(key(claim.pending().userId(), claim.pending().projectId()), payload, remaining);
		return Boolean.TRUE.equals(placed);
	}

	private String encode(PendingJiraConnect pending) throws Exception {
		ObjectNode node = mapper.createObjectNode();
		String aad = TokenEncryptor.aad(pending.projectId().toString(), "JIRA", pending.userId().toString());
		node.put("userId", pending.userId().toString());
		node.put("projectId", pending.projectId().toString());
		node.put("accessEnvelope", encryptor.encrypt(pending.accessToken(), aad));
		if (pending.refreshToken() != null) {
			node.put("refreshEnvelope", encryptor.encrypt(pending.refreshToken(), aad));
		}
		node.put("scope", pending.scope());
		node.put("createdAt", pending.createdAt().toString());
		return mapper.writeValueAsString(node);
	}

	private Optional<PendingJiraConnect> decode(String json) {
		if (json == null) {
			return Optional.empty();
		}
		try {
			JsonNode node = mapper.readTree(json);
			UUID userId = UUID.fromString(node.path("userId").asText());
			UUID projectId = UUID.fromString(node.path("projectId").asText());
			String aad = TokenEncryptor.aad(projectId.toString(), "JIRA", userId.toString());
			String access = encryptor.decrypt(node.path("accessEnvelope").asText(), aad);
			String refresh = node.hasNonNull("refreshEnvelope")
					? encryptor.decrypt(node.path("refreshEnvelope").asText(), aad)
					: null;
			return Optional.of(new PendingJiraConnect(
					userId,
					projectId,
					access,
					refresh,
					node.path("scope").asText(""),
					Instant.parse(node.path("createdAt").asText(Instant.now().toString()))));
		} catch (Exception ex) {
			return Optional.empty();
		}
	}

	private static long parsePttl(Object raw) {
		if (raw == null) {
			return -2L;
		}
		if (raw instanceof Number number) {
			return number.longValue();
		}
		try {
			return Long.parseLong(String.valueOf(raw));
		} catch (NumberFormatException ex) {
			return -2L;
		}
	}

	private static String key(UUID userId, UUID projectId) {
		return KEY_PREFIX + userId + ":" + projectId;
	}
}
