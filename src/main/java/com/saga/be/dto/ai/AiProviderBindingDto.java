package com.saga.be.dto.ai;

/** A course provider binding on the wire: canonical provider (OPENAI/GEMINI/OPENROUTER/COHERE) and a
 * model id from the server-side catalog. Requests are validated; responses are always canonical. */
public record AiProviderBindingDto(String provider, String modelId) {}
