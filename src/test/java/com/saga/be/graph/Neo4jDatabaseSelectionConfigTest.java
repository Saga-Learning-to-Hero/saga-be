package com.saga.be.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class Neo4jDatabaseSelectionConfigTest {

	@Test
	void blankUsesServerHomeDatabase() {
		assertNull(Neo4jDatabaseSelectionConfig.selectionProvider(null).getDatabaseSelection().getValue());
		assertNull(Neo4jDatabaseSelectionConfig.selectionProvider("").getDatabaseSelection().getValue());
		assertNull(Neo4jDatabaseSelectionConfig.selectionProvider("   ").getDatabaseSelection().getValue());
	}

	@Test
	void namedDatabaseIsPinned() {
		assertEquals("saga", Neo4jDatabaseSelectionConfig.selectionProvider("  saga  ").getDatabaseSelection().getValue());
	}

	@Test
	void reactiveBlankUsesServerHomeDatabase() {
		assertNull(Neo4jDatabaseSelectionConfig.reactiveSelectionProvider(null).getDatabaseSelection().block().getValue());
		assertNull(Neo4jDatabaseSelectionConfig.reactiveSelectionProvider("").getDatabaseSelection().block().getValue());
		assertNull(Neo4jDatabaseSelectionConfig.reactiveSelectionProvider("   ").getDatabaseSelection().block().getValue());
	}

	@Test
	void reactiveNamedDatabaseIsPinned() {
		assertEquals(
				"saga",
				Neo4jDatabaseSelectionConfig.reactiveSelectionProvider("  saga  ").getDatabaseSelection().block().getValue());
	}
}
