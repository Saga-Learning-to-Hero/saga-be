package com.saga.be.ai;

import java.util.List;

/** Target types and evidence are strings so unsupported provider values reach deterministic validation. */
public record AiAcademicProposedClassification(String targetType, String targetId, Double confidence, String summary, List<String> evidence) {}
