package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Real AES-256-GCM roundtrip/tamper/wrong-key coverage for the at-rest credential cipher --
 * section XVII's mandatory security tests. */
class AiCredentialCipherTest {
	private static final String KEY_A = Base64.getEncoder().encodeToString("A".repeat(32).getBytes());
	private static final String KEY_B = Base64.getEncoder().encodeToString("B".repeat(32).getBytes());

	@Test
	void isNotConfiguredWhenNoKeyIsProvided() {
		assertThat(new AiCredentialCipher("").isConfigured()).isFalse();
		assertThat(new AiCredentialCipher(null).isConfigured()).isFalse();
	}

	@Test
	void isNotConfiguredWhenTheKeyIsNotExactlyThirtyTwoBytes() {
		String tooShort = Base64.getEncoder().encodeToString("short-key".getBytes());
		assertThat(new AiCredentialCipher(tooShort).isConfigured()).isFalse();
	}

	@Test
	void encryptDecryptRoundtripRecoversTheExactPlaintext() {
		AiCredentialCipher cipher = new AiCredentialCipher(KEY_A);
		var encrypted = cipher.encrypt("sk-raw-provider-key-12345");
		assertThat(cipher.decrypt(encrypted.ciphertextBase64(), encrypted.nonceBase64(), encrypted.keyVersion())).isEqualTo("sk-raw-provider-key-12345");
	}

	@Test
	void sameSecretEncryptedTwiceProducesDifferentCiphertextViaFreshNonces() {
		AiCredentialCipher cipher = new AiCredentialCipher(KEY_A);
		var first = cipher.encrypt("same-secret");
		var second = cipher.encrypt("same-secret");
		assertThat(first.nonceBase64()).isNotEqualTo(second.nonceBase64());
		assertThat(first.ciphertextBase64()).isNotEqualTo(second.ciphertextBase64());
	}

	@Test
	void tamperedCiphertextFailsClosed() {
		AiCredentialCipher cipher = new AiCredentialCipher(KEY_A);
		var encrypted = cipher.encrypt("sk-raw-provider-key");
		byte[] raw = Base64.getDecoder().decode(encrypted.ciphertextBase64());
		raw[0] ^= 0xFF;
		String tampered = Base64.getEncoder().encodeToString(raw);
		assertThatThrownBy(() -> cipher.decrypt(tampered, encrypted.nonceBase64(), encrypted.keyVersion()))
				.isInstanceOf(AiCredentialCryptoException.class);
	}

	@Test
	void wrongKeyFailsClosed() {
		var encrypted = new AiCredentialCipher(KEY_A).encrypt("sk-raw-provider-key");
		AiCredentialCipher wrongCipher = new AiCredentialCipher(KEY_B);
		assertThatThrownBy(() -> wrongCipher.decrypt(encrypted.ciphertextBase64(), encrypted.nonceBase64(), encrypted.keyVersion()))
				.isInstanceOf(AiCredentialCryptoException.class);
	}

	@Test
	void encryptFailsClosedWhenNoMasterKeyIsConfigured() {
		AiCredentialCipher cipher = new AiCredentialCipher(null);
		assertThatThrownBy(() -> cipher.encrypt("sk-raw-provider-key"))
				.isInstanceOf(AiCredentialCryptoException.class)
				.satisfies(ex -> assertThat(((AiCredentialCryptoException) ex).safeCode()).isEqualTo("AI_CREDENTIAL_MASTER_KEY_NOT_CONFIGURED"));
	}

	@Test
	void unsupportedKeyVersionFailsClosed() {
		AiCredentialCipher cipher = new AiCredentialCipher(KEY_A);
		var encrypted = cipher.encrypt("sk-raw-provider-key");
		assertThatThrownBy(() -> cipher.decrypt(encrypted.ciphertextBase64(), encrypted.nonceBase64(), 99))
				.isInstanceOf(AiCredentialCryptoException.class);
	}
}
