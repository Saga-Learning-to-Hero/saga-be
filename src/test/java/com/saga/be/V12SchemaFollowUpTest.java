package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V12SchemaFollowUpTest {

	@Test
	void v12AddsGithubProjectInstallationWithoutRewritingPriorMigrations() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v4 = read("/db/migration/V4__integration_identity_audit_attribution_foundation.sql");
		String v12 = read("/db/migration/V12__github_project_installation.sql");
		assertTrue(v1.contains("CREATE TABLE github_installation"));
		assertTrue(v4.contains("fk_github_installation_project"));
		assertTrue(v12.contains("CREATE TABLE github_project_installation"));
		assertTrue(v12.contains("uk_github_project_installation"));
		assertTrue(v12.contains("ix_gpi_project"));
		assertTrue(v12.contains("ix_gpi_installation"));
		assertTrue(v12.contains("fk_gpi_project"));
		assertTrue(v12.contains("fk_gpi_installation"));
		assertTrue(v12.contains("INSERT INTO github_project_installation"));
		assertTrue(v12.contains("NOT EXISTS"));
		assertFalse(v12.contains("DROP COLUMN project_id"));
		assertFalse(v12.toUpperCase().contains("DROP TABLE github_installation"));
		assertFalse(v1.contains("github_project_installation"));
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V12SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
