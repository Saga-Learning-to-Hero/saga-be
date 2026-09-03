package com.saga.be.mail;

import java.util.Locale;

public final class EmailFailureCodes {

	public static final String MAIL_DISABLED = "MAIL_DISABLED";
	public static final String SMTP_AUTH = "SMTP_AUTH";
	public static final String SMTP_TIMEOUT = "SMTP_TIMEOUT";
	public static final String INVALID_ADDRESS = "INVALID_ADDRESS";
	public static final String SMTP_ERROR = "SMTP_ERROR";
	public static final String PROVIDER_AUTH = "PROVIDER_AUTH";
	public static final String PROVIDER_TIMEOUT = "PROVIDER_TIMEOUT";
	public static final String PROVIDER_ERROR = "PROVIDER_ERROR";
	public static final String UNKNOWN = "UNKNOWN";

	private EmailFailureCodes() {}

	public static String from(Throwable error) {
		if (error instanceof EmailSendException send) {
			return truncate(send.getFailureCode());
		}
		if (error instanceof org.springframework.web.client.RestClientResponseException rest) {
			return fromHttpStatus(rest.getStatusCode().value());
		}
		if (error instanceof org.springframework.web.client.ResourceAccessException) {
			return PROVIDER_TIMEOUT;
		}
		String haystack = combinedMessage(error).toLowerCase(Locale.ROOT);
		if (containsSecret(haystack)) {
			if (haystack.contains("auth") || haystack.contains("password") || haystack.contains("credential")) {
				return SMTP_AUTH;
			}
			return SMTP_ERROR;
		}
		if (haystack.contains("timeout") || haystack.contains("timed out")) {
			return SMTP_TIMEOUT;
		}
		if (haystack.contains("address") || haystack.contains("recipient") || haystack.contains("invalid")) {
			return INVALID_ADDRESS;
		}
		if (haystack.contains("auth") || haystack.contains("535") || haystack.contains("534")) {
			return SMTP_AUTH;
		}
		if (error instanceof java.net.SocketTimeoutException) {
			return SMTP_TIMEOUT;
		}
		return SMTP_ERROR;
	}

	public static String fromHttpStatus(int status) {
		if (status == 401 || status == 403) {
			return PROVIDER_AUTH;
		}
		if (status == 408 || status == 504) {
			return PROVIDER_TIMEOUT;
		}
		if (status == 400 || status == 422) {
			return INVALID_ADDRESS;
		}
		return PROVIDER_ERROR;
	}

	public static String truncate(String code) {
		if (code == null || code.isBlank()) {
			return UNKNOWN;
		}
		String trimmed = code.trim();
		return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
	}

	static boolean containsSecret(String haystack) {
		return haystack.contains("password")
				|| haystack.contains("secret")
				|| haystack.contains("apikey")
				|| haystack.contains("api_key")
				|| haystack.contains("refresh_token")
				|| haystack.contains("access_token")
				|| haystack.contains("credential");
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
