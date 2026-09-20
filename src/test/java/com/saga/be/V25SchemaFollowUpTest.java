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

class V25SchemaFollowUpTest {

	private static final String V1_SHA256 =
			"78959f026ce93d32cbfbd93061fd795fdc065acce8639b3cd1974baa293605b2";

	private static final Pattern VERSIONED_MIGRATION =
			Pattern.compile("^V(\\d+)__.+\\.sql$", Pattern.CASE_INSENSITIVE);

	@Test
	void v1ChecksumRemainsUnchanged() throws Exception {
		assertEquals(V1_SHA256, sha256("/db/migration/V1__initial_schema.sql"));
	}

	@Test
	void v25AddsFailoverTablesWithoutEditingV1ToV24() throws IOException {
		String v25 = read("/db/migration/V25__jira_task_failover.sql");

		for (Path prior : versionedMigrationsThrough(24)) {
			String sql = Files.readString(prior, StandardCharsets.UTF_8);
			String name = prior.getFileName().toString();
			assertFalse(
					sql.contains("jira_task_failover_run"),
					name + " must not mention jira_task_failover_run (V25 only)");
			assertFalse(
					sql.contains("jira_task_failover_item"),
					name + " must not mention jira_task_failover_item (V25 only)");
			assertFalse(
					sql.contains("fk_jira_failover_run_project"),
					name + " must not add failover FKs (V25 only)");
			assertFalse(
					sql.contains("uk_jira_failover_item_run_source"),
					name + " must not add uk_jira_failover_item_run_source (V25 only)");
		}

		assertTrue(v25.contains("CREATE TABLE jira_task_failover_run"));
		assertTrue(v25.contains("CREATE TABLE jira_task_failover_item"));
		assertTrue(v25.contains("KEY ix_jira_failover_run_project_status (project_id, status)"));
		assertTrue(v25.contains("UNIQUE KEY uk_jira_failover_item_run_source (run_id, source_task_id)"));
		assertTrue(v25.contains("UNIQUE KEY uk_jira_failover_item_outbound_claim (outbound_claim_task_id)"));
		assertTrue(v25.contains("outbound_claim_task_id CHAR(36)"));
		assertTrue(v25.contains("GENERATED ALWAYS AS"));
		assertTrue(v25.contains("'REMOTE_OUTCOME_UNKNOWN'"));
		assertTrue(v25.contains("CONSTRAINT fk_jira_failover_run_project FOREIGN KEY (project_id) REFERENCES project (id)"));
		assertFalse(
				v25.contains("fk_jira_failover_item_target_ji"),
				"item target JI removed — run.target is SoT");
		assertFalse(v25.contains("target_jira_integration_id CHAR(36) NOT NULL,\n  source_status_snapshot"));
		assertTrue(v25.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"));
		assertFalse(v25.toUpperCase().contains("DROP TABLE"));
		assertFalse(v25.contains("V1__"));
		assertFalse(v25.contains("V24__"));
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
		return MigrationChecksumSupport.sha256Lf(V25SchemaFollowUpTest.class, classpath);
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V25SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
