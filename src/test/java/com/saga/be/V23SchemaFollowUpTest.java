package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V23SchemaFollowUpTest {

	private static final String V1_SHA256 =
			"78959f026ce93d32cbfbd93061fd795fdc065acce8639b3cd1974baa293605b2";

	@Test
	void v1ChecksumRemainsUnchanged() throws Exception {
		assertEquals(V1_SHA256, sha256("/db/migration/V1__initial_schema.sql"));
	}

	@Test
	void v23AddsNullableParentCountWithoutDefaultOrIndex() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v22 = read("/db/migration/V22__task_native_parent.sql");
		String v23 = read("/db/migration/V23__git_commit_parent_count.sql");

		assertTrue(v1.contains("CREATE TABLE git_commit"));
		assertFalse(v1.contains("parent_count"));
		assertFalse(v22.contains("parent_count"));

		assertTrue(v23.contains("ADD COLUMN parent_count INT NULL"));
		assertFalse(v23.toUpperCase().contains("DEFAULT 1"));
		assertFalse(v23.toUpperCase().contains("NOT NULL"));
		assertFalse(v23.toUpperCase().contains("CREATE INDEX"));
		assertFalse(v23.toUpperCase().contains("ADD KEY"));
		assertFalse(v23.toUpperCase().contains("ADD INDEX"));
		assertFalse(v23.toUpperCase().contains("UPDATE GIT_COMMIT"));
		assertFalse(v23.contains("V22"));
	}

	private static String sha256(String classpath) throws Exception {
		return MigrationChecksumSupport.sha256Lf(V23SchemaFollowUpTest.class, classpath);
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V23SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
