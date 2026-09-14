package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V17SchemaFollowUpTest {

	@Test
	void v17AddsNullableTaskStartDateMatchingDueDateTypeWithoutIndex() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v16 = read("/db/migration/V16__task_jira_parent_identity.sql");
		String v17 = read("/db/migration/V17__task_jira_start_date.sql");

		// V1-V16 are immutable: due_date stays DATETIME(6) NULL, and no prior migration adds
		// task.start_date.
		assertTrue(v1.contains("due_date DATETIME(6) NULL"));
		assertFalse(v1.contains("start_date DATETIME(6) NULL AFTER due_date"));
		assertFalse(v16.toLowerCase().contains("start_date"));

		assertTrue(v17.contains("ALTER TABLE task"));
		assertTrue(v17.contains("ADD COLUMN start_date DATETIME(6) NULL AFTER due_date"));
		assertFalse(v17.contains("ix_task_start_date"));
		assertFalse(v17.toUpperCase().contains("CREATE INDEX"));
		assertFalse(v17.toUpperCase().contains("ADD INDEX"));
		assertFalse(v17.toUpperCase().contains("ADD UNIQUE"));
		assertFalse(v17.toUpperCase().contains("DELETE FROM TASK"));
		assertFalse(v17.toUpperCase().contains("UPDATE TASK"));
		assertFalse(v17.toUpperCase().contains("DROP TABLE TASK"));
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V17SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
