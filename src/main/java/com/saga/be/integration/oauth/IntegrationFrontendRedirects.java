package com.saga.be.integration.oauth;

import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

public final class IntegrationFrontendRedirects {

	private IntegrationFrontendRedirects() {}

	public static String safeReturnPath(String returnPath) {
		if (returnPath == null || returnPath.isBlank()) {
			return null;
		}
		String trimmed = returnPath.trim();
		if (!trimmed.startsWith("/") || trimmed.startsWith("//") || trimmed.contains("://") || trimmed.contains("\\")) {
			return null;
		}
		return trimmed;
	}

	public static String successLocation(String successUrl, String returnPath) {
		String safe = safeReturnPath(returnPath);
		if (safe == null || successUrl == null || successUrl.isBlank()) {
			return successUrl;
		}
		URI success = URI.create(successUrl);
		if (success.getScheme() == null || success.getHost() == null) {
			return successUrl;
		}
		return UriComponentsBuilder.fromUri(success)
				.replacePath(safe)
				.replaceQuery(null)
				.fragment(null)
				.build()
				.toUriString();
	}

	public static String failureLocation(String failureUrl, IntegrationErrorCode code) {
		if (failureUrl == null || failureUrl.isBlank()) {
			return failureUrl;
		}
		UriComponentsBuilder builder = UriComponentsBuilder.fromUri(URI.create(failureUrl));
		if (code != null) {
			builder.replaceQueryParam("code", code.name());
		}
		return builder.build().toUriString();
	}

	/** Success/return path with an integration error code for controlled FE handling (e.g. installation picker). */
	public static String successLocationWithCode(String successUrl, String returnPath, IntegrationErrorCode code) {
		String base = successLocation(successUrl, returnPath);
		if (base == null || base.isBlank() || code == null) {
			return base;
		}
		return UriComponentsBuilder.fromUriString(base).replaceQueryParam("code", code.name()).build().toUriString();
	}

	public static ResponseEntity<Void> seeOther(String location) {
		return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
	}

	public static ResponseEntity<Void> failure(String failureUrl, IntegrationException ex) {
		return seeOther(failureLocation(failureUrl, ex == null ? null : ex.getCode()));
	}
}
