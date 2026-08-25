package com.saga.be.auth;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GoogleRoleResolver {

	public static final Pattern STUDENT_FPT_EMAIL =
			Pattern.compile("^[A-Za-z]+\\d{6}@fpt\\.edu\\.vn$", Pattern.CASE_INSENSITIVE);

	public enum Outcome {
		STUDENT,
		LECTURER,
		REJECT_UNVERIFIED,
		REJECT_DOMAIN,
		REJECT_INELIGIBLE
	}

	public Outcome resolve(String email, boolean emailVerified, String hostedDomain, Set<String> allowedHostedDomains) {
		if (!emailVerified || !StringUtils.hasText(email)) {
			return Outcome.REJECT_UNVERIFIED;
		}
		String normalized = email.trim().toLowerCase(Locale.ROOT);
		if (!isInstitutionalGoogle(normalized, hostedDomain, allowedHostedDomains)) {
			return Outcome.REJECT_DOMAIN;
		}
		if (normalized.endsWith("@fe.edu.vn")) {
			return Outcome.LECTURER;
		}
		if (STUDENT_FPT_EMAIL.matcher(normalized).matches()) {
			return Outcome.STUDENT;
		}
		if (normalized.endsWith("@fpt.edu.vn")) {
			return Outcome.LECTURER;
		}
		return Outcome.REJECT_INELIGIBLE;
	}

	public boolean isInstitutionalGoogle(String normalizedEmail, String hostedDomain, Set<String> allowedHostedDomains) {
		if (allowedHostedDomains == null || allowedHostedDomains.isEmpty()) {
			return false;
		}
		Set<String> allowed = allowedHostedDomains.stream()
				.filter(StringUtils::hasText)
				.map(d -> d.trim().toLowerCase(Locale.ROOT))
				.collect(java.util.stream.Collectors.toSet());
		String emailDomain = domainOf(normalizedEmail);
		if (!allowed.contains(emailDomain)) {
			return false;
		}
		if (!StringUtils.hasText(hostedDomain)) {
			return false;
		}
		return allowed.contains(hostedDomain.trim().toLowerCase(Locale.ROOT));
	}

	private static String domainOf(String email) {
		int at = email.lastIndexOf('@');
		if (at < 0 || at == email.length() - 1) {
			return "";
		}
		return email.substring(at + 1);
	}
}
