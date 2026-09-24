package com.saga.be.entity.enums;

import java.util.Locale;
import java.util.Optional;

/** Canonical external AI provider behind a course credential or binding. Never free text. */
public enum AiProvider {
	OPENAI, GEMINI, OPENROUTER;

	/** Case-insensitive parse so legacy clients that sent {@code "openai"} keep working; anything
	 * outside the enum is empty, never a new provider. */
	public static Optional<AiProvider> parse(String raw) {
		if (raw == null || raw.isBlank()) return Optional.empty();
		try { return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT))); }
		catch (IllegalArgumentException e) { return Optional.empty(); }
	}
}
