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

class V3SchemaFollowUpTest {

	private static final String V1_SHA256 =
			"78959f026ce93d32cbfbd93061fd795fdc065acce8639b3cd1974baa293605b2";
	private static final String V2_SHA256 =
			"7c94ba664aab965ad95390885760f13d598f1e8c1995bb61db78e87ae15e1ee9";

	@Test
	void v3AddsUsernameAndGoogleSubjectWithoutNewTables() throws IOException {
		String sql = read("/db/migration/V3__auth_v1_account_identity.sql");
		assertFalse(sql.toLowerCase().contains("create table"));
		assertTrue(sql.contains("username VARCHAR(64) NULL"));
		assertTrue(sql.contains("google_subject VARCHAR(255) NULL"));
		assertTrue(sql.contains("uk_user_account_username"));
		assertTrue(sql.contains("uk_user_account_google_subject"));
		assertFalse(sql.toLowerCase().contains("auth_identity"));
		assertFalse(sql.toLowerCase().contains("refresh_token"));
		assertFalse(sql.contains("CREATE TABLE"));
	}

	@Test
	void v1AndV2ChecksumsRemainUnchanged() throws Exception {
		assertEquals(V1_SHA256, sha256("/db/migration/V1__initial_schema.sql"));
		assertEquals(V2_SHA256, sha256("/db/migration/V2__user_account_password_hash_and_comment_task.sql"));
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v2 = read("/db/migration/V2__user_account_password_hash_and_comment_task.sql");
		assertFalse(v1.contains("google_subject"));
		assertFalse(v1.contains("username VARCHAR(64)"));
		assertFalse(v2.contains("google_subject"));
		assertFalse(v2.contains("username VARCHAR(64)"));
		assertTrue(v2.contains("password_hash VARCHAR(255) NULL"));
	}

	private static String sha256(String classpath) throws Exception {
		try (InputStream in = V3SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
			return HexFormat.of().formatHex(digest);
		}
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V3SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
