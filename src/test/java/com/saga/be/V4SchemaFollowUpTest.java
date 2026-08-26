package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class V4SchemaFollowUpTest {

	private static final String V1_SHA256 =
			"78959f026ce93d32cbfbd93061fd795fdc065acce8639b3cd1974baa293605b2";
	private static final String V2_SHA256 =
			"7c94ba664aab965ad95390885760f13d598f1e8c1995bb61db78e87ae15e1ee9";

	@Test
	void v1ThroughV3ChecksumsRemainUnchanged() throws Exception {
		assertEquals(V1_SHA256, sha256("/db/migration/V1__initial_schema.sql"));
		assertEquals(V2_SHA256, sha256("/db/migration/V2__user_account_password_hash_and_comment_task.sql"));
	}

	@Test
	void v4AddsFoundationTablesWithoutAutoIncrement() throws IOException {
		String sql = read("/db/migration/V4__integration_identity_audit_attribution_foundation.sql");
		assertTrue(sql.contains("CREATE TABLE audit_log"));
		assertTrue(sql.contains("CREATE TABLE task_work_session"));
		assertTrue(sql.contains("CREATE TABLE contribution_confirmation"));
		assertTrue(sql.contains("CREATE TABLE task_git_commit_link"));
		assertTrue(sql.contains("CREATE TABLE task_pull_request_link"));
		assertTrue(sql.contains("CREATE TABLE webauthn_credential"));
		assertTrue(sql.contains("DROP INDEX uk_identity_user_provider"));
		assertTrue(sql.contains("uk_identity_active_provider_subject"));
		assertTrue(sql.contains("fk_github_installation_project"));
		assertTrue(
				sql.indexOf("ADD KEY ix_identity_user_provider") < sql.indexOf("DROP INDEX uk_identity_user_provider"),
				"Replacement index must exist before dropping uk_identity_user_provider");
		assertFalse(sql.toUpperCase().contains("AUTO_INCREMENT"));
		assertFalse(sql.contains("system_audit_log"));
	}

	private static String sha256(String classpath) throws Exception {
		try (InputStream in = V4SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
			return HexFormat.of().formatHex(digest);
		}
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V4SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
