package com.saga.be.service.ai;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** At-rest encryption for {@code ai_course_provider_credential.encrypted_secret}, keyed by
 * {@code SAGA_AI_CREDENTIAL_MASTER_KEY} (base64, exactly 32 bytes for AES-256). The key is read
 * once at construction and never logged; a missing/malformed key does not fail application
 * startup (BYOK is an optional feature) -- it fails closed only when a credential operation is
 * actually attempted, exactly like every other optional-feature gate in this codebase. */
@Component
public class AiCredentialCipher {
	public static final int KEY_VERSION = 1;

	private final SecretKeySpec key;

	public AiCredentialCipher(@Value("${SAGA_AI_CREDENTIAL_MASTER_KEY:}") String masterKeyBase64) {
		this.key = AesGcmPrimitive.parseKey(masterKeyBase64);
	}

	public boolean isConfigured() { return key != null; }

	public record Encrypted(String ciphertextBase64, String nonceBase64, int keyVersion) {}

	public Encrypted encrypt(String plaintext) {
		requireConfigured();
		byte[] nonce = AesGcmPrimitive.freshNonce();
		String ciphertext = AesGcmPrimitive.encrypt(key, nonce, plaintext, "AI_CREDENTIAL_ENCRYPTION_FAILED");
		return new Encrypted(ciphertext, Base64.getEncoder().encodeToString(nonce), KEY_VERSION);
	}

	public String decrypt(String ciphertextBase64, String nonceBase64, int keyVersion) {
		requireConfigured();
		if (keyVersion != KEY_VERSION) throw new AiCredentialCryptoException("AI_CREDENTIAL_KEY_VERSION_UNSUPPORTED");
		byte[] nonce;
		try { nonce = Base64.getDecoder().decode(nonceBase64); } catch (IllegalArgumentException e) { throw new AiCredentialCryptoException("AI_CREDENTIAL_DECRYPTION_FAILED"); }
		return AesGcmPrimitive.decrypt(key, nonce, ciphertextBase64, "AI_CREDENTIAL_DECRYPTION_FAILED");
	}

	private void requireConfigured() { if (key == null) throw new AiCredentialCryptoException("AI_CREDENTIAL_MASTER_KEY_NOT_CONFIGURED"); }
}
