package com.saga.be.ai;

import com.saga.be.entity.enums.AiTaskEvidenceStrength;
import java.util.List;

public record AiTaskIntelligenceResult(AiTaskEvidenceStrength evidenceStrength, String summary, boolean deviationDetected, String deviationSummary, List<AiEvidenceReference> evidence, boolean humanReviewRequired) implements AiAnalysisResult {}
