package com.saga.be.workload;

import java.util.regex.Pattern;

/**
 * Bounded route identity for logs/metrics. Uses Spring's mapping pattern, never a UUID instance path.
 */
public final class WorkloadRoutePatterns {

	static final String UNMAPPED = "unmapped";

	private static final Pattern UUID_INSTANCE = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private WorkloadRoutePatterns() {}

	public static String normalize(Object bestMatchingPattern) {
		if (!(bestMatchingPattern instanceof String raw) || raw.isBlank()) {
			return UNMAPPED;
		}
		String trimmed = raw.trim();
		return UUID_INSTANCE.matcher(trimmed).replaceAll("{id}");
	}

	public static boolean containsUuidInstance(String routePattern) {
		return routePattern != null && UUID_INSTANCE.matcher(routePattern).find();
	}
}
