package com.saga.be.service.contribution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.saga.be.service.contribution.ReservedContributionMarkerClassifier.Outcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReservedContributionMarkerClassifierTest {

	@Test
	void exactReservedLabelMapsToCriterion() {
		assertEquals(Outcome.CODE, ReservedContributionMarkerClassifier.classify(List.of("backend", "saga:code")));
		assertEquals(Outcome.TEST, ReservedContributionMarkerClassifier.classify(List.of("saga:test")));
		assertEquals(Outcome.DOCUMENT, ReservedContributionMarkerClassifier.classify(List.of("saga:document")));
		assertEquals(Outcome.RESEARCH, ReservedContributionMarkerClassifier.classify(List.of("saga:research")));
	}

	@Test
	void caseAndSubstringDoNotMatch() {
		assertEquals(Outcome.NONE, ReservedContributionMarkerClassifier.classify(List.of("SAGA:TEST")));
		assertEquals(Outcome.NONE, ReservedContributionMarkerClassifier.classify(List.of("saga:test-extra")));
		assertEquals(Outcome.NONE, ReservedContributionMarkerClassifier.classify(List.of("ui-ux")));
	}

	@Test
	void conflictingMarkersAreAmbiguous() {
		assertEquals(
				Outcome.AMBIGUOUS,
				ReservedContributionMarkerClassifier.classify(List.of("saga:code", "saga:research")));
	}

	@Test
	void jsonLabelsParse() {
		assertEquals(List.of("saga:code", "backend"), TaskLabelParser.parse("[\"saga:code\",\"backend\"]"));
	}
}
