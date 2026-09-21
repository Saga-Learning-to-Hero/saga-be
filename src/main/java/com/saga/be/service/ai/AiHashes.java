package com.saga.be.service.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class AiHashes {
	private AiHashes() {}
	static String sha256(String value) {
		try {
			byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder(64);
			for (byte b : bytes) out.append(String.format("%02x", b));
			return out.toString();
		} catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
	}
}
