package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V14SchemaFollowUpTest {

	@Test
	void v14ReplacesBlanketCloudProjectUniquenessWithActiveScopedUniquenessOnly() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v14 = read("/db/migration/V14__jira_active_scoped_cloud_project_uniqueness.sql");

		// V1-V13 are immutable: the original blanket constraint still reads exactly as it always did.
		assertTrue(v1.contains("UNIQUE KEY uk_jira_cloud_project (cloud_id, jira_project_id)"));

		// V14 drops that blanket constraint and replaces it with an ACTIVE-scoped one via
		// generated columns, matching the source columns' exact types (VARCHAR(128)/VARCHAR(64)).
		assertTrue(v14.contains("DROP INDEX uk_jira_cloud_project"));
		assertTrue(v14.contains("ADD UNIQUE KEY uk_jira_active_cloud_project (active_cloud_id, active_jira_project_id)"));
		assertTrue(v14.contains("active_cloud_id VARCHAR(128)"));
		assertTrue(v14.contains("active_jira_project_id VARCHAR(64)"));
		assertTrue(v14.contains("GENERATED ALWAYS AS"));
		assertTrue(v14.contains("connection_status = 'ACTIVE'"));

		// No historical jira_integration row is ever deleted or re-parented by this migration.
		assertFalse(v14.toUpperCase().contains("DELETE FROM JIRA_INTEGRATION"));
		assertFalse(v14.toUpperCase().contains("UPDATE JIRA_INTEGRATION"));
		assertFalse(v14.toUpperCase().contains("DROP TABLE JIRA_INTEGRATION"));
		assertFalse(v14.contains("project_id ="));
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V14SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
