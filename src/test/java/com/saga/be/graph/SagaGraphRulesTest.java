package com.saga.be.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.graph.SagaGraphRules.TaskGraphAttrs;
import java.util.List;
import org.junit.jupiter.api.Test;

class SagaGraphRulesTest {

	@Test
	void codeDoneWithoutCommitsIsAnomalyButClassified() {
		TaskGraphAttrs attrs = SagaGraphRules.classify(TaskStatus.DONE, List.of("saga:code"), false, 0);
		assertEquals("CODE", attrs.weightType());
		assertTrue(attrs.classified());
		assertTrue(attrs.anomaly());
	}

	@Test
	void documentWithoutFileIsNotClassified() {
		TaskGraphAttrs attrs = SagaGraphRules.classify(TaskStatus.DONE, List.of("saga:document"), false, 2);
		assertNull(attrs.weightType());
		assertFalse(attrs.classified());
		assertFalse(attrs.anomaly());
	}

	@Test
	void documentWithFileIsClassified() {
		TaskGraphAttrs attrs = SagaGraphRules.classify(TaskStatus.DONE, List.of("saga:document"), true, 0);
		assertEquals("DOCUMENT", attrs.weightType());
		assertTrue(attrs.classified());
		assertFalse(attrs.anomaly());
	}

	@Test
	void ambiguousLabelsAreNotClassified() {
		TaskGraphAttrs attrs =
				SagaGraphRules.classify(TaskStatus.DONE, List.of("saga:code", "saga:test"), false, 0);
		assertNull(attrs.weightType());
		assertFalse(attrs.classified());
		assertFalse(attrs.anomaly());
	}
}
