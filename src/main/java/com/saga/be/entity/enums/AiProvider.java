package com.saga.be.entity.enums;

import java.util.Locale;
import java.util.Optional;

/**
 * Canonical external AI provider behind a course credential or binding. Never free text.
 *
 * <p>Only {@link #isSupported() supported} providers (OPENAI, GEMINI) can be configured or
 * executed. OPENROUTER and COHERE are LEGACY values: SAGA no longer offers them, but they stay in
 * this enum so rows persisted while they were supported (provider decisions, credentials,
 * bindings) remain readable and their provenance is reported truthfully, never rewritten.
 */
public enum AiProvider {
	OPENAI, GEMINI, OPENROUTER, COHERE;

	/** True for providers SAGA currently offers for new configuration and runtime dispatch. */
	public boolean isSupported() {
		return this == OPENAI || this == GEMINI;
	}

	/** Case-insensitive parse so legacy clients that sent {@code "openai"} keep working; anything
	 * outside the enum is empty, never a new provider. Accepts LEGACY values -- use
	 * {@link #parseSupported} for anything that configures new work. */
	public static Optional<AiProvider> parse(String raw) {
		if (raw == null || raw.isBlank()) return Optional.empty();
		try { return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT))); }
		catch (IllegalArgumentException e) { return Optional.empty(); }
	}

	/** Like {@link #parse} but empty for LEGACY providers: the gate for new credentials/bindings. */
	public static Optional<AiProvider> parseSupported(String raw) {
		return parse(raw).filter(AiProvider::isSupported);
	}
}
