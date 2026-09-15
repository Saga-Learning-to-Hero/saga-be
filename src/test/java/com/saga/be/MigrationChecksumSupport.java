package com.saga.be;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Flyway SQL is stored with LF. Windows {@code core.autocrlf} checkouts may
 * present CRLF on the classpath; checksum locks must still match the git blob.
 */
final class MigrationChecksumSupport {

	private MigrationChecksumSupport() {
	}

	static String sha256Lf(Class<?> type, String classpath) throws Exception {
		try (InputStream in = type.getResourceAsStream(classpath)) {
			if (in == null) {
				throw new IllegalStateException("missing " + classpath);
			}
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(stripCr(in.readAllBytes()));
			return HexFormat.of().formatHex(digest);
		}
	}

	private static byte[] stripCr(byte[] raw) {
		int n = 0;
		for (byte b : raw) {
			if (b != '\r') {
				n++;
			}
		}
		if (n == raw.length) {
			return raw;
		}
		byte[] lf = new byte[n];
		int i = 0;
		for (byte b : raw) {
			if (b != '\r') {
				lf[i++] = b;
			}
		}
		return lf;
	}
}
