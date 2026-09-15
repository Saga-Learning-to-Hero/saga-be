package com.saga.be.push;

import java.util.Locale;

public final class FcmFailureCodes {

	public static final String UNREGISTERED = "UNREGISTERED";
	public static final String INVALID_TOKEN = "INVALID_TOKEN";
	public static final String SENDER_ID_MISMATCH = "SENDER_ID_MISMATCH";
	public static final String UNAVAILABLE = "UNAVAILABLE";
	public static final String INTERNAL = "INTERNAL";
	public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
	public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
	public static final String UNKNOWN = "UNKNOWN";
	public static final String FCM_DISABLED = "FCM_DISABLED";
	public static final String OWNERSHIP_MISMATCH = "OWNERSHIP_MISMATCH";
	public static final String INSTALLATION_INACTIVE = "INSTALLATION_INACTIVE";
	public static final String TOKEN_MISSING = "TOKEN_MISSING";
	public static final String SKIPPED = "SKIPPED";

	private FcmFailureCodes() {}

	public static String truncate(String code) {
		if (code == null || code.isBlank()) {
			return UNKNOWN;
		}
		String trimmed = code.trim();
		return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
	}

	public static String safeDetail(Throwable error) {
		if (error == null) {
			return "FCM send failed.";
		}
		String haystack = combinedMessage(error);
		if (looksLikeSecret(haystack)) {
			return "FCM send failed.";
		}
		String message = error.getMessage();
		if (message == null || message.isBlank()) {
			return "FCM send failed.";
		}
		String trimmed = message.trim();
		return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 180);
	}

	static boolean looksLikeSecret(String haystack) {
		if (haystack == null || haystack.isBlank()) {
			return false;
		}
		String lower = haystack.toLowerCase(Locale.ROOT);
		return lower.contains("private_key")
				|| lower.contains("begin private")
				|| lower.contains("private-key")
				|| lower.contains("client_email")
				|| lower.contains("service-account")
				|| lower.contains("refresh_token")
				|| lower.contains("access_token");
	}

	private static String combinedMessage(Throwable error) {
		StringBuilder builder = new StringBuilder();
		Throwable current = error;
		int depth = 0;
		while (current != null && depth < 6) {
			if (current.getMessage() != null) {
				builder.append(' ').append(current.getMessage());
			}
			current = current.getCause();
			depth++;
		}
		return builder.toString();
	}
}
