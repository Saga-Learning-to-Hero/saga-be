package com.saga.be.service.evidence;

import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.service.confirmation.EvidenceHasher;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

public final class TaskWebLinkUrls {

	private TaskWebLinkUrls() {}

	public static String normalize(String raw) {
		if (raw == null || raw.isBlank()) {
			throw invalid("URL is required.");
		}
		String trimmed = raw.trim();
		if (trimmed.length() > 2048) {
			throw invalid("URL must be at most 2048 characters.");
		}
		if (trimmed.contains(" ") || trimmed.contains("\n") || trimmed.contains("\r") || trimmed.contains("\t")) {
			throw invalid("URL must not contain whitespace.");
		}
		URI uri;
		try {
			uri = URI.create(trimmed);
		} catch (IllegalArgumentException ex) {
			throw invalid("URL is invalid.");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			throw invalid("URL must start with http:// or https://.");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw invalid("URL host is required.");
		}
		return trimmed;
	}

	public static String hash(String normalizedUrl) {
		return EvidenceHasher.sha256(normalizedUrl);
	}

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.TASK_WEB_LINK_INVALID, HttpStatus.BAD_REQUEST, message);
	}
}
