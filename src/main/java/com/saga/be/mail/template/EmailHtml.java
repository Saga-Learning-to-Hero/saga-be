package com.saga.be.mail.template;

import java.util.Locale;

final class EmailHtml {

	private EmailHtml() {}

	static String escape(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder(value.length() + 16);
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '"' -> out.append("&quot;");
				case '\'' -> out.append("&#39;");
				default -> out.append(c);
			}
		}
		return out.toString();
	}

	static String blankTo(String value, String fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		return value.trim();
	}

	static String safeHttpUrl(String url) {
		if (url == null || url.isBlank()) {
			return "";
		}
		String trimmed = url.trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
			return "";
		}
		return escape(trimmed);
	}
}
