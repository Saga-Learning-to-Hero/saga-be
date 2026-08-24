package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
