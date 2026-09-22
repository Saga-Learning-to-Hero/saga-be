package com.saga.be.ai;

public record AiProviderResponse(AiAnalysisResult result, Long latencyMs, Long inputUnits, Long outputUnits, String modelRevision, String costMetadataJson) {}
