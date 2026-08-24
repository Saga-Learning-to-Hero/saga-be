package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V2SchemaFollowUpTest {

	@Test
	void v2DoesNotCreateTablesAndAddsCredentialAndCommentTask() throws IOException {
		String sql = read("/db/migration/V2__user_account_password_hash_and_comment_task.sql");
		assertFalse(sql.toLowerCase().contains("create table"));
		assertFalse(sql.toLowerCase().contains("refresh_token"));
		assertFalse(sql.toLowerCase().contains("session"));
		assertTrue(sql.contains("password_hash"));
		assertTrue(sql.contains("VARCHAR(255)"));
		assertTrue(sql.contains("task_id"));
		assertTrue(sql.contains("fk_comment_task"));
	}

	@Test
	void v1RemainsImmutableUuidBaseline() throws IOException {
		String sql = read("/db/migration/V1__initial_schema.sql");
		assertTrue(sql.contains("CREATE TABLE user_account"));
		assertTrue(sql.contains("PRIMARY KEY (singleton_id)"));
		assertFalse(sql.contains("password_hash"));
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V2SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
