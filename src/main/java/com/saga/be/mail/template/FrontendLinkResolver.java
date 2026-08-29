package com.saga.be.mail.template;

import com.saga.be.config.AuthProperties;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

final class FrontendLinkResolver {

	private final AuthProperties auth;

	FrontendLinkResolver(AuthProperties auth) {
		this.auth = auth;
	}

	String origin() {
		List<String> origins = auth == null ? List.of() : auth.getFrontendOrigins();
		String base = origins == null || origins.isEmpty() || !StringUtils.hasText(origins.getFirst())
				? "http://localhost:3000"
				: origins.getFirst().trim();
		return trimSlash(base);
	}

	String dashboardUrl() {
		String configured = auth == null ? "" : auth.getGoogle().getSuccessUrl();
		if (isAbsoluteHttp(configured)) {
			return configured.trim();
		}
		return origin() + "/dashboard";
	}

	String loginUrl() {
		String configured = auth == null ? "" : auth.getGoogle().getFailureUrl();
		if (isAbsoluteHttp(configured)) {
			return configured.trim();
		}
		return origin() + "/login";
	}

	String registerUrl() {
		return origin() + "/register";
	}

	private static boolean isAbsoluteHttp(String url) {
		if (!StringUtils.hasText(url)) {
			return false;
		}
		String lower = url.trim().toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://");
	}

	private static String trimSlash(String value) {
		if (value.endsWith("/")) {
			return value.substring(0, value.length() - 1);
		}
		return value;
	}
}
