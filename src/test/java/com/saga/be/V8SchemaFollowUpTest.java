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

class V8SchemaFollowUpTest {

	private static final String V1_SHA256 =
			"78959f026ce93d32cbfbd93061fd795fdc065acce8639b3cd1974baa293605b2";
	private static final String V2_SHA256 =
			"7c94ba664aab965ad95390885760f13d598f1e8c1995bb61db78e87ae15e1ee9";
	private static final String V3_SHA256 =
			"37ce6b011f8f9bd32e8a9ad962281fda0f42dae7aae3cc278159ed0d4b20cc19";
	private static final String V4_SHA256 =
			"f00c45cd679cf3e659c10f21a6412fadcb2b2be09b22cfbcfb680d86567ea418";
	private static final String V5_SHA256 =
			"ceb3f667ee74f9e0c45cccf9b3033b9092597551dee2c9669d167dd146ec21e9";
	private static final String V6_SHA256 =
			"7d803f2eea3f743ed6d1f4c5c0f1e2a865a88a2d9e545a91834cb7aa7225feec";
	private static final String V7_SHA256 =
			"098b9778397094c1ff0bdf9792bf58bc009fda98439efe5d3d639659f281176d";

	@Test
	void appliedMigrationsV1ThroughV7RemainChecksumLocked() throws Exception {
		assertEquals(V1_SHA256, sha256("/db/migration/V1__initial_schema.sql"));
		assertEquals(V2_SHA256, sha256("/db/migration/V2__user_account_password_hash_and_comment_task.sql"));
		assertEquals(V3_SHA256, sha256("/db/migration/V3__auth_v1_account_identity.sql"));
		assertEquals(V4_SHA256, sha256("/db/migration/V4__integration_identity_audit_attribution_foundation.sql"));
		assertEquals(V5_SHA256, sha256("/db/migration/V5__subject_syllabus_academic_foundation.sql"));
		assertEquals(V6_SHA256, sha256("/db/migration/V6__academic_runtime_foundation.sql"));
		assertEquals(V7_SHA256, sha256("/db/migration/V7__course_roster_invitation_identity.sql"));
	}

	@Test
	void v8EvolvesTeamWithoutRewritingPriorMigrations() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v8 = read("/db/migration/V8__lecturer_team_management.sql");
		assertTrue(v1.contains("CREATE TABLE team"));
		assertTrue(v1.contains("project_id CHAR(36) NOT NULL"));
		assertTrue(v1.contains("UNIQUE KEY uk_team_project (project_id)"));
		assertTrue(v1.contains("CONSTRAINT fk_team_project FOREIGN KEY (project_id) REFERENCES project (id)"));
		assertTrue(v1.contains("UNIQUE KEY uk_team_member_enrollment (team_id, course_enrollment_id)"));
		assertFalse(v1.contains("team_no"));
		assertFalse(v1.contains("uk_team_course_team_no"));
		assertFalse(v1.contains("uk_team_member_enrollment_once"));
		assertTrue(v8.contains("ADD COLUMN team_no INT NULL"));
		assertTrue(v8.contains("MODIFY team_no INT NOT NULL"));
		assertTrue(v8.contains("uk_team_course_team_no"));
		assertTrue(v8.contains("MODIFY project_id CHAR(36) NULL"));
		assertTrue(v8.contains("uk_team_member_enrollment_once"));
		assertTrue(v8.contains("course_enrollment_id"));
		assertFalse(v8.contains("DROP FOREIGN KEY fk_team_project"));
		assertFalse(v8.contains("DROP INDEX uk_team_project"));
		assertFalse(v8.contains("uk_team_course_name"));
		assertFalse(v8.contains("CREATE TABLE"));
		assertFalse(v8.contains("DROP TABLE"));
		assertFalse(v8.toUpperCase().contains("AUTO_INCREMENT"));
		assertFalse(v8.toLowerCase().contains("github"));
		assertFalse(v8.toLowerCase().contains("jira"));
		assertFalse(v8.contains("INSERT INTO project"));
		assertFalse(v8.contains("CREATE TABLE course_team"));
	}

	private static String sha256(String classpath) throws Exception {
		try (InputStream in = V8SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
			return HexFormat.of().formatHex(digest);
		}
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V8SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
