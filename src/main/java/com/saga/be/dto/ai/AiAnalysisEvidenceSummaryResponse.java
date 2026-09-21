package com.saga.be.dto.ai;

import java.util.UUID;

public record AiAnalysisEvidenceSummaryResponse(UUID id, String type, String sourceRef, String contentHash, int ordinalIndex) {}
