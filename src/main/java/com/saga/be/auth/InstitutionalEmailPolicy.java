package com.saga.be.auth;

import com.saga.be.config.AuthProperties;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InstitutionalEmailPolicy {

	private static final Set<String> MINIMUM_INSTITUTIONAL_DOMAINS = Set.of("fpt.edu.vn", "fe.edu.vn");

	private final AuthProperties properties;

	public InstitutionalEmailPolicy(AuthProperties properties) {
		this.properties = properties;
	}

	public boolean isInstitutionalEmail(String email) {
		String domain = domainOf(email);
		return domain != null && institutionalDomains().contains(domain);
	}

	public Set<String> institutionalDomains() {
		Set<String> domains = new LinkedHashSet<>(MINIMUM_INSTITUTIONAL_DOMAINS);
		for (String configured : properties.getGoogle().getAllowedHostedDomains()) {
			if (StringUtils.hasText(configured)) {
				domains.add(configured.trim().toLowerCase(Locale.ROOT));
			}
		}
		return Set.copyOf(domains);
	}

	static String domainOf(String email) {
		if (!StringUtils.hasText(email)) {
			return null;
		}
		String normalized = email.trim().toLowerCase(Locale.ROOT);
		int at = normalized.lastIndexOf('@');
		if (at <= 0 || at == normalized.length() - 1) {
			return null;
		}
		return normalized.substring(at + 1);
	}
}
