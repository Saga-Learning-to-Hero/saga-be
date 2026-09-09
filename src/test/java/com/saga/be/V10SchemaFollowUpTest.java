package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V10SchemaFollowUpTest {

	@Test
	void v10AddsTaskFileWithoutRewritingV1() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v10 = read("/db/migration/V10__task_file.sql");
		assertFalse(v1.contains("CREATE TABLE task_file"));
		assertTrue(v10.contains("CREATE TABLE task_file"));
		assertTrue(v10.contains("content_hash CHAR(64) NOT NULL"));
		assertTrue(v10.contains("uk_task_file_hash"));
		assertTrue(v10.contains("ON DELETE CASCADE"));
		assertTrue(v10.contains("fk_task_file_user"));
		assertFalse(v10.toUpperCase().contains("AUTO_INCREMENT"));
		assertFalse(v10.contains("DROP TABLE"));
		assertFalse(v10.contains("ALTER TABLE task "));
		assertFalse(v10.contains("ALTER TABLE task_attachment"));
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V10SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
