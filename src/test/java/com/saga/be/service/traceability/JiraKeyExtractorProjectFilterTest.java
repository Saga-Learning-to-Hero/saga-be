package com.saga.be.service.traceability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class JiraKeyExtractorProjectFilterTest {

	@Test
	void extractForProject_onlySelectedProjectKey() {
		Set<String> keys = JiraKeyExtractor.extractForProject(
				"SAGA", "SAGA-123 implement login", "fix(SAGA-15): OAuth", "ABC-99 unrelated");
		assertThat(keys).containsExactly("SAGA-123", "SAGA-15");
	}

	@Test
	void extractForProject_blankProjectKey_returnsEmpty() {
		assertThat(JiraKeyExtractor.extractForProject(" ", "SAGA-1")).isEmpty();
		assertThat(JiraKeyExtractor.extractForProject(null, "SAGA-1")).isEmpty();
	}
}
