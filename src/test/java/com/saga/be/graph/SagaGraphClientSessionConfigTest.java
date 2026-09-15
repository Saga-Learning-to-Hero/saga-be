package com.saga.be.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.SessionConfig;

class SagaGraphClientSessionConfigTest {

	@Test
	void blankUsesHomeDatabase() {
		assertNull(SagaGraphClient.sessionConfig("").database().orElse(null));
		assertNull(SagaGraphClient.sessionConfig("   ").database().orElse(null));
		assertNull(SagaGraphClient.sessionConfig(null).database().orElse(null));
	}

	@Test
	void namedDatabaseIsPinned() {
		assertEquals("45839acd", SagaGraphClient.sessionConfig("45839acd").database().orElseThrow());
		SessionConfig local = SagaGraphClient.sessionConfig("neo4j");
		assertEquals("neo4j", local.database().orElseThrow());
	}
}
