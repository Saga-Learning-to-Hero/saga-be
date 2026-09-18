package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V22SchemaFollowUpTest {

	private static final String V1_SHA256 =
			"78959f026ce93d32cbfbd93061fd795fdc065acce8639b3cd1974baa293605b2";
	private static final String V21_SHA256 =
			"71cb9d5af338fe5978aefb811f78abba69b9d1999697e3593396d3b1c118772c";

	@Test
	void v1AndV21ChecksumsRemainUnchanged() throws Exception {
		assertEquals(V1_SHA256, sha256("/db/migration/V1__initial_schema.sql"));
		assertEquals(V21_SHA256, sha256("/db/migration/V21__audit_project_team_snapshots.sql"));
	}

	@Test
	void v22AddsNullableNativeParentWithoutTouchingV1ToV21() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v16 = read("/db/migration/V16__task_jira_parent_identity.sql");
		String v21 = read("/db/migration/V21__audit_project_team_snapshots.sql");
		String v22 = read("/db/migration/V22__task_native_parent.sql");

		assertTrue(v1.contains("CONSTRAINT fk_task_blocks FOREIGN KEY (blocks_task_id) REFERENCES task (id)"));
		assertFalse(v1.contains("parent_task_id"));
		assertTrue(v16.contains("parent_external_id"));
		assertTrue(v16.contains("parent_external_key"));
		assertFalse(v16.contains("parent_task_id"));
		assertFalse(v21.contains("parent_task_id"));
		assertFalse(v21.contains("V22"));

		assertTrue(v22.contains("ADD COLUMN parent_task_id CHAR(36) NULL"));
		assertTrue(v22.contains("ADD KEY ix_task_parent_task_id (parent_task_id)"));
		assertTrue(v22.contains("ADD CONSTRAINT fk_task_parent_task FOREIGN KEY (parent_task_id) REFERENCES task (id)"));
		assertFalse(v22.toUpperCase().contains("ON DELETE CASCADE"));
		assertFalse(v22.toUpperCase().contains("ON DELETE SET NULL"));
		assertFalse(v22.toUpperCase().contains("UPDATE TASK"));
		assertFalse(v22.toUpperCase().contains("DELETE FROM TASK"));
		assertFalse(v22.toUpperCase().contains("ADD COLUMN PARENT_EXTERNAL"));
		assertFalse(v22.toUpperCase().contains("ADD COLUMN BLOCKS_TASK"));
		assertFalse(v22.toUpperCase().contains("AUTO_INCREMENT"));
	}

	private static String sha256(String classpath) throws Exception {
		return MigrationChecksumSupport.sha256Lf(V22SchemaFollowUpTest.class, classpath);
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V22SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
