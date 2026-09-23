package com.saga.be.dto.ai;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AiAnalysisResponse(UUID id, UUID projectId, UUID courseId, String artifactType, UUID artifactId, String artifactRevision, String analysisType, String status, String evidenceHash, String policyVersion, String promptVersion, String schemaVersion, String taxonomyVersion, String providerConfigHash, LocalDateTime startedAt, LocalDateTime completedAt, String failureCode, LocalDateTime createdAt, List<AiAnalysisEvidenceSummaryResponse> evidence, AiAnalysisDecisionResponse providerDecision) {}
