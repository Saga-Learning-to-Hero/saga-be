package com.saga.be.integration.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class Pkce {

	private static final SecureRandom RANDOM = new SecureRandom();

	private Pkce() {}

	public static String newVerifier() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return base64Url(bytes);
	}

	public static String challengeS256(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return base64Url(digest);
		} catch (Exception ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	public static String newState() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return base64Url(bytes);
	}

	private static String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
