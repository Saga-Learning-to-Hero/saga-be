package com.saga.be.integration.github;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
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
	private final GitHubAppPrivateKeyParser keyParser;

	public GitHubAppJwtService(IntegrationProperties properties) {
		this.properties = properties;
		this.keyParser = new GitHubAppPrivateKeyParser();
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
			PrivateKey privateKey = keyParser.parse(properties.getGithub().getPrivateKeyBase64());
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(privateKey);
			signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
			return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
		} catch (IntegrationException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "GitHub App JWT could not be created.");
		}
	}

	private static String base64(String json) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	public boolean hasKey() {
		return StringUtils.hasText(properties.getGithub().getPrivateKeyBase64());
	}
}
