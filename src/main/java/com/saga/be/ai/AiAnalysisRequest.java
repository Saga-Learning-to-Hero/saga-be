package com.saga.be.ai;

import com.saga.be.entity.enums.AiProviderRole;
import java.util.List;
import java.util.UUID;

public record AiAnalysisRequest(UUID runId, AiProviderRole providerRole, String systemContract, List<AiEvidenceInput> evidence) {
	public record AiEvidenceInput(UUID id, String type, String sourceRef, String payloadJson, String metadataJson) {}
}
