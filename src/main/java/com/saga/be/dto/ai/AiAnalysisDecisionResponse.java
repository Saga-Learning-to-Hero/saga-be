package com.saga.be.dto.ai;

import java.time.LocalDateTime;
import java.util.UUID;

public record AiAnalysisDecisionResponse(UUID id, String providerRole, String providerKey, String modelId, String modelRevision, String route, String status, Boolean schemaValid, Long latencyMs, Long inputUnits, Long outputUnits, String safeErrorCode, String structuredResultJson, LocalDateTime completedAt) {}
