package com.saga.be.ai;

import com.saga.be.entity.enums.AiProviderRole;

public interface AiModelProvider {
	AiProviderRole role();
	String providerKey();
	String providerConfigHash();
	String modelId();
	AiProviderResponse analyze(AiAnalysisRequest request);
}
