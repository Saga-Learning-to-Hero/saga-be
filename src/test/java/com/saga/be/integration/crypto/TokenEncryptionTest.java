package com.saga.be.integration.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.jira.JiraRefreshTokenRotator;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TokenEncryptionTest {

	@Test
	void encryptedRefreshTokenRoundTripsAndNeverStoresPlaintext() {
		byte[] key = new byte[32];
		for (int i = 0; i < 32; i++) {
			key[i] = (byte) i;
		}
		TokenEncryptor encryptor = new TokenEncryptor(Base64.getEncoder().encodeToString(key));
		String aad = TokenEncryptor.aad("int-1", "JIRA", "user-1");
		String envelope = encryptor.encrypt("refresh-token-value", aad);
		assertTrue(envelope.startsWith("v1:"));
		assertFalse(envelope.contains("refresh-token-value"));
		assertEquals("refresh-token-value", encryptor.decrypt(envelope, aad));
		assertNotEquals(envelope, encryptor.encrypt("refresh-token-value", aad));
	}

	@Test
	void wrongAadFailsClosed() {
		TokenEncryptor encryptor = new TokenEncryptor(Base64.getEncoder().encodeToString(new byte[32]));
		String envelope = encryptor.encrypt("secret", TokenEncryptor.aad("a", "JIRA", "u"));
		assertThrows(IntegrationException.class, () -> encryptor.decrypt(envelope, TokenEncryptor.aad("b", "JIRA", "u")));
	}

	@Test
	void concurrentRotationDoesNotCorruptToken() throws InterruptedException {
		byte[] key = new byte[32];
		key[0] = 7;
		TokenEncryptor encryptor = new TokenEncryptor(Base64.getEncoder().encodeToString(key));
		UUID id = UUID.randomUUID();
		String aad = TokenEncryptor.aad(id.toString(), "JIRA", "user");
		AtomicStore store = new AtomicStore(encryptor.encrypt("rt-0", aad), encryptor, aad);
		JiraRefreshTokenRotator rotator = new JiraRefreshTokenRotator(store, encryptor);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(8);
		for (int i = 0; i < 8; i++) {
			Thread.ofVirtual().start(() -> {
				try {
					start.await();
					rotator.refresh(id, "JIRA", "user");
				} catch (Exception ignored) {
					// ignore
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		done.await();
		assertTrue(store.currentEncryptedRefresh(id).startsWith("v1:"));
		assertFalse(store.currentEncryptedRefresh(id).contains("rt-"));
		assertTrue(store.exchanges.get() >= 1);
	}

	private static final class AtomicStore implements JiraRefreshTokenRotator.TokenSource {
		private final Map<UUID, String> tokens = new ConcurrentHashMap<>();
		private final TokenEncryptor encryptor;
		private final String aad;
		private final AtomicInteger exchanges = new AtomicInteger();
		private final AtomicInteger seq = new AtomicInteger();

		private AtomicStore(String initial, TokenEncryptor encryptor, String aad) {
			this.encryptor = encryptor;
			this.aad = aad;
			tokens.put(UUID.fromString(aad.split("\\|")[0]), initial);
		}

		@Override
		public String currentEncryptedRefresh(UUID integrationId) {
			return tokens.get(integrationId);
		}

		@Override
		public void persistRotated(UUID integrationId, String encryptedRefreshToken) {
			tokens.put(integrationId, encryptedRefreshToken);
		}

		@Override
		public JiraRefreshTokenRotator.Rotation exchange(String plaintextRefreshToken) {
			int n = seq.incrementAndGet();
			exchanges.incrementAndGet();
			String next = "rt-" + n;
			return new JiraRefreshTokenRotator.Rotation(
					encryptor.encrypt(next, aad), "access-" + n, LocalDateTime.now().plusHours(1));
		}
	}
}
