package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class V24SchemaFollowUpTest {

	private static final String V1_SHA256 =
			"78959f026ce93d32cbfbd93061fd795fdc065acce8639b3cd1974baa293605b2";

	private static final Pattern VERSIONED_MIGRATION =
			Pattern.compile("^V(\\d+)__.+\\.sql$", Pattern.CASE_INSENSITIVE);

	@Test
	void v1ChecksumRemainsUnchanged() throws Exception {
		assertEquals(V1_SHA256, sha256("/db/migration/V1__initial_schema.sql"));
	}

	@Test
	void v24AddsTaskProvenanceAndProjectCapacityWithoutEditingV1ToV23() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v14 = read("/db/migration/V14__jira_active_scoped_cloud_project_uniqueness.sql");
		String v24 = read("/db/migration/V24__multi_jira_task_provenance.sql");

		assertTrue(v1.contains("UNIQUE KEY uk_jira_integration_project (project_id)"));
		assertTrue(v1.contains("UNIQUE KEY uk_task_project_external_id (project_id, external_id)"));
		assertFalse(extractCreateTable(v1, "task").contains("jira_integration_id"));

		assertTrue(v14.contains("ADD UNIQUE KEY uk_jira_active_cloud_project (active_cloud_id, active_jira_project_id)"));

		for (Path prior : versionedMigrationsThrough(23)) {
			String sql = Files.readString(prior, StandardCharsets.UTF_8);
			String name = prior.getFileName().toString();
			assertFalse(
					sql.contains("ADD COLUMN jira_integration_id"),
					name + " must not add task.jira_integration_id (V24 only)");
			assertFalse(
					sql.contains("fk_task_jira_integration"),
					name + " must not add fk_task_jira_integration (V24 only)");
			assertFalse(
					sql.contains("uk_task_jira_integration_external_id"),
					name + " must not add uk_task_jira_integration_external_id (V24 only)");
			assertFalse(
					sql.contains("ix_task_project_jira_integration"),
					name + " must not add ix_task_project_jira_integration (V24 only)");
			assertFalse(
					sql.contains("DROP INDEX uk_jira_integration_project"),
					name + " must not drop uk_jira_integration_project (V24 only)");
			assertFalse(
					sql.contains("DROP INDEX uk_task_project_external_id"),
					name + " must not drop uk_task_project_external_id (V24 only)");
		}

		assertTrue(v24.contains("DROP INDEX uk_jira_integration_project"));
		assertTrue(v24.contains("ADD KEY ix_jira_integration_project (project_id)"));
		assertTrue(v24.contains("ADD COLUMN jira_integration_id CHAR(36) NULL AFTER project_id"));
		assertTrue(v24.contains("MODIFY COLUMN jira_integration_id CHAR(36) NOT NULL"));
		assertTrue(v24.contains("ADD CONSTRAINT fk_task_jira_integration FOREIGN KEY (jira_integration_id) REFERENCES jira_integration (id)"));
		assertTrue(v24.contains("DROP INDEX uk_task_project_external_id"));
		assertTrue(v24.contains("ADD UNIQUE KEY uk_task_jira_integration_external_id (jira_integration_id, external_id)"));
		assertTrue(v24.contains("ADD KEY ix_task_project_jira_integration (project_id, jira_integration_id)"));
		assertTrue(v24.toUpperCase().contains("UPDATE TASK"));

		assertFalse(v24.contains("DROP INDEX uk_jira_active_cloud_project"));
		assertFalse(v24.contains("DROP KEY uk_jira_active_cloud_project"));
		assertFalse(v24.toUpperCase().contains("DROP TABLE"));
		assertFalse(v24.contains("V1__"));
		assertFalse(v24.contains("V23__"));
	}

	private static String extractCreateTable(String sql, String table) {
		String marker = "CREATE TABLE " + table + " (";
		int start = sql.indexOf(marker);
		assertTrue(start >= 0, "missing " + marker);
		int end = sql.indexOf("ENGINE=InnoDB", start);
		assertTrue(end > start, "missing ENGINE for " + table);
		return sql.substring(start, end);
	}

	private static List<Path> versionedMigrationsThrough(int maxInclusive) throws IOException {
		Path dir = Path.of("src/main/resources/db/migration");
		try (Stream<Path> stream = Files.list(dir)) {
			return stream
					.filter(path -> {
						Matcher matcher = VERSIONED_MIGRATION.matcher(path.getFileName().toString());
						return matcher.matches() && Integer.parseInt(matcher.group(1)) <= maxInclusive;
					})
					.sorted(Comparator.comparingInt(path -> {
						Matcher matcher = VERSIONED_MIGRATION.matcher(path.getFileName().toString());
						matcher.matches();
						return Integer.parseInt(matcher.group(1));
					}))
					.toList();
		}
	}

	private static String sha256(String classpath) throws Exception {
		return MigrationChecksumSupport.sha256Lf(V24SchemaFollowUpTest.class, classpath);
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V24SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
