package com.saga.be.dto.ai;

public record AiAdjudicationResponse(String outcome, String disagreementDetailsJson, boolean humanReviewRequired) {
	public static AiAdjudicationResponse notApplicable() { return new AiAdjudicationResponse("NOT_APPLICABLE", null, false); }
}
