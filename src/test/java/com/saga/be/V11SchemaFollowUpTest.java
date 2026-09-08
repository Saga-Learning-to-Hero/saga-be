package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V11SchemaFollowUpTest {

	@Test
	void v11AddsJiraEvidenceSourceWithoutRewritingV1() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v9 = read("/db/migration/V9__task_web_link.sql");
		String v10 = read("/db/migration/V10__task_file.sql");
		String v11 = read("/db/migration/V11__jira_task_evidence_source.sql");
		assertFalse(v1.contains("CREATE TABLE task_web_link"));
		assertFalse(v1.contains("CREATE TABLE task_file"));
		assertTrue(v9.contains("created_by_user_id CHAR(36) NOT NULL"));
		assertTrue(v10.contains("created_by_user_id CHAR(36) NOT NULL"));
		assertTrue(v11.contains("ALTER TABLE task_web_link"));
		assertTrue(v11.contains("ALTER TABLE task_file"));
		assertTrue(v11.contains("uk_task_web_link_external"));
		assertTrue(v11.contains("uk_task_file_external"));
		assertTrue(v11.contains("DEFAULT 'SAGA'"));
		assertFalse(v11.contains("DROP TABLE"));
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V11SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
