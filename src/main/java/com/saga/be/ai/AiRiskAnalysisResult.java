package com.saga.be.ai;

import com.saga.be.entity.enums.AiRiskLevel;
import java.util.List;

public record AiRiskAnalysisResult(AiRiskLevel riskLevel, List<AiFinding> riskReasons, List<String> recommendedActions, boolean humanReviewRecommended, Double confidence) implements AiAnalysisResult {}
