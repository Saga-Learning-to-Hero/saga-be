package com.saga.be.ai;

import java.util.List;

public record AiProgressNarrativeResult(String overview, List<String> highlights, List<String> concerns, List<String> recommendations, List<String> blockers, String dueSoonOverdueNote, List<AiEvidenceReference> evidence, boolean humanReviewRecommended) implements AiAnalysisResult {}
