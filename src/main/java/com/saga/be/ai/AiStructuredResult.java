package com.saga.be.ai;

import java.util.List;

public record AiStructuredResult(CommitMessageAssessment commitMessageAssessment, CodeAssessment codeAssessment, TaskAlignment taskAlignment, List<AcademicClassification> academicClassifications, AiOverallDecision overallDecision, boolean humanReviewRequired) {
	public record CommitMessageAssessment(AiCommitMessageVerdict verdict, Integer score, List<AiFinding> findings) {}
	public record CodeAssessment(AiCodeVerdict verdict, Double confidence, List<AiFinding> findings, List<AiEvidenceReference> evidence) {}
	public record TaskAlignment(AiTaskAlignmentVerdict verdict, Double confidence, List<AiEvidenceReference> evidence) {}
	/** Reserved for later academic phases; it must be empty in AI-1. */
	public record AcademicClassification(String targetType, String targetId, Double confidence, String status, List<AiEvidenceReference> evidence) {}
}
