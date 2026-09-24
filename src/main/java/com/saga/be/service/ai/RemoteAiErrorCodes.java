package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.web.client.RestClientResponseException;

/** Maps a saga-ai error response to a safe code. saga-ai's contract is a top-level
 * {@code {"code": "...", "message": "..."}} body; only an absent or unrecognised code falls back
 * to the HTTP status. Shared by both remote providers so the two can never diverge again. */
final class RemoteAiErrorCodes {
	private static final Set<String> KNOWN = Set.of(
			"AI_RUNTIME_NOT_CONFIGURED",
			"AI_RUNTIME_DISABLED",
			"AI_RUNTIME_UNAVAILABLE",
			"AI_PROVIDER_AUTH_FAILED",
			"AI_PROVIDER_RATE_LIMITED",
			"AI_PROVIDER_TIMEOUT",
			"AI_PROVIDER_FAILED",
			"AI_PROVIDER_RESULT_INVALID",
			"AI_PROVIDER_QUOTA_EXHAUSTED",
			"AI_PROVIDER_UNAVAILABLE",
			"AI_PROVIDER_MODEL_NOT_FOUND",
			"AI_PROVIDER_NOT_SUPPORTED",
			"AI_MODEL_NOT_SUPPORTED",
			"AI_MODEL_CAPABILITY_UNSUPPORTED",
			"AI_CONTRACT_VERSION_UNSUPPORTED",
			"AI_CREDENTIAL_ENVELOPE_INVALID",
			"AI_CREDENTIAL_TRANSPORT_NOT_CONFIGURED");

	private RemoteAiErrorCodes() {}

	static String from(ObjectMapper mapper, RestClientResponseException e) {
		try {
			String code = mapper.readTree(e.getResponseBodyAsString()).path("code").asText("");
			if (KNOWN.contains(code)) return code;
		} catch (Exception ignored) {
			// Unparseable body: fall through to the status-based mapping below.
		}
		int status = e.getStatusCode().value();
		// saga-ai's own internal-token rejection is 401 UNAUTHORIZED: a runtime configuration
		// problem, never evidence that the lecturer's provider key is wrong.
		if (status == 401) return "AI_RUNTIME_UNAVAILABLE";
		if (status == 429) return "AI_PROVIDER_RATE_LIMITED";
		return "AI_PROVIDER_FAILED";
	}
}
