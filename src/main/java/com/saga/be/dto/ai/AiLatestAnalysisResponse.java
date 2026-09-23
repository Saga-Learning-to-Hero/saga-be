package com.saga.be.dto.ai;

public record AiLatestAnalysisResponse(String status, AiAnalysisResponse analysis) {
	public static AiLatestAnalysisResponse notAnalyzed() { return new AiLatestAnalysisResponse("NOT_ANALYZED", null); }
	public static AiLatestAnalysisResponse found(AiAnalysisResponse analysis) { return new AiLatestAnalysisResponse("FOUND", analysis); }
}
