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

class V7SchemaFollowUpTest {

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

	@Test
	void appliedMigrationsV1ThroughV6RemainChecksumLocked() throws Exception {
		assertEquals(V1_SHA256, sha256("/db/migration/V1__initial_schema.sql"));
		assertEquals(V2_SHA256, sha256("/db/migration/V2__user_account_password_hash_and_comment_task.sql"));
		assertEquals(V3_SHA256, sha256("/db/migration/V3__auth_v1_account_identity.sql"));
		assertEquals(V4_SHA256, sha256("/db/migration/V4__integration_identity_audit_attribution_foundation.sql"));
		assertEquals(V5_SHA256, sha256("/db/migration/V5__subject_syllabus_academic_foundation.sql"));
		assertEquals(V6_SHA256, sha256("/db/migration/V6__academic_runtime_foundation.sql"));
	}

	@Test
	void v7DeclaresInvitationIdentityWithoutRewritingPriorMigrations() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v6 = read("/db/migration/V6__academic_runtime_foundation.sql");
		String v7 = read("/db/migration/V7__course_roster_invitation_identity.sql");
		assertTrue(v1.contains("CREATE TABLE student_course_invitation"));
		assertTrue(v1.contains("student_profile_id CHAR(36) NOT NULL"));
		assertFalse(v1.contains("uk_invitation_course_email"));
		assertFalse(v6.contains("ALTER TABLE student_course_invitation"));
		assertTrue(v7.contains("MODIFY student_profile_id CHAR(36) NULL"));
		assertTrue(v7.contains("ADD COLUMN email VARCHAR(255) NULL"));
		assertTrue(v7.contains("ADD COLUMN student_code VARCHAR(64) NULL"));
		assertTrue(v7.contains("ADD COLUMN full_name VARCHAR(255) NULL"));
		assertTrue(v7.contains("uk_invitation_course_email"));
		assertTrue(v7.contains("uk_invitation_course_student_code"));
		assertTrue(v7.contains("chk_invitation_identity"));
		assertTrue(v7.contains("student_profile_id IS NOT NULL"));
		assertTrue(v7.contains("TRIM(email) <> ''"));
		assertTrue(v7.contains("TRIM(student_code) <> ''"));
		assertTrue(v7.contains("LOWER(TRIM(ua.email))"));
		assertTrue(v7.contains("UPPER(TRIM(sp.student_code))"));
		assertFalse(v7.contains("CREATE TABLE"));
		assertFalse(v7.contains("DROP TABLE"));
		assertFalse(v7.toUpperCase().contains("AUTO_INCREMENT"));
		assertFalse(v7.toLowerCase().contains("github"));
		assertFalse(v7.toLowerCase().contains("jira"));
		assertFalse(v7.contains("neo4j"));
		assertFalse(v7.contains("team_member"));
		assertFalse(v7.contains("ALTER TABLE course_enrollment"));
		assertFalse(v7.contains("ALTER TABLE user_account"));
		assertFalse(v7.contains("member_code"));
	}

	private static String sha256(String classpath) throws Exception {
		try (InputStream in = V7SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
			return HexFormat.of().formatHex(digest);
		}
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V7SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
