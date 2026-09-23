package com.saga.be.service.ai;

import com.saga.be.ai.*;
import com.saga.be.entity.enums.AiRiskLevel;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Validates a Risk Analysis response solely against this run's immutable evidence bundle. */
@Component
@Profile("!test")
public class AiRiskAnalysisResultValidator {
	public Optional<String> invalidReason(AiRiskAnalysisResult result, Set<UUID> evidenceIds) {
		if (result == null || result.riskLevel() == null || result.riskReasons() == null || result.riskReasons().isEmpty() || result.recommendedActions() == null) return Optional.of("MISSING_REQUIRED_SECTION");
		if (!confidence(result.confidence())) return Optional.of("INVALID_CONFIDENCE");
		if (result.riskLevel() == AiRiskLevel.HIGH && !result.humanReviewRecommended()) return Optional.of("HIGH_RISK_REQUIRES_HUMAN_REVIEW");
		for (AiFinding reason : result.riskReasons()) if (reason == null || reason.evidence() == null || reason.evidence().isEmpty() || !validRefs(reason.evidence(), evidenceIds)) return Optional.of("INVALID_RISK_REASON_EVIDENCE");
		return Optional.empty();
	}
	private static boolean confidence(Double value) { return value == null || (value >= 0d && value <= 1d); }
	private static boolean validRefs(List<AiEvidenceReference> refs, Set<UUID> ids) { if (refs == null) return true; return refs.stream().allMatch(ref -> ref != null && ref.kind() != null && ref.evidenceId() != null && ids.contains(ref.evidenceId())); }
}
