package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DatabaseFoundationLockTest {

	@Test
	void migrationsHaveNoAutoIncrementAndNoPlaintextPassword() throws IOException {
		String sql = allMigrations();
		assertFalse(sql.toUpperCase().contains("AUTO_INCREMENT"));
		assertFalse(sql.matches("(?s).*\\bpassword\\s+VARCHAR.*"));
		assertFalse(sql.contains("raw_password"));
		assertFalse(sql.contains("password_salt"));
		assertTrue(sql.contains("password_hash VARCHAR(255) NULL"));
		assertTrue(sql.contains("username VARCHAR(64) NULL"));
		assertTrue(sql.contains("google_subject VARCHAR(255) NULL"));
		assertTrue(sql.contains("uk_team_project"));
		assertTrue(sql.contains("fk_comment_task"));
		assertTrue(sql.contains("ix_comment_task"));
	}

	@Test
	void entitiesDoNotUseIdentityGeneration() throws IOException {
		Path entityRoot = Path.of("src/main/java/com/saga/be/entity");
		try (Stream<Path> files = Files.walk(entityRoot)) {
			List<String> identities = files
					.filter(p -> p.toString().endsWith(".java"))
					.map(p -> {
						try {
							return Files.readString(p);
						} catch (IOException e) {
							throw new IllegalStateException(e);
						}
					})
					.filter(source -> source.contains("GenerationType.IDENTITY"))
					.toList();
			assertTrue(identities.isEmpty(), "IDENTITY found in entity sources");
			String base = Files.readString(entityRoot.resolve("BaseEntity.java"));
			assertTrue(base.contains("GenerationType.UUID"));
			assertTrue(base.contains("java.util.UUID"));
		}
	}

	@Test
	void versionedFlywayMigrationsHaveUniqueVersionsThroughV23() throws IOException {
		Pattern versioned = Pattern.compile("^V(\\d+)__.+\\.sql$");
		Map<Integer, List<String>> byVersion = new TreeMap<>();
		try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
			files.map(path -> path.getFileName().toString()).forEach(name -> {
				Matcher matcher = versioned.matcher(name);
				if (matcher.matches()) {
					byVersion
							.computeIfAbsent(Integer.parseInt(matcher.group(1)), unused -> new ArrayList<>())
							.add(name);
				}
			});
		}
		byVersion.forEach((version, names) -> assertEquals(
				1, names.size(), "duplicate Flyway version " + version + ": " + names));
		assertEquals(List.of("V17__task_jira_start_date.sql"), byVersion.get(17));
		assertEquals(List.of("V18__git_commit_branch_membership.sql"), byVersion.get(18));
		assertEquals(List.of("V19__peer_review_default_rubric.sql"), byVersion.get(19));
		assertEquals(List.of("V20__firebase_installation_fcm_token.sql"), byVersion.get(20));
		assertEquals(List.of("V21__audit_project_team_snapshots.sql"), byVersion.get(21));
		assertEquals(List.of("V22__task_native_parent.sql"), byVersion.get(22));
		assertEquals(List.of("V23__git_commit_parent_count.sql"), byVersion.get(23));
		assertEquals(23, byVersion.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
	}

	private static String allMigrations() throws IOException {
		Path dir = Path.of("src/main/resources/db/migration");
		StringBuilder sql = new StringBuilder();
		try (Stream<Path> files = Files.list(dir)) {
			files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().forEach(p -> {
				try {
					sql.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
			});
		}
		return sql.toString();
	}
}
