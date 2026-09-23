package com.saga.be.ai;

import com.saga.be.entity.enums.AiCredentialSource;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.service.ai.AiCredentialEnvelope;
import java.util.List;
import java.util.UUID;

public record AiAnalysisRequest(UUID runId, AiProviderRole providerRole, AiAnalysisType analysisType, String promptVersion, String taxonomyVersion, String systemContract, List<AiEvidenceInput> evidence, AiCredentialSource credentialSource, AiCredentialEnvelope credentialEnvelope) {
	public AiAnalysisRequest(UUID runId, AiProviderRole providerRole, AiAnalysisType analysisType, String promptVersion, String taxonomyVersion, String systemContract, List<AiEvidenceInput> evidence) { this(runId, providerRole, analysisType, promptVersion, taxonomyVersion, systemContract, evidence, AiCredentialSource.PLATFORM, null); }
	public AiAnalysisRequest(UUID runId, AiProviderRole providerRole, String systemContract, List<AiEvidenceInput> evidence) { this(runId, providerRole, AiAnalysisType.COMMIT_INTELLIGENCE, "commit-intelligence-v1", null, systemContract, evidence, AiCredentialSource.PLATFORM, null); }
	public record AiEvidenceInput(UUID id, String type, String sourceRef, String payloadJson, String metadataJson) {}
}
