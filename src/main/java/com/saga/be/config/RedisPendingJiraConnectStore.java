package com.saga.be.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saga.be.integration.crypto.TokenEncryptor;
import com.saga.be.integration.oauth.PendingJiraConnect;
import com.saga.be.integration.oauth.PendingJiraConnectStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisPendingJiraConnectStore implements PendingJiraConnectStore {

	private static final String KEY_PREFIX = "saga:oauth:pending:jira:";

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
			redis.opsForValue()
					.set(key(pending.userId(), pending.projectId()), mapper.writeValueAsString(node), requestedTtl != null ? requestedTtl : ttl);
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
		String json = redis.opsForValue().getAndDelete(key(userId, projectId));
		return decode(json);
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

	private static String key(UUID userId, UUID projectId) {
		return KEY_PREFIX + userId + ":" + projectId;
	}
}
