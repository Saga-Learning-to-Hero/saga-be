package com.saga.be.workload;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkloadRoutePatternsTest {

	@Test
	void keepsSpringMappingPlaceholders() {
		assertThat(WorkloadRoutePatterns.normalize("/api/projects/{projectId}/tasks"))
				.isEqualTo("/api/projects/{projectId}/tasks");
	}

	@Test
	void stripsUuidInstanceValues() {
		UUID id = UUID.fromString("5b99c0a0-1111-2222-3333-444444444444");
		String raw = "/api/projects/" + id + "/tasks";
		String normalized = WorkloadRoutePatterns.normalize(raw);
		assertThat(normalized).isEqualTo("/api/projects/{id}/tasks");
		assertThat(WorkloadRoutePatterns.containsUuidInstance(normalized)).isFalse();
		assertThat(normalized).doesNotContain(id.toString());
	}

	@Test
	void missingPatternIsUnmappedNotRawUri() {
		assertThat(WorkloadRoutePatterns.normalize(null)).isEqualTo("unmapped");
		assertThat(WorkloadRoutePatterns.normalize("")).isEqualTo("unmapped");
		assertThat(WorkloadRoutePatterns.normalize("   ")).isEqualTo("unmapped");
	}
}
