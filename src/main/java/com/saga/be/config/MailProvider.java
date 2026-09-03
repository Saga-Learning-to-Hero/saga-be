package com.saga.be.config;

public enum MailProvider {
	SMTP,
	GMAIL_API;

	public static MailProvider from(String raw) {
		if (raw == null || raw.isBlank()) {
			return SMTP;
		}
		String normalized = raw.trim().toLowerCase().replace('_', '-');
		if (normalized.equals("smtp")) {
			return SMTP;
		}
		if (normalized.equals("gmail-api")) {
			return GMAIL_API;
		}
		throw new IllegalArgumentException("saga.mail.provider must be smtp or gmail-api.");
	}
}
