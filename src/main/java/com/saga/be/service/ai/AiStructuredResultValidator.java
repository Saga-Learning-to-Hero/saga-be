package com.saga.be.service.ai;

import com.saga.be.ai.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("!test")
public class AiStructuredResultValidator {
	public Optional<String> invalidReason(AiStructuredResult result, Set<UUID> evidenceIds) {
		if (result == null || result.commitMessageAssessment() == null || result.codeAssessment() == null || result.taskAlignment() == null || result.overallDecision() == null || result.academicClassifications() == null) return Optional.of("MISSING_REQUIRED_SECTION");
		if (result.commitMessageAssessment().score() == null || result.commitMessageAssessment().score() < 0 || result.commitMessageAssessment().score() > 100) return Optional.of("INVALID_SCORE");
		if (!confidence(result.codeAssessment().confidence()) || !confidence(result.taskAlignment().confidence())) return Optional.of("INVALID_CONFIDENCE");
		if (!result.academicClassifications().isEmpty()) return Optional.of("ACADEMIC_CLASSIFICATION_NOT_ALLOWED");
		if (result.overallDecision() == AiOverallDecision.HUMAN_REVIEW_REQUIRED && !result.humanReviewRequired()) return Optional.of("OVERALL_REVIEW_INCONSISTENT");
		List<AiFinding> findings = new ArrayList<>();
		if (result.commitMessageAssessment().findings() != null) findings.addAll(result.commitMessageAssessment().findings());
		if (result.codeAssessment().findings() != null) findings.addAll(result.codeAssessment().findings());
		for (AiFinding finding : findings) if (finding == null || finding.evidence() == null || finding.evidence().isEmpty() || !validRefs(finding.evidence(), evidenceIds)) return Optional.of("INVALID_FINDING_EVIDENCE");
		if (!validRefs(result.codeAssessment().evidence(), evidenceIds) || !validRefs(result.taskAlignment().evidence(), evidenceIds)) return Optional.of("UNKNOWN_EVIDENCE_REFERENCE");
		return Optional.empty();
	}
	private static boolean confidence(Double value) { return value == null || (value >= 0d && value <= 1d); }
	private static boolean validRefs(List<AiEvidenceReference> refs, Set<UUID> ids) { if (refs == null) return true; return refs.stream().allMatch(ref -> ref != null && ref.kind() != null && ref.evidenceId() != null && ids.contains(ref.evidenceId())); }
}
