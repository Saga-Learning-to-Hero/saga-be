package com.saga.be.integration.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
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

	@Test
	void claimThenRestorePreservesOriginalExpiryNotFullTtl() {
		InMemoryPendingJiraConnectStore store = new InMemoryPendingJiraConnectStore();
		UUID user = UUID.randomUUID();
		UUID project = UUID.randomUUID();
		PendingJiraConnect pending = new PendingJiraConnect(
				user, project, "access", "refresh", "read:jira-work", Instant.now());
		store.save(pending, Duration.ofMinutes(2));
		PendingJiraClaim claim = store.claim(user, project).orElseThrow();
		Instant claimedExpiry = claim.expiresAt();
		assertTrue(claimedExpiry.isBefore(Instant.now().plus(Duration.ofMinutes(3))));
		assertTrue(claimedExpiry.isAfter(Instant.now().plus(Duration.ofSeconds(60))));
		assertTrue(store.restoreIfAbsent(claim));
		PendingJiraClaim again = store.claim(user, project).orElseThrow();
		assertEquals(claimedExpiry, again.expiresAt());
		Duration remaining = Duration.between(Instant.now(), again.expiresAt());
		assertTrue(remaining.compareTo(Duration.ofMinutes(10)) < 0);
		assertTrue(remaining.compareTo(Duration.ofSeconds(30)) > 0);
	}

	@Test
	void restoreDoesNotOverwriteNewerPendingGrant() {
		InMemoryPendingJiraConnectStore store = new InMemoryPendingJiraConnectStore();
		UUID user = UUID.randomUUID();
		UUID project = UUID.randomUUID();
		PendingJiraConnect older = new PendingJiraConnect(
				user, project, "old-access", null, "read:jira-work", Instant.now());
		store.save(older, Duration.ofMinutes(5));
		PendingJiraClaim claimA = store.claim(user, project).orElseThrow();
		PendingJiraConnect newer = new PendingJiraConnect(
				user, project, "new-access", null, "read:jira-work", Instant.now());
		store.save(newer, Duration.ofMinutes(10));
		assertFalse(store.restoreIfAbsent(claimA));
		assertEquals("new-access", store.get(user, project).orElseThrow().accessToken());
	}

	@Test
	void expiredClaimIsNotRestored() {
		InMemoryPendingJiraConnectStore store = new InMemoryPendingJiraConnectStore();
		UUID user = UUID.randomUUID();
		UUID project = UUID.randomUUID();
		PendingJiraConnect pending = new PendingJiraConnect(
				user, project, "access", null, "read:jira-work", Instant.now());
		PendingJiraClaim expired = new PendingJiraClaim(pending, Instant.now().minusSeconds(1), null);
		assertFalse(store.restoreIfAbsent(expired));
		assertTrue(store.get(user, project).isEmpty());
	}

	@Test
	void concurrentClaimOnlyOneWinner() throws Exception {
		InMemoryPendingJiraConnectStore store = new InMemoryPendingJiraConnectStore();
		UUID user = UUID.randomUUID();
		UUID project = UUID.randomUUID();
		store.save(
				new PendingJiraConnect(user, project, "access", null, "read:jira-work", Instant.now()),
				Duration.ofMinutes(5));
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		AtomicInteger wins = new AtomicInteger();
		Runnable racer = () -> {
			try {
				start.await();
				if (store.claim(user, project).isPresent()) {
					wins.incrementAndGet();
				}
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			} finally {
				done.countDown();
			}
		};
		Thread t1 = new Thread(racer);
		Thread t2 = new Thread(racer);
		t1.start();
		t2.start();
		start.countDown();
		done.await();
		assertEquals(1, wins.get());
		assertTrue(store.get(user, project).isEmpty());
	}

	@Test
	void successfulClaimLeavesGrantConsumedWithoutRestore() {
		InMemoryPendingJiraConnectStore store = new InMemoryPendingJiraConnectStore();
		UUID user = UUID.randomUUID();
		UUID project = UUID.randomUUID();
		store.save(
				new PendingJiraConnect(user, project, "access", null, "read:jira-work", Instant.now()),
				Duration.ofMinutes(5));
		Optional<PendingJiraClaim> claim = store.claim(user, project);
		assertTrue(claim.isPresent());
		assertTrue(store.get(user, project).isEmpty());
	}
}
