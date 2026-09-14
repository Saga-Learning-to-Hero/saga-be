package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V18SchemaFollowUpTest {

	@Test
	void v18AddsBranchMembershipSnapshotWithoutTouchingV1ToV17() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v17 = read("/db/migration/V17__task_jira_start_date.sql");
		String v18 = read("/db/migration/V18__git_commit_branch_membership.sql");

		assertFalse(v1.toLowerCase().contains("git_commit_branch"));
		assertFalse(v1.toLowerCase().contains("branch_membership_synced_at"));
		assertFalse(v17.toLowerCase().contains("git_commit_branch"));
		assertFalse(v17.toLowerCase().contains("branch_membership_synced_at"));

		assertTrue(v18.contains("ALTER TABLE git_repo"));
		assertTrue(v18.contains("ADD COLUMN branch_membership_synced_at DATETIME(6) NULL AFTER last_synced_at"));
		assertTrue(v18.contains("CREATE TABLE git_commit_branch"));
		assertTrue(v18.contains("UNIQUE KEY uk_git_commit_branch (git_commit_id, branch_name)"));
		assertTrue(v18.contains("utf8mb4_bin"));
		assertTrue(v18.contains("ON DELETE CASCADE"));
		assertFalse(v18.toUpperCase().contains("DROP TABLE GIT_COMMIT"));
		assertFalse(v18.toUpperCase().contains("DROP TABLE GIT_REPO"));
		assertFalse(v18.contains("V19"));
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V18SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
