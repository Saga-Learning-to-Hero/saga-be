package com.saga.be.integration.github;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class GitHubWebhookSignature {

	private GitHubWebhookSignature() {}

	public static boolean matches(byte[] payload, String secret, String header) {
		if (payload == null || secret == null || header == null || header.isBlank()) {
			return false;
		}
		String expected = "sha256=" + hmacSha256Hex(payload, secret);
		byte[] left = expected.getBytes(StandardCharsets.US_ASCII);
		byte[] right = header.trim().getBytes(StandardCharsets.US_ASCII);
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
