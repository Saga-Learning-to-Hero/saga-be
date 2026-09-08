package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V9SchemaFollowUpTest {

	@Test
	void v9AddsTaskWebLinkWithoutRewritingV1() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v9 = read("/db/migration/V9__task_web_link.sql");
		assertFalse(v1.contains("CREATE TABLE task_web_link"));
		assertTrue(v9.contains("CREATE TABLE task_web_link"));
		assertTrue(v9.contains("url_hash CHAR(64) NOT NULL"));
		assertTrue(v9.contains("uk_task_web_link_hash"));
		assertTrue(v9.contains("ON DELETE CASCADE"));
		assertTrue(v9.contains("fk_task_web_link_user"));
		assertFalse(v9.toUpperCase().contains("AUTO_INCREMENT"));
		assertFalse(v9.contains("DROP TABLE"));
		assertFalse(v9.contains("ALTER TABLE task "));
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V9SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}

