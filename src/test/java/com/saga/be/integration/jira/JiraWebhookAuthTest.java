package com.saga.be.integration.jira;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class JiraWebhookAuthTest {

	private static final String SECRET = "oauth-client-secret";

	@Test
	void validBearerJwtHs256IsAccepted() throws Exception {
		String jwt = signedJwt("HS256", "{\"exp\":9999999999}");
		assertTrue(JiraWebhookAuth.accepts(new byte[] {1}, "Bearer " + jwt, null, SECRET, null));
	}

	@Test
	void validBearerJwtHs512IsAccepted() throws Exception {
		String jwt = signedJwt("HS512", "{\"exp\":9999999999}");
		assertTrue(JiraWebhookAuth.accepts(new byte[] {1}, "Bearer " + jwt, null, SECRET, null));
	}

	@Test
	void expiredJwtIsRejected() throws Exception {
		String jwt = signedJwt("HS256", "{\"exp\":1}");
		assertFalse(JiraWebhookAuth.accepts(new byte[] {1}, "Bearer " + jwt, null, SECRET, null));
	}

	@Test
	void expEqualToNowIsRejected() throws Exception {
		long now = Instant.now().getEpochSecond();
		String jwt = signedJwt("HS256", "{\"exp\":" + now + "}");
		assertFalse(JiraWebhookAuth.accepts(new byte[] {1}, "Bearer " + jwt, null, SECRET, null));
	}

	@Test
	void missingExpClaimIsRejected() throws Exception {
		String jwt = signedJwt("HS256", "{\"iss\":\"atlassian\"}");
		assertFalse(JiraWebhookAuth.accepts(new byte[] {1}, "Bearer " + jwt, null, SECRET, null));
	}

	@Test
	void invalidJwtSignatureIsRejected() throws Exception {
		String jwt = signedJwtWithSecret("other-secret", "HS256", "{\"exp\":9999999999}");
		assertFalse(JiraWebhookAuth.accepts(new byte[] {1}, "Bearer " + jwt, null, SECRET, null));
	}

	@Test
	void algNoneIsRejected() throws Exception {
		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
		String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
		String payload = enc.encodeToString("{\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));
		// Non-empty signature segment so rejection is due to alg allowlist, not structure.
		String jwt = header + "." + payload + "." + enc.encodeToString(new byte[] {1, 2, 3});
		assertFalse(JiraWebhookAuth.accepts(new byte[] {1}, "Bearer " + jwt, null, SECRET, null));
		assertFalse(JiraWebhookAuth.verifyBearerJwt(jwt, SECRET));
	}

	@Test
	void unsupportedAlgIsRejected() throws Exception {
		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
		String header = enc.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
		String payload = enc.encodeToString("{\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));
		// Forged/unsigned-looking token; must not verify even if signature bytes present.
		String jwt = header + "." + payload + "." + enc.encodeToString(new byte[32]);
		assertFalse(JiraWebhookAuth.verifyBearerJwt(jwt, SECRET));
	}

	@Test
	void malformedTokenIsRejected() {
		assertFalse(JiraWebhookAuth.verifyBearerJwt("not-a-jwt", SECRET));
		assertFalse(JiraWebhookAuth.verifyBearerJwt("only.two", SECRET));
		assertFalse(JiraWebhookAuth.verifyBearerJwt("a.b.c.d", SECRET));
		assertFalse(JiraWebhookAuth.verifyBearerJwt("...", SECRET));
		assertFalse(JiraWebhookAuth.verifyBearerJwt("", SECRET));
		assertFalse(JiraWebhookAuth.accepts(new byte[] {1}, "Bearer not.a.jwt", null, SECRET, null));
	}

	@Test
	void missingAuthRejectedWhenClientSecretConfigured() {
		assertFalse(JiraWebhookAuth.accepts(new byte[] {1}, null, null, SECRET, null));
		assertFalse(JiraWebhookAuth.accepts(new byte[] {1}, "", null, SECRET, null));
		assertFalse(JiraWebhookAuth.accepts(new byte[] {1}, "Basic abc", null, SECRET, null));
		assertFalse(JiraWebhookAuth.accepts(new byte[] {1}, "Bearer ", null, SECRET, null));
	}

	@Test
	void manualHmacFallbackAcceptedWhenNoBearer() {
		byte[] body = "{\"webhookEvent\":\"jira:issue_updated\"}".getBytes(StandardCharsets.UTF_8);
		String webhookSecret = "dedicated-jira-webhook-secret";
		String header = "sha256=" + JiraWebhookSignature.hmacSha256Hex(body, webhookSecret);
		assertTrue(JiraWebhookAuth.accepts(body, null, header, SECRET, webhookSecret));
		assertFalse(JiraWebhookAuth.accepts(body, null, null, SECRET, webhookSecret));
	}

	@Test
	void blankSecretsPreserveLocalDevAccept() {
		assertTrue(JiraWebhookAuth.accepts(new byte[] {1}, null, null, null, null));
		assertTrue(JiraWebhookAuth.accepts(new byte[] {1}, null, null, "", ""));
	}

	private static String signedJwt(String alg, String payloadJson) throws Exception {
		return signedJwtWithSecret(SECRET, alg, payloadJson);
	}

	private static String signedJwtWithSecret(String secret, String alg, String payloadJson) throws Exception {
		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
		String headerJson = "{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}";
		String header = enc.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
		String payload = enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
		String signingInput = header + "." + payload;
		String macAlg = "HS512".equalsIgnoreCase(alg) ? "HmacSHA512" : "HmacSHA256";
		Mac mac = Mac.getInstance(macAlg);
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), macAlg));
		String sig = enc.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
		return signingInput + "." + sig;
	}
}
