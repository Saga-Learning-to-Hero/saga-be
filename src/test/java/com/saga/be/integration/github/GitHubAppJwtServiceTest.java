package com.saga.be.integration.github;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.github.GitHubAppPrivateKeyParser.KeyFormat;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GitHubAppJwtServiceTest {

	private static final String APP_ID = "123456";

	private static KeyPair rsa;
	private static String pkcs1ConfigValue;
	private static String pkcs8ConfigValue;

	@BeforeAll
	static void generateKeys() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		rsa = generator.generateKeyPair();
		pkcs1ConfigValue = encodeConfig(pkcs1Pem((RSAPrivateCrtKey) rsa.getPrivate()));
		pkcs8ConfigValue = encodeConfig(pkcs8Pem(rsa.getPrivate()));
	}

	@Test
	void legacyPkcs1WrapThrowsArrayIndexOutOfBounds() throws Exception {
		byte[] pkcs1Der = pkcs1Der((RSAPrivateCrtKey) rsa.getPrivate());
		ArrayIndexOutOfBoundsException ex =
				assertThrows(ArrayIndexOutOfBoundsException.class, () -> legacyPkcs1ToPkcs8(pkcs1Der));
		assertEquals(ArrayIndexOutOfBoundsException.class, ex.getClass());
	}

	@Test
	void pkcs1GithubStylePemParsesSuccessfully() {
		PrivateKey key = new GitHubAppPrivateKeyParser().parse(pkcs1ConfigValue);
		assertEquals("RSA", key.getAlgorithm());
		assertEquals(KeyFormat.PKCS1, GitHubAppPrivateKeyParser.detectFormat(decodeConfig(pkcs1ConfigValue)));
	}

	@Test
	void pkcs8PemParsesSuccessfully() {
		PrivateKey key = new GitHubAppPrivateKeyParser().parse(pkcs8ConfigValue);
		assertEquals("RSA", key.getAlgorithm());
		assertEquals(KeyFormat.PKCS8, GitHubAppPrivateKeyParser.detectFormat(decodeConfig(pkcs8ConfigValue)));
	}

	@Test
	void malformedOuterBase64IsRejected() {
		IntegrationException ex =
				assertThrows(IntegrationException.class, () -> new GitHubAppPrivateKeyParser().parse("%%%not-base64%%%"));
		assertParseFailure(ex);
	}

	@Test
	void malformedPemIsRejected() {
		String encoded = Base64.getEncoder().encodeToString("not a pem file".getBytes(StandardCharsets.UTF_8));
		IntegrationException ex =
				assertThrows(IntegrationException.class, () -> new GitHubAppPrivateKeyParser().parse(encoded));
		assertParseFailure(ex);
	}

	@Test
	void malformedDerIsRejected() {
		String pem = "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(new byte[] {0x30, 0x03, 0x02, 0x01})
				+ "\n-----END RSA PRIVATE KEY-----\n";
		IntegrationException ex =
				assertThrows(IntegrationException.class, () -> new GitHubAppPrivateKeyParser().parse(encodeConfig(pem)));
		assertParseFailure(ex);
	}

	@Test
	void nonRsaKeyIsRejected() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		String encoded = encodeConfig(pkcs8Pem(generator.generateKeyPair().getPrivate()));
		IntegrationException ex =
				assertThrows(IntegrationException.class, () -> new GitHubAppPrivateKeyParser().parse(encoded));
		assertParseFailure(ex);
	}

	@Test
	void validPkcs1CreatesGithubAppJwt() throws Exception {
		GitHubAppJwtService service = new GitHubAppJwtService(configured(pkcs1ConfigValue));
		String jwt = assertDoesNotThrow(service::createJwt);
		assertJwtShapeAndSignature(jwt, rsa);
	}

	@Test
	void validPkcs8CreatesGithubAppJwt() throws Exception {
		GitHubAppJwtService service = new GitHubAppJwtService(configured(pkcs8ConfigValue));
		String jwt = assertDoesNotThrow(service::createJwt);
		assertJwtShapeAndSignature(jwt, rsa);
	}

	@Test
	void parseFailureDoesNotLeakCryptoDetails() {
		IntegrationException ex =
				assertThrows(IntegrationException.class, () -> new GitHubAppPrivateKeyParser().parse("%%%not-base64%%%"));
		String message = ex.getMessage();
		assertFalse(message.toLowerCase().contains("pem"));
		assertFalse(message.toLowerCase().contains("der"));
		assertFalse(message.toLowerCase().contains("exception"));
		assertFalse(message.contains("BEGIN"));
	}

	private static void assertParseFailure(IntegrationException ex) {
		assertEquals(IntegrationErrorCode.INTEGRATION_UNAVAILABLE, ex.getCode());
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
		assertEquals("GitHub App private key could not be parsed.", ex.getMessage());
	}

	private static void assertJwtShapeAndSignature(String jwt, KeyPair pair) throws Exception {
		String[] parts = jwt.split("\\.");
		assertEquals(3, parts.length);
		String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
		assertTrue(header.contains("\"alg\":\"RS256\""));
		assertTrue(header.contains("\"typ\":\"JWT\""));
		assertTrue(payload.contains("\"iss\":\"" + APP_ID + "\""));
		assertTrue(payload.contains("\"iat\":"));
		assertTrue(payload.contains("\"exp\":"));
		Signature signature = Signature.getInstance("SHA256withRSA");
		signature.initVerify(pair.getPublic());
		signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
		assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[2])));
		assertNotNull(parts[2]);
	}

	private static IntegrationProperties configured(String privateKeyBase64) {
		IntegrationProperties properties = new IntegrationProperties();
		properties.getGithub().setEnabled(true);
		properties.getGithub().setAppId(APP_ID);
		properties.getGithub().setClientId("Iv1.test");
		properties.getGithub().setClientSecret("test-secret");
		properties.getGithub().setPrivateKeyBase64(privateKeyBase64);
		properties.getGithub().setWebhookSecret("webhook-secret");
		return properties;
	}

	private static String pkcs1Pem(RSAPrivateCrtKey key) throws Exception {
		return wrapPem("RSA PRIVATE KEY", pkcs1Der(key));
	}

	private static byte[] pkcs1Der(RSAPrivateCrtKey key) throws Exception {
		return new RSAPrivateKey(
						key.getModulus(),
						key.getPublicExponent(),
						key.getPrivateExponent(),
						key.getPrimeP(),
						key.getPrimeQ(),
						key.getPrimeExponentP(),
						key.getPrimeExponentQ(),
						key.getCrtCoefficient())
				.getEncoded();
	}

	private static String pkcs8Pem(PrivateKey key) {
		return wrapPem("PRIVATE KEY", key.getEncoded());
	}

	private static String wrapPem(String type, byte[] der) {
		String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
		return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
	}

	private static String encodeConfig(String pem) {
		return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
	}

	private static String decodeConfig(String encoded) {
		return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
	}

	/**
	 * Exact previous wrap from {@code GitHubAppJwtService#pkcs1ToPkcs8}. Kept only to
	 * document the production failure: {@link ArrayIndexOutOfBoundsException}.
	 */
	private static byte[] legacyPkcs1ToPkcs8(byte[] pkcs1) {
		byte[] prefix = new byte[] {
			0x30,
			(byte) 0x82,
			0x00,
			0x00,
			0x02,
			0x01,
			0x00,
			0x30,
			0x0d,
			0x06,
			0x09,
			0x2a,
			(byte) 0x86,
			0x48,
			(byte) 0x86,
			(byte) 0xf7,
			0x0d,
			0x01,
			0x01,
			0x01,
			0x05,
			0x00,
			0x04,
			(byte) 0x82,
			0x00,
			0x00
		};
		int bodyLen = pkcs1.length;
		byte[] result = new byte[26 + bodyLen];
		System.arraycopy(prefix, 0, result, 0, prefix.length);
		result[2] = (byte) 0x82;
		int seqLen = result.length - 4;
		result[3] = (byte) ((seqLen >> 8) & 0xff);
		result[4] = (byte) (seqLen & 0xff);
		result[24] = (byte) 0x82;
		result[25] = (byte) ((bodyLen >> 8) & 0xff);
		result[26] = (byte) (bodyLen & 0xff);
		System.arraycopy(pkcs1, 0, result, 27, bodyLen);
		return result;
	}
}
