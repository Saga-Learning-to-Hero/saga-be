package com.saga.be.service.ai;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Request-scoped BE-to-saga-ai transport encryption, keyed by {@code
 * SAGA_AI_CREDENTIAL_TRANSPORT_KEY} -- a deliberately separate secret from the at-rest master key
 * ({@link AiCredentialCipher}), so a leak of one never compromises the other. Every envelope uses
 * a fresh nonce; nothing here is ever persisted, cached, or logged. */
@Component
public class AiCredentialTransportCipher {
	static final int VERSION = 1;
	static final String ALGORITHM = "AES-256-GCM";

	private final SecretKeySpec key;

	public AiCredentialTransportCipher(@Value("${SAGA_AI_CREDENTIAL_TRANSPORT_KEY:}") String transportKeyBase64) {
		this.key = AesGcmPrimitive.parseKey(transportKeyBase64);
	}

	public boolean isConfigured() { return key != null; }

	public AiCredentialEnvelope seal(String rawSecret) {
		if (key == null) throw new AiCredentialCryptoException("AI_CREDENTIAL_TRANSPORT_KEY_NOT_CONFIGURED");
		byte[] nonce = AesGcmPrimitive.freshNonce();
		String ciphertext = AesGcmPrimitive.encrypt(key, nonce, rawSecret, "AI_CREDENTIAL_TRANSPORT_ENCRYPTION_FAILED");
		return new AiCredentialEnvelope(VERSION, ALGORITHM, Base64.getEncoder().encodeToString(nonce), ciphertext);
	}
}
