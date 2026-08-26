package com.saga.be.service.attribution;

import com.saga.be.entity.enums.AttributionConfidence;
import com.saga.be.entity.enums.AttributionRiskSignal;
import java.util.ArrayList;
import java.util.List;

public final class AttributionConfidenceEvaluator {

	public record Evidence(
			boolean identityMapped,
			boolean teamMember,
			boolean jiraAssigneeMatchesGithubActor,
			boolean commitSignatureVerified,
			boolean sagaWorkSessionPresent,
			boolean recentStepUp,
			boolean contributionConfirmed,
			boolean identityChangedNearDeadline,
			boolean unmappedProviderActor) {}

	public record Result(AttributionConfidence confidence, List<AttributionRiskSignal> riskSignals) {}

	private AttributionConfidenceEvaluator() {}

	public static Result evaluate(Evidence evidence) {
		List<AttributionRiskSignal> signals = new ArrayList<>();
		if (evidence.unmappedProviderActor() || !evidence.identityMapped()) {
			signals.add(AttributionRiskSignal.UNMAPPED_PROVIDER_IDENTITY);
		}
		if (evidence.identityMapped() && evidence.teamMember() && !evidence.jiraAssigneeMatchesGithubActor()) {
			signals.add(AttributionRiskSignal.JIRA_ASSIGNEE_GITHUB_ACTOR_MISMATCH);
		}
		if (evidence.identityChangedNearDeadline()) {
			signals.add(AttributionRiskSignal.IDENTITY_CHANGED_NEAR_DEADLINE);
		}
		if (!evidence.contributionConfirmed()) {
			signals.add(AttributionRiskSignal.CONTRIBUTION_CONFIRMATION_MISSING);
		}
		if (!evidence.sagaWorkSessionPresent() && evidence.identityMapped()) {
			signals.add(AttributionRiskSignal.PROVIDER_ACTIVITY_WITHOUT_SAGA_TASK_EVIDENCE);
		}
		// Missing commit signature is intentionally not a cheating signal.
		int flags = 0;
		if (!evidence.identityMapped()) {
			flags += 2;
		}
		if (!evidence.teamMember()) {
			flags += 2;
		}
		if (!evidence.jiraAssigneeMatchesGithubActor()) {
			flags += 1;
		}
		if (evidence.identityChangedNearDeadline()) {
			flags += 2;
		}
		if (!evidence.contributionConfirmed()) {
			flags += 1;
		}
		if (!evidence.sagaWorkSessionPresent()) {
			flags += 1;
		}
		if (evidence.recentStepUp()) {
			flags = Math.max(0, flags - 1);
		}
		if (evidence.commitSignatureVerified()) {
			flags = Math.max(0, flags - 1);
		}
		AttributionConfidence confidence;
		if (!evidence.identityMapped() || evidence.identityChangedNearDeadline()) {
			confidence = AttributionConfidence.FLAGGED;
		} else if (flags >= 4) {
			confidence = AttributionConfidence.LOW;
		} else if (flags >= 2) {
			confidence = AttributionConfidence.MEDIUM;
		} else {
			confidence = AttributionConfidence.HIGH;
		}
		return new Result(confidence, List.copyOf(signals));
	}
}
