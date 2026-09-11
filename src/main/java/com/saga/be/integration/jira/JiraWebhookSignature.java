package com.saga.be.integration.jira;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Jira Cloud manual webhook signature verification.
 * Header: {@code X-Hub-Signature: <method>=<signature>} (e.g. {@code sha256=<hex>}).
 * Distinct from GitHub's {@code X-Hub-Signature-256}.
 */
public final class JiraWebhookSignature {

	private JiraWebhookSignature() {}

	/**
	 * When {@code secret} is blank, verification is skipped (dev/local). When configured, requires a
	 * valid {@code X-Hub-Signature} sha256 HMAC over the raw body.
	 */
	public static boolean accepts(byte[] payload, String secret, String xHubSignatureHeader) {
		if (secret == null || secret.isBlank()) {
			return true;
		}
		return matches(payload, secret, xHubSignatureHeader);
	}

	public static boolean matches(byte[] payload, String secret, String xHubSignatureHeader) {
		if (payload == null || secret == null || secret.isBlank()) {
			return false;
		}
		if (xHubSignatureHeader == null || xHubSignatureHeader.isBlank()) {
			return false;
		}
		String trimmed = xHubSignatureHeader.trim();
		int sep = trimmed.indexOf('=');
		if (sep <= 0 || sep >= trimmed.length() - 1) {
			return false;
		}
		String method = trimmed.substring(0, sep).trim().toLowerCase(Locale.ROOT);
		String provided = trimmed.substring(sep + 1).trim();
		if (!"sha256".equals(method) || provided.isEmpty()) {
			return false;
		}
		String expectedHex = hmacSha256Hex(payload, secret);
		byte[] left = expectedHex.getBytes(StandardCharsets.US_ASCII);
		byte[] right = provided.getBytes(StandardCharsets.US_ASCII);
		return left.length == right.length && MessageDigest.isEqual(left, right);
	}

	public static String hmacSha256Hex(byte[] payload, String secret) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(payload));
		} catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
		}
	}
}
