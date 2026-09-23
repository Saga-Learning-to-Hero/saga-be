package com.saga.be.ai;

/** Provider-neutral, analysis-type-specific structured result marker. */
public sealed interface AiAnalysisResult permits AiStructuredResult, AiAcademicClassificationResult, AiTaskIntelligenceResult, AiRiskAnalysisResult, AiProgressNarrativeResult {}
