package com.saga.be.integration.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingJiraConnectStoreTest {

	@Test
	void pendingConnectIsSingleUseAndRedacted() {
		InMemoryPendingJiraConnectStore store = new InMemoryPendingJiraConnectStore();
		UUID user = UUID.randomUUID();
		UUID project = UUID.randomUUID();
		PendingJiraConnect pending = new PendingJiraConnect(
				user, project, "access-token-secret", "refresh-token-secret", "read:jira-work", Instant.now());
		assertTrue(!pending.toString().contains("access-token-secret"));
		assertTrue(!pending.toString().contains("refresh-token-secret"));
		store.save(pending, Duration.ofMinutes(10));
		assertEquals("access-token-secret", store.get(user, project).orElseThrow().accessToken());
		assertEquals("refresh-token-secret", store.consume(user, project).orElseThrow().refreshToken());
		assertTrue(store.get(user, project).isEmpty());
		assertTrue(store.consume(user, project).isEmpty());
	}
}
