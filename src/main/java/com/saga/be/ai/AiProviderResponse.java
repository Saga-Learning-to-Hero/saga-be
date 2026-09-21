package com.saga.be.ai;

public record AiProviderResponse(AiStructuredResult result, Long latencyMs, Long inputUnits, Long outputUnits, String modelRevision, String costMetadataJson) {}
