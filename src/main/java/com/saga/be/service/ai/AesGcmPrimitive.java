package com.saga.be.service.ai;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Shared AES-256-GCM primitive behind both {@link AiCredentialCipher} (at-rest) and {@link
 * AiCredentialTransportCipher} (BE-to-saga-ai transport) -- same authenticated-encryption
 * mechanism, deliberately kept in one place so a fix to one never silently diverges from the
 * other. Never logs, never includes plaintext/key material in any exception message. */
final class AesGcmPrimitive {
	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int GCM_TAG_BITS = 128;
	private static final int NONCE_BYTES = 12;
	private static final int KEY_BYTES = 32;

	private AesGcmPrimitive() {}

	static SecretKeySpec parseKey(String base64Key) {
		if (base64Key == null || base64Key.isBlank()) return null;
		byte[] raw;
		try { raw = Base64.getDecoder().decode(base64Key.trim()); } catch (IllegalArgumentException e) { return null; }
		if (raw.length != KEY_BYTES) return null; // AES-256 requires exactly 32 bytes; malformed config fails closed, never truncated/padded
		return new SecretKeySpec(raw, "AES");
	}

	static byte[] freshNonce() { byte[] nonce = new byte[NONCE_BYTES]; new SecureRandom().nextBytes(nonce); return nonce; }

	static String encrypt(SecretKeySpec key, byte[] nonce, String plaintext, String safeFailureCode) {
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
			return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) { throw new AiCredentialCryptoException(safeFailureCode); }
	}

	static String decrypt(SecretKeySpec key, byte[] nonce, String ciphertextB64, String safeFailureCode) {
		try {
			byte[] ciphertext = Base64.getDecoder().decode(ciphertextB64);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
			return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException e) { throw new AiCredentialCryptoException(safeFailureCode); }
	}
}
