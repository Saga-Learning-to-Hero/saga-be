package com.saga.be.service.contribution;

import com.saga.be.entity.enums.ContributionCriterion;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReservedContributionMarkerClassifier {

	public static final String CODE = "saga:code";
	public static final String TEST = "saga:test";
	public static final String DOCUMENT = "saga:document";
	public static final String RESEARCH = "saga:research";

	public enum Outcome {
		CODE,
		TEST,
		DOCUMENT,
		RESEARCH,
		NONE,
		AMBIGUOUS
	}

	/**
	 * Exact spelling SAGA's own task form offered for DOCUMENT before the canonical marker was
	 * enforced; tasks carrying it were meant as documents, so it counts as {@link #DOCUMENT}.
	 */
	public static final String DOCUMENT_ALIAS = "saga:doc";

	private ReservedContributionMarkerClassifier() {}

	/**
	 * Every reserved marker present on a task (exact, case-sensitive match after trim; never a
	 * substring). Empty when the task has none. Order: CODE, TEST, DOCUMENT, RESEARCH.
	 */
	public static Set<Outcome> markers(List<String> labels) {
		Set<Outcome> found = new java.util.TreeSet<>();
		if (labels != null) {
			for (String raw : labels) {
				if (raw == null) {
					continue;
				}
				String label = raw.trim();
				if (CODE.equals(label)) {
					found.add(Outcome.CODE);
				} else if (TEST.equals(label)) {
					found.add(Outcome.TEST);
				} else if (DOCUMENT.equals(label) || DOCUMENT_ALIAS.equals(label)) {
					found.add(Outcome.DOCUMENT);
				} else if (RESEARCH.equals(label)) {
					found.add(Outcome.RESEARCH);
				}
			}
		}
		return new LinkedHashSet<>(found);
	}

	public static Outcome classify(List<String> labels) {
		Set<Outcome> found = markers(labels);
		if (found.isEmpty()) {
			return Outcome.NONE;
		}
		if (found.size() > 1) {
			return Outcome.AMBIGUOUS;
		}
		return found.iterator().next();
	}

	public static ContributionCriterion toCriterion(Outcome outcome) {
		return switch (outcome) {
			case CODE -> ContributionCriterion.CODE;
			case TEST -> ContributionCriterion.TEST;
			case DOCUMENT -> ContributionCriterion.DOCUMENT;
			case RESEARCH -> ContributionCriterion.RESEARCH;
			case NONE, AMBIGUOUS -> null;
		};
	}
}
