package com.saga.be.dto.ai;

import java.util.List;

/** Server-side model allowlist. Static data: serving it never contacts any provider. */
public record AiProviderCatalogResponse(List<Provider> providers, String freeTierNotice) {
	public record Provider(String provider, String displayName, List<Model> models) {}
	public record Model(String provider, String modelId, String displayName, boolean freeTierEligible, boolean supportsStructuredOutput, boolean recommendedForAutomation) {}
}
