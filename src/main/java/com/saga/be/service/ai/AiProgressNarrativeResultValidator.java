package com.saga.be.service.ai;

import com.saga.be.ai.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Validates a Progress Narrative response solely against this run's immutable evidence bundle. */
@Component
@Profile("!test")
public class AiProgressNarrativeResultValidator {
	public Optional<String> invalidReason(AiProgressNarrativeResult result, Set<UUID> evidenceIds) {
		if (result == null || result.overview() == null || result.overview().isBlank()) return Optional.of("MISSING_OVERVIEW");
		if (result.highlights() == null || result.concerns() == null || result.recommendations() == null || result.blockers() == null) return Optional.of("MISSING_REQUIRED_SECTION");
		if (result.dueSoonOverdueNote() == null || result.dueSoonOverdueNote().isBlank()) return Optional.of("MISSING_DUE_SOON_OVERDUE_NOTE");
		if (!validRefs(result.evidence(), evidenceIds)) return Optional.of("UNKNOWN_EVIDENCE_REFERENCE");
		return Optional.empty();
	}
	private static boolean validRefs(List<AiEvidenceReference> refs, Set<UUID> ids) { if (refs == null) return true; return refs.stream().allMatch(ref -> ref != null && ref.kind() != null && ref.evidenceId() != null && ids.contains(ref.evidenceId())); }
}
