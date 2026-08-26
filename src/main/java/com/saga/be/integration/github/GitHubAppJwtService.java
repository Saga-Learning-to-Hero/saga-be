package com.saga.be.integration.github;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Profile("!test")
public class GitHubAppJwtService {

	private final IntegrationProperties properties;

	public GitHubAppJwtService(IntegrationProperties properties) {
		this.properties = properties;
	}

	public String createJwt() {
		if (!properties.getGithub().isConfigured()) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "GitHub App is not configured.");
		}
		try {
			Instant now = Instant.now();
			String header = base64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
			String payload = base64("{\"iat\":"
					+ (now.getEpochSecond() - 60)
					+ ",\"exp\":"
					+ (now.getEpochSecond() + 540)
					+ ",\"iss\":\""
					+ properties.getGithub().getAppId()
					+ "\"}");
			String signingInput = header + "." + payload;
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(privateKey());
			signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
			return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
		} catch (Exception ex) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "GitHub App JWT could not be created.");
		}
	}

	private PrivateKey privateKey() throws Exception {
		String raw = properties.getGithub().getPrivateKeyBase64();
		String pem = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
		if (!pem.contains("BEGIN")) {
			pem = raw;
		}
		String normalized = pem.replace("-----BEGIN RSA PRIVATE KEY-----", "")
				.replace("-----END RSA PRIVATE KEY-----", "")
				.replace("-----BEGIN PRIVATE KEY-----", "")
				.replace("-----END PRIVATE KEY-----", "")
				.replaceAll("\\s", "");
		byte[] der = Base64.getDecoder().decode(normalized);
		if (pem.contains("RSA PRIVATE KEY")) {
			der = pkcs1ToPkcs8(der);
		}
		return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
	}

	private static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
		// Minimal PKCS#1 -> PKCS#8 wrap for RSA.
		byte[] prefix = new byte[] {
			0x30, (byte) 0x82, 0x00, 0x00, 0x02, 0x01, 0x00, 0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
			(byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00, 0x04, (byte) 0x82, 0x00, 0x00
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

	private static String base64(String json) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	public boolean hasKey() {
		return StringUtils.hasText(properties.getGithub().getPrivateKeyBase64());
	}
}
