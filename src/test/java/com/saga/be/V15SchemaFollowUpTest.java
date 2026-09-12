package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V15SchemaFollowUpTest {

	@Test
	void v15ReplacesBlanketRepositoryUniquenessWithActiveScopedUniquenessPlusProjectGuard() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v15 = read("/db/migration/V15__github_active_scoped_repository_uniqueness.sql");

		// V1-V14 are immutable: the original blanket constraint still reads exactly as it always did.
		assertTrue(v1.contains("UNIQUE KEY uk_git_repo_provider_id (provider, repository_id)"));

		// V15 drops that blanket constraint and replaces it with an ACTIVE-scoped one via
		// generated columns, matching the source columns' exact types (VARCHAR(32)/BIGINT), plus a
		// new project-scoped guard on ordinary (mapped) columns.
		assertTrue(v15.contains("DROP INDEX uk_git_repo_provider_id"));
		assertTrue(v15.contains(
				"ADD UNIQUE KEY uk_git_repo_active_provider_repository (active_provider, active_repository_id)"));
		assertTrue(v15.contains(
				"ADD UNIQUE KEY uk_git_repo_project_provider_repository (project_id, provider, repository_id)"));
		assertTrue(v15.contains("active_provider VARCHAR(32)"));
		assertTrue(v15.contains("active_repository_id BIGINT"));
		assertTrue(v15.contains("GENERATED ALWAYS AS"));
		assertTrue(v15.contains("connection_status = 'ACTIVE'"));

		// No historical git_repo row is ever deleted or re-parented by this migration.
		assertFalse(v15.toUpperCase().contains("DELETE FROM GIT_REPO"));
		assertFalse(v15.toUpperCase().contains("UPDATE GIT_REPO"));
		assertFalse(v15.toUpperCase().contains("DROP TABLE GIT_REPO"));
		assertFalse(v15.contains("project_id ="));
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V15SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
