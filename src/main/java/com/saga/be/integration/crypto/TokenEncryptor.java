package com.saga.be.integration.crypto;

import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * Versioned AES-256-GCM envelope: {@code v1:<base64(nonce || ciphertext+tag)>}.
 */
public class TokenEncryptor {

	private static final String PREFIX = "v1:";
	private static final int NONCE_LENGTH = 12;
	private static final int TAG_BITS = 128;

	private final byte[] key;
	private final SecureRandom random = new SecureRandom();

	public TokenEncryptor(String base64Key) {
		this.key = decodeKey(base64Key);
	}

	public boolean isReady() {
		return key != null && key.length == 32;
	}

	public String encrypt(String plaintext, String aad) {
		requireReady();
		if (plaintext == null) {
			return null;
		}
		byte[] nonce = new byte[NONCE_LENGTH];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
			if (StringUtils.hasText(aad)) {
				cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
			}
			byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			ByteBuffer buffer = ByteBuffer.allocate(nonce.length + cipherText.length);
			buffer.put(nonce);
			buffer.put(cipherText);
			return PREFIX + Base64.getEncoder().encodeToString(buffer.array());
		} catch (GeneralSecurityException ex) {
			throw unavailable("Token encryption failed.");
		}
	}

	public String decrypt(String envelope, String aad) {
		requireReady();
		if (!StringUtils.hasText(envelope)) {
			return null;
		}
		if (!envelope.startsWith(PREFIX)) {
			throw unavailable("Unsupported token envelope version.");
		}
		byte[] decoded = Base64.getDecoder().decode(envelope.substring(PREFIX.length()));
		if (decoded.length <= NONCE_LENGTH) {
			throw unavailable("Token envelope is truncated.");
		}
		byte[] nonce = Arrays.copyOfRange(decoded, 0, NONCE_LENGTH);
		byte[] cipherText = Arrays.copyOfRange(decoded, NONCE_LENGTH, decoded.length);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
			if (StringUtils.hasText(aad)) {
				cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
			}
			return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw unavailable("Token decryption failed.");
		}
	}

	public static String aad(String integrationId, String provider, String connectedUserId) {
		return integrationId + "|" + provider + "|" + connectedUserId;
	}

	private void requireReady() {
		if (!isReady()) {
			throw unavailable("Integration token encryption key is not configured.");
		}
	}

	private static byte[] decodeKey(String base64Key) {
		if (!StringUtils.hasText(base64Key)) {
			return new byte[0];
		}
		try {
			return Base64.getDecoder().decode(base64Key.trim());
		} catch (IllegalArgumentException ex) {
			return new byte[0];
		}
	}

	private static IntegrationException unavailable(String message) {
		return new IntegrationException(IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, message);
	}
}
