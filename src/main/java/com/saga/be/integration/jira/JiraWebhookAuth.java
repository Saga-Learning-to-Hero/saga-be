package com.saga.be.integration.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies inbound Jira Cloud webhooks.
 *
 * <ul>
 *   <li>OAuth dynamic webhooks: {@code Authorization: Bearer <JWT>} signed with the app client secret
 *   <li>Optional manual admin webhooks: {@code X-Hub-Signature} HMAC with dedicated webhook secret
 * </ul>
 */
public final class JiraWebhookAuth {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private JiraWebhookAuth() {}

	/**
	 * @param oauthClientSecret Atlassian OAuth app client secret (JWT HS256 key for dynamic webhooks)
	 * @param manualWebhookSecret dedicated {@code SAGA_JIRA_WEBHOOK_SECRET} for admin HMAC webhooks
	 */
	public static boolean accepts(
			byte[] body,
			String authorizationHeader,
			String xHubSignatureHeader,
			String oauthClientSecret,
			String manualWebhookSecret) {
		String bearer = bearerToken(authorizationHeader);
		if (bearer != null) {
			return verifyBearerJwt(bearer, oauthClientSecret);
		}
		if (manualWebhookSecret != null && !manualWebhookSecret.isBlank()) {
			return JiraWebhookSignature.matches(body, manualWebhookSecret, xHubSignatureHeader);
		}
		// Fail closed when OAuth app credentials exist (production dynamic webhooks expected).
		if (oauthClientSecret != null && !oauthClientSecret.isBlank()) {
			return false;
		}
		// Local/dev without Jira OAuth configured.
		return true;
	}

	static String bearerToken(String authorizationHeader) {
		if (authorizationHeader == null || authorizationHeader.isBlank()) {
			return null;
		}
		String trimmed = authorizationHeader.trim();
		if (trimmed.length() < 8 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return null;
		}
		String token = trimmed.substring(7).trim();
		return token.isEmpty() ? null : token;
	}

	/**
	 * Verifies an Atlassian OAuth dynamic-webhook bearer JWT.
	 *
	 * <p>Allowlist: {@code HS256}/{@code HS512} only ({@code none} and other algs rejected). Signature
	 * compared with {@link MessageDigest#isEqual}. {@code exp} is required; tokens are rejected when
	 * {@code now >= exp} (RFC 7519).
	 */
	static boolean verifyBearerJwt(String jwt, String clientSecret) {
		if (jwt == null || clientSecret == null || clientSecret.isBlank()) {
			return false;
		}
		String[] parts = jwt.split("\\.", -1);
		if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
			return false;
		}
		try {
			byte[] headerJson = base64UrlDecode(parts[0]);
			JsonNode header = MAPPER.readTree(headerJson);
			String alg = header.path("alg").asText("");
			// Explicit reject of alg=none and any non-HMAC allowlist entry.
			if ("none".equalsIgnoreCase(alg)
					|| (!"HS256".equalsIgnoreCase(alg) && !"HS512".equalsIgnoreCase(alg))) {
				return false;
			}
			String signingInput = parts[0] + "." + parts[1];
			String macAlg = "HS512".equalsIgnoreCase(alg) ? "HmacSHA512" : "HmacSHA256";
			byte[] expected = hmac(macAlg, signingInput.getBytes(StandardCharsets.US_ASCII), clientSecret);
			byte[] provided = base64UrlDecode(parts[2]);
			if (!MessageDigest.isEqual(expected, provided)) {
				return false;
			}
			JsonNode claims = MAPPER.readTree(base64UrlDecode(parts[1]));
			if (!claims.has("exp") || !claims.get("exp").canConvertToLong()) {
				return false;
			}
			long exp = claims.get("exp").asLong();
			// RFC 7519: current time MUST be before exp.
			return Instant.now().getEpochSecond() < exp;
		} catch (Exception ex) {
			return false;
		}
	}

	private static byte[] hmac(String algorithm, byte[] input, String secret)
			throws NoSuchAlgorithmException, InvalidKeyException {
		Mac mac = Mac.getInstance(algorithm);
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
		return mac.doFinal(input);
	}

	private static byte[] base64UrlDecode(String value) {
		return Base64.getUrlDecoder().decode(value);
	}
}
