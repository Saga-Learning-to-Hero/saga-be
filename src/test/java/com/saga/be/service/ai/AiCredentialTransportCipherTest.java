package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.*;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Transport-envelope sealing -- section XVII. saga-be only ever seals (never unseals; saga-ai
 * does that), so correctness here is proven by decrypting the sealed envelope with the same
 * shared AES-256-GCM primitive the seal itself used, confirming the wire format is self-consistent. */
class AiCredentialTransportCipherTest {
	private static final String KEY = Base64.getEncoder().encodeToString("T".repeat(32).getBytes());

	@Test
	void isNotConfiguredWithoutAKey() {
		assertThat(new AiCredentialTransportCipher("").isConfigured()).isFalse();
	}

	@Test
	void sealFailsClosedWhenNoTransportKeyIsConfigured() {
		AiCredentialTransportCipher cipher = new AiCredentialTransportCipher(null);
		assertThatThrownBy(() -> cipher.seal("sk-raw-key"))
				.isInstanceOf(AiCredentialCryptoException.class)
				.satisfies(ex -> assertThat(((AiCredentialCryptoException) ex).safeCode()).isEqualTo("AI_CREDENTIAL_TRANSPORT_KEY_NOT_CONFIGURED"));
	}

	@Test
	void sealedEnvelopeHasTheDocumentedMinimalShape() {
		AiCredentialEnvelope envelope = new AiCredentialTransportCipher(KEY).seal("sk-raw-key");
		assertThat(envelope.version()).isEqualTo(1);
		assertThat(envelope.algorithm()).isEqualTo("AES-256-GCM");
		assertThat(envelope.nonce()).isNotBlank();
		assertThat(envelope.ciphertext()).isNotBlank();
	}

	@Test
	void sealingTheSameSecretTwiceUsesFreshNoncesAndDifferentCiphertext() {
		AiCredentialTransportCipher cipher = new AiCredentialTransportCipher(KEY);
		AiCredentialEnvelope first = cipher.seal("same-course-key");
		AiCredentialEnvelope second = cipher.seal("same-course-key");
		assertThat(first.nonce()).isNotEqualTo(second.nonce());
		assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
	}

	@Test
	void sealedEnvelopeIsDecryptableWithTheSameSharedAesGcmPrimitive() {
		AiCredentialEnvelope envelope = new AiCredentialTransportCipher(KEY).seal("sk-raw-course-key");
		SecretKeySpec key = AesGcmPrimitive.parseKey(KEY);
		String decrypted = AesGcmPrimitive.decrypt(key, Base64.getDecoder().decode(envelope.nonce()), envelope.ciphertext(), "TEST_DECRYPT_FAILED");
		assertThat(decrypted).isEqualTo("sk-raw-course-key");
	}

	@Test
	void wrongKeyCannotOpenTheSealedEnvelope() {
		AiCredentialEnvelope envelope = new AiCredentialTransportCipher(KEY).seal("sk-raw-course-key");
		SecretKeySpec wrongKey = AesGcmPrimitive.parseKey(Base64.getEncoder().encodeToString("W".repeat(32).getBytes()));
		assertThatThrownBy(() -> AesGcmPrimitive.decrypt(wrongKey, Base64.getDecoder().decode(envelope.nonce()), envelope.ciphertext(), "TEST_DECRYPT_FAILED"))
				.isInstanceOf(AiCredentialCryptoException.class);
	}
}
