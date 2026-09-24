package com.saga.be.ai;

import com.saga.be.entity.enums.AiProvider;

/** A server-validated (provider, model) pair from the course's AI settings. A {@code null}
 * binding anywhere means the legacy, pre multi-provider behaviour: OpenAI with the platform model. */
public record AiProviderBinding(AiProvider provider, String modelId) {
	public String identity() { return provider.name() + ":" + modelId; }
}
