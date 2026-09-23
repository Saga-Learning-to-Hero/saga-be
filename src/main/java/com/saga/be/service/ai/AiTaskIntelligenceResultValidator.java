package com.saga.be.service.ai;

import com.saga.be.ai.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Validates a Task Intelligence response solely against this run's immutable evidence bundle. */
@Component
@Profile("!test")
public class AiTaskIntelligenceResultValidator {
	public Optional<String> invalidReason(AiTaskIntelligenceResult result, Set<UUID> evidenceIds) {
		if (result == null || result.evidenceStrength() == null || result.summary() == null || result.summary().isBlank()) return Optional.of("MISSING_REQUIRED_SECTION");
		if (result.deviationDetected() && (result.deviationSummary() == null || result.deviationSummary().isBlank())) return Optional.of("DEVIATION_WITHOUT_SUMMARY");
		if (!result.deviationDetected() && result.deviationSummary() != null && !result.deviationSummary().isBlank()) return Optional.of("DEVIATION_SUMMARY_WITHOUT_FLAG");
		if (!validRefs(result.evidence(), evidenceIds)) return Optional.of("UNKNOWN_EVIDENCE_REFERENCE");
		return Optional.empty();
	}
	private static boolean validRefs(List<AiEvidenceReference> refs, Set<UUID> ids) { if (refs == null) return true; return refs.stream().allMatch(ref -> ref != null && ref.kind() != null && ref.evidenceId() != null && ids.contains(ref.evidenceId())); }
}
