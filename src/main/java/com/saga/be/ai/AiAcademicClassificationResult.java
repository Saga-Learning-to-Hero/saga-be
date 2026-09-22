package com.saga.be.ai;

import java.util.List;

public record AiAcademicClassificationResult(AiAcademicClassificationDecision classificationDecision, List<AiAcademicProposedClassification> classifications, String summary, boolean humanReviewRequired) implements AiAnalysisResult {}
