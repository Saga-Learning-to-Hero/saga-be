package com.saga.be.ai;

import com.saga.be.entity.enums.AiProviderRole;

public interface AiModelProvider {
	AiProviderRole role();
	String providerKey();
	String providerConfigHash();
	String modelId();
	AiProviderResponse analyze(AiAnalysisRequest request);

	/** Only a provider that forwards the course credential envelope may serve a COURSE-sourced
	 * run; any other provider would silently answer with the platform key instead. */
	default boolean supportsCourseCredential() { return false; }
}
