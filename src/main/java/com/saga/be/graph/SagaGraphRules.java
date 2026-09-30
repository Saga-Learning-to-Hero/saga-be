package com.saga.be.graph;

import com.saga.be.entity.enums.ContributionCriterion;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.service.contribution.ReservedContributionMarkerClassifier;
import com.saga.be.service.contribution.TaskEvidencePolicy;
import com.saga.be.service.contribution.ReservedContributionMarkerClassifier.Outcome;
import java.util.List;

public final class SagaGraphRules {

	public record TaskGraphAttrs(String weightType, boolean classified, boolean anomaly) {}

	private SagaGraphRules() {}

	public static TaskGraphAttrs classify(
			TaskStatus status, List<String> labels, boolean hasFileEvidence, int linkedCommitCount) {
		Outcome outcome = ReservedContributionMarkerClassifier.classify(labels);
		ContributionCriterion criterion = ReservedContributionMarkerClassifier.toCriterion(outcome);
		boolean classified = criterion != null;
		if (criterion == ContributionCriterion.DOCUMENT || criterion == ContributionCriterion.RESEARCH) {
			classified = hasFileEvidence;
		}
		String weightType = classified ? criterion.name() : null;
		// Anomaly = a DONE task missing the proof its own labels require (commit for code/test,
		// file/link/attachment for document/research) -- the same TaskEvidencePolicy the task list uses.
		boolean anomaly = TaskEvidencePolicy.evaluate(status, labels, linkedCommitCount, hasFileEvidence ? 1 : 0)
				.missingProof();
		return new TaskGraphAttrs(weightType, classified && weightType != null, anomaly);
	}
}
