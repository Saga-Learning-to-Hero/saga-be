package com.saga.be.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Authoritative formal student code: exactly 2 letters + 6 digits.
 * For FPT institutional emails, only a trailing local-part suffix matching that form is accepted.
 */
public final class FormalFptStudentCode {

	/** Persisted / roster form after normalization. */
	public static final Pattern FORMAL_CODE = Pattern.compile("^[A-Za-z]{2}[0-9]{6}$");

	/** Trailing suffix of an email local-part. */
	private static final Pattern TRAILING_FORMAL_CODE =
			Pattern.compile("([A-Za-z]{2}[0-9]{6})$", Pattern.CASE_INSENSITIVE);

	private FormalFptStudentCode() {}

	public static boolean isFormalCode(String value) {
		return StringUtils.hasText(value) && FORMAL_CODE.matcher(value.trim()).matches();
	}

	/** @return uppercase formal code, or empty if value is not exactly 2 letters + 6 digits */
	public static Optional<String> normalizeFormalCode(String value) {
		if (!StringUtils.hasText(value)) {
			return Optional.empty();
		}
		String trimmed = value.trim();
		if (!FORMAL_CODE.matcher(trimmed).matches()) {
			return Optional.empty();
		}
		return Optional.of(trimmed.toUpperCase(Locale.ROOT));
	}

	/**
	 * @return uppercase trailing formal code from email local-part when present; otherwise empty
	 */
	public static Optional<String> extractFromEmail(String email) {
		if (!StringUtils.hasText(email)) {
			return Optional.empty();
		}
		String normalized = email.trim().toLowerCase(Locale.ROOT);
		int at = normalized.lastIndexOf('@');
		if (at <= 0) {
			return Optional.empty();
		}
		String localPart = normalized.substring(0, at);
		Matcher matcher = TRAILING_FORMAL_CODE.matcher(localPart);
		if (!matcher.find()) {
			return Optional.empty();
		}
		return Optional.of(matcher.group(1).toUpperCase(Locale.ROOT));
	}
}
