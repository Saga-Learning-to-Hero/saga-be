package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Set;

/**
 * The model that really answered, as saga-ai reported it ({@code providerKey}, {@code modelId} in the
 * decision's cost metadata). A system-key run is recorded with saga-be's configured default model
 * (gpt-5.6-sol) although saga-ai served it from its own chain (Cohere, Gemini...).
 */
record AiActualModel(String provider, String modelId) {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Set<String> PROVIDERS = Set.of("OPENAI", "GEMINI", "OPENROUTER", "COHERE");

	/** Null when saga-ai said nothing usable (an older run, the fake provider). */
	static AiActualModel from(String costMetadataJson) {
		if (costMetadataJson == null || costMetadataJson.isBlank()) return null;
		try {
			JsonNode node = MAPPER.readTree(costMetadataJson);
			String modelId = node.path("modelId").asText("").trim();
			String provider = node.path("providerKey").asText("").trim().toUpperCase(Locale.ROOT);
			if (modelId.isEmpty() || !PROVIDERS.contains(provider)) return null;
			return new AiActualModel(provider, modelId);
		} catch (Exception ex) {
			return null;
		}
	}
}
