package com.saga.be.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.integration.crypto.TokenEncryptor;
import com.saga.be.integration.oauth.PendingJiraClaim;
import com.saga.be.integration.oauth.PendingJiraConnect;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RedisPendingJiraConnectStoreTest {

	private StringRedisTemplate redis;
	private ValueOperations<String, String> values;
	private TokenEncryptor encryptor;
	private RedisPendingJiraConnectStore store;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		byte[] key = new byte[32];
		for (int i = 0; i < key.length; i++) {
			key[i] = (byte) i;
		}
		encryptor = new TokenEncryptor(Base64.getEncoder().encodeToString(key));
		redis = mock(StringRedisTemplate.class);
		values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		store = new RedisPendingJiraConnectStore(redis, encryptor, new ObjectMapper(), Duration.ofMinutes(10));
	}

	@Test
	@SuppressWarnings("unchecked")
	void claimUsesAtomicScriptCapturingPttl() {
		UUID user = UUID.randomUUID();
		UUID project = UUID.randomUUID();
		PendingJiraConnect pending =
				new PendingJiraConnect(user, project, "access-secret", null, "read:jira-work", Instant.now());
		store.save(pending, Duration.ofMinutes(2));
		ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
		verify(values).set(eq(key(user, project)), payloadCaptor.capture(), eq(Duration.ofMinutes(2)));
		String json = payloadCaptor.getValue();
		when(redis.execute(any(RedisScript.class), anyList())).thenReturn(List.of(json, 120_000L));

		PendingJiraClaim claim = store.claim(user, project).orElseThrow();
		assertEquals("access-secret", claim.pending().accessToken());
		assertEquals(json, claim.restorePayload());
		assertTrue(claim.expiresAt().isAfter(Instant.now().plusSeconds(60)));
		assertTrue(claim.expiresAt().isBefore(Instant.now().plusSeconds(150)));
		assertFalse(claim.toString().contains("access-secret"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void restoreUsesSetIfAbsentWithRemainingTtl() {
		UUID user = UUID.randomUUID();
		UUID project = UUID.randomUUID();
		PendingJiraConnect pending =
				new PendingJiraConnect(user, project, "access", null, "read:jira-work", Instant.now());
		String payload = "{\"opaque\":true}";
		Instant expiresAt = Instant.now().plusSeconds(90);
		PendingJiraClaim claim = new PendingJiraClaim(pending, expiresAt, payload);
		when(values.setIfAbsent(eq(key(user, project)), eq(payload), any(Duration.class))).thenReturn(true);

		assertTrue(store.restoreIfAbsent(claim));
		ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
		verify(values).setIfAbsent(eq(key(user, project)), eq(payload), ttl.capture());
		assertTrue(ttl.getValue().compareTo(Duration.ofMinutes(10)) < 0);
		assertTrue(ttl.getValue().getSeconds() <= 90);
		assertTrue(ttl.getValue().getSeconds() >= 1);
	}

	@Test
	void restoreDoesNotOverwriteWhenKeyExists() {
		UUID user = UUID.randomUUID();
		UUID project = UUID.randomUUID();
		PendingJiraClaim claim = new PendingJiraClaim(
				new PendingJiraConnect(user, project, "old", null, "scope", Instant.now()),
				Instant.now().plusSeconds(60),
				"old-payload");
		when(values.setIfAbsent(eq(key(user, project)), eq("old-payload"), any(Duration.class))).thenReturn(false);
		assertFalse(store.restoreIfAbsent(claim));
	}

	@Test
	void restoreSkipsExpiredClaim() {
		UUID user = UUID.randomUUID();
		UUID project = UUID.randomUUID();
		PendingJiraClaim claim = new PendingJiraClaim(
				new PendingJiraConnect(user, project, "old", null, "scope", Instant.now()),
				Instant.now().minusSeconds(1),
				"old-payload");
		assertFalse(store.restoreIfAbsent(claim));
		verify(values, never()).setIfAbsent(any(), any(), any(Duration.class));
	}

	@Test
	@SuppressWarnings("unchecked")
	void claimReturnsEmptyWhenScriptMisses() {
		when(redis.execute(any(RedisScript.class), anyList())).thenReturn(null);
		assertEquals(Optional.empty(), store.claim(UUID.randomUUID(), UUID.randomUUID()));
	}

	private static String key(UUID userId, UUID projectId) {
		return "saga:oauth:pending:jira:" + userId + ":" + projectId;
	}
}
