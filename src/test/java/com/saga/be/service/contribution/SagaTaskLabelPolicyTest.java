package com.saga.be.service.contribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SagaTaskLabelPolicyTest {

	@Test
	void createNormalizesCaseWhitespaceAndDocAliasToCanonicalMarkers() {
		assertThat(SagaTaskLabelPolicy.forCreate(List.of(" SAGA:Code "))).containsExactly("saga:code");
		assertThat(SagaTaskLabelPolicy.forCreate(List.of("saga:doc"))).containsExactly("saga:document");
		assertThat(SagaTaskLabelPolicy.forCreate(List.of("saga:research", "saga:research")))
				.containsExactly("saga:research");
	}

	@Test
	void createKeepsNullAsOmittedAndDropsBlankEntries() {
		assertThat(SagaTaskLabelPolicy.forCreate(null)).isNull();
		assertThat(SagaTaskLabelPolicy.forCreate(List.of(" ", ""))).isEmpty();
	}

	@Test
	void createRejectsAnyNonSagaLabelWithTheAllowedListInDetails() {
		assertThatThrownBy(() -> SagaTaskLabelPolicy.forCreate(List.of("saga:code", "frontend")))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> {
					IntegrationException error = (IntegrationException) ex;
					assertThat(error.getCode()).isEqualTo(IntegrationErrorCode.TASK_LABEL_NOT_ALLOWED);
					assertThat(error.getDetails()).isEqualTo(Map.of("allowedLabels", SagaTaskLabelPolicy.ALLOWED));
				});
	}

	@Test
	void createRejectsTwoDifferentMarkers() {
		assertThatThrownBy(() -> SagaTaskLabelPolicy.forCreate(List.of("saga:code", "saga:test")))
				.isInstanceOf(IntegrationException.class);
		assertThatThrownBy(() -> SagaTaskLabelPolicy.forCreate(List.of("saga:doc", "saga:research")))
				.isInstanceOf(IntegrationException.class);
	}

	@Test
	void patchKeepsExistingLabelsVerbatimIncludingNonSagaOnes() {
		assertThat(SagaTaskLabelPolicy.forPatch(List.of("Backend", "saga:code"), List.of("backend", "saga:code")))
				.containsExactly("Backend", "saga:code");
	}

	@Test
	void patchLeavesAnAlreadyAmbiguousTaskAloneWhenNoMarkerIsAdded() {
		assertThat(SagaTaskLabelPolicy.forPatch(List.of("saga:code", "saga:test"), List.of("saga:code", "saga:test")))
				.containsExactly("saga:code", "saga:test");
		assertThat(SagaTaskLabelPolicy.forPatch(List.of("saga:code", "saga:test"), List.of("saga:test")))
				.containsExactly("saga:test");
	}

	@Test
	void patchRejectsNewNonSagaLabelAndAddingASecondMarker() {
		assertThatThrownBy(() -> SagaTaskLabelPolicy.forPatch(List.of("backend"), List.of("backend", "urgent")))
				.isInstanceOf(IntegrationException.class);
		assertThatThrownBy(() -> SagaTaskLabelPolicy.forPatch(List.of("saga:code"), List.of("saga:code", "saga:test")))
				.isInstanceOf(IntegrationException.class);
	}

	@Test
	void patchAllowsSwappingTheOnlyMarkerAndClearingAll() {
		assertThat(SagaTaskLabelPolicy.forPatch(List.of("saga:code"), List.of("saga:test"))).containsExactly("saga:test");
		assertThat(SagaTaskLabelPolicy.forPatch(List.of("saga:code", "backend"), List.of())).isEmpty();
		assertThat(SagaTaskLabelPolicy.forPatch(List.of("saga:code"), null)).isNull();
	}
}
