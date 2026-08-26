package com.saga.be.integration.jira;

import com.saga.be.integration.crypto.TokenEncryptor;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rotates Atlassian refresh tokens under a per-integration lock.
 */
public class JiraRefreshTokenRotator {

	public record Rotation(String encryptedRefreshToken, String accessToken, java.time.LocalDateTime accessExpiresAt) {}

	public interface TokenSource {
		String currentEncryptedRefresh(UUID integrationId);

		void persistRotated(UUID integrationId, String encryptedRefreshToken);

		Rotation exchange(String plaintextRefreshToken);
	}

	private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
	private final TokenSource tokens;
	private final TokenEncryptor encryptor;

	public JiraRefreshTokenRotator(TokenSource tokens, TokenEncryptor encryptor) {
		this.tokens = tokens;
		this.encryptor = encryptor;
	}

	public Rotation refresh(UUID integrationId, String provider, String connectedUserId) {
		Object lock = locks.computeIfAbsent(integrationId, id -> new Object());
		synchronized (lock) {
			String encrypted = tokens.currentEncryptedRefresh(integrationId);
			String plaintext = encryptor.decrypt(encrypted, TokenEncryptor.aad(integrationId.toString(), provider, connectedUserId));
			Rotation rotated = tokens.exchange(plaintext);
			if (rotated.encryptedRefreshToken() != null) {
				tokens.persistRotated(integrationId, rotated.encryptedRefreshToken());
			} else if (rotated.accessToken() != null) {
				String next = encryptor.encrypt(plaintext, TokenEncryptor.aad(integrationId.toString(), provider, connectedUserId));
				tokens.persistRotated(integrationId, next);
			}
			return rotated;
		}
	}
}
