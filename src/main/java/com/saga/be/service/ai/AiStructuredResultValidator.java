package com.saga.be.service.ai;

import com.saga.be.ai.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("!test")
public class AiStructuredResultValidator {
	public Optional<String> invalidReason(AiStructuredResult result, Set<UUID> evidenceIds) {
		return invalidReason(result, evidenceIds, Set.of(), true);
	}
	public Optional<String> invalidReason(AiStructuredResult result, Set<UUID> evidenceIds, Set<UUID> linkedTaskIds, boolean completeCodeCoverage) {
		if (result == null || result.commitMessageAssessment() == null || result.codeAssessment() == null || result.taskAlignments() == null || result.taskAlignmentSummary() == null || result.overallDecision() == null || result.academicClassifications() == null) return Optional.of("MISSING_REQUIRED_SECTION");
		if (result.commitMessageAssessment().score() == null || result.commitMessageAssessment().score() < 0 || result.commitMessageAssessment().score() > 100) return Optional.of("INVALID_SCORE");
		if (!confidence(result.codeAssessment().confidence())) return Optional.of("INVALID_CONFIDENCE");
		if (!result.academicClassifications().isEmpty()) return Optional.of("ACADEMIC_CLASSIFICATION_NOT_ALLOWED");
		if (result.overallDecision() == AiOverallDecision.HUMAN_REVIEW_REQUIRED && !result.humanReviewRequired()) return Optional.of("OVERALL_REVIEW_INCONSISTENT");
		List<AiFinding> findings = new ArrayList<>();
		if (result.commitMessageAssessment().findings() != null) findings.addAll(result.commitMessageAssessment().findings());
		if (result.codeAssessment().findings() != null) findings.addAll(result.codeAssessment().findings());
		for (AiFinding finding : findings) if (finding == null || finding.evidence() == null || finding.evidence().isEmpty() || !validRefs(finding.evidence(), evidenceIds)) return Optional.of("INVALID_FINDING_EVIDENCE");
		Set<UUID> taskIds = new HashSet<>();
		for (AiStructuredResult.TaskAlignment alignment : result.taskAlignments()) {
			if (alignment == null || alignment.taskId() == null || !linkedTaskIds.contains(alignment.taskId()) || !taskIds.add(alignment.taskId()) || !confidence(alignment.confidence()) || !validRefs(alignment.evidence(), evidenceIds)) return Optional.of("INVALID_TASK_ALIGNMENT");
		}
		if (!taskIds.equals(linkedTaskIds) || (linkedTaskIds.isEmpty() && result.taskAlignmentSummary() != AiTaskAlignmentVerdict.NO_LINKED_TASK) || (!linkedTaskIds.isEmpty() && result.taskAlignmentSummary() == AiTaskAlignmentVerdict.NO_LINKED_TASK)) return Optional.of("TASK_ALIGNMENT_MEMBERSHIP_INVALID");
		if (!completeCodeCoverage && result.codeAssessment().verdict() == AiCodeVerdict.POSITIVE) return Optional.of("POSITIVE_WITH_PARTIAL_EVIDENCE");
		if (!validRefs(result.codeAssessment().evidence(), evidenceIds)) return Optional.of("UNKNOWN_EVIDENCE_REFERENCE");
		return Optional.empty();
	}
	private static boolean confidence(Double value) { return value == null || (value >= 0d && value <= 1d); }
	private static boolean validRefs(List<AiEvidenceReference> refs, Set<UUID> ids) { if (refs == null) return true; return refs.stream().allMatch(ref -> ref != null && ref.kind() != null && ref.evidenceId() != null && ids.contains(ref.evidenceId())); }
}
