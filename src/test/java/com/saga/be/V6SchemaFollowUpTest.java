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

class V6SchemaFollowUpTest {

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
	void v6AddsRuntimeFoundationWithoutTouchingPriorMigrations() throws IOException {
		String v5 = read("/db/migration/V5__subject_syllabus_academic_foundation.sql");
		String v6 = read("/db/migration/V6__academic_runtime_foundation.sql");
		assertFalse(v5.contains("ALTER TABLE academic_class"));
		assertFalse(v5.contains("ALTER TABLE course"));
		assertFalse(v5.contains("uk_course_class_subject"));
		assertFalse(v5.contains("fk_course_syllabus_subject"));
		assertTrue(v6.contains("uk_syllabus_id_subject"));
		assertTrue(v6.contains("ADD COLUMN semester_id"));
		assertTrue(v6.contains("uk_academic_class_semester_code"));
		assertTrue(v6.contains("uk_academic_class_id_semester"));
		assertTrue(v6.contains("DROP INDEX uk_academic_class_code"));
		assertTrue(v6.contains("ADD COLUMN syllabus_version_id"));
		assertTrue(v6.contains("uk_course_class_subject"));
		assertTrue(v6.contains("fk_course_syllabus_subject"));
		assertTrue(v6.contains("fk_course_class_semester"));
		assertTrue(v6.contains("FOREIGN KEY (syllabus_version_id, subject_id)"));
		assertFalse(v6.contains("CREATE TABLE semester"));
		assertFalse(v6.contains("CREATE TABLE course"));
		assertFalse(v6.contains("CREATE TABLE academic_class"));
		assertFalse(v6.contains("ALTER TABLE subject ADD"));
		assertFalse(v6.contains("ALTER TABLE subject_syllabus_version ADD COLUMN semester"));
		assertFalse(v6.toUpperCase().contains("AUTO_INCREMENT"));
		assertFalse(v6.contains("DROP TABLE"));
		assertFalse(v6.toLowerCase().contains("github"));
		assertFalse(v6.toLowerCase().contains("jira"));
		assertFalse(v6.contains("neo4j"));
		assertFalse(v6.contains("team_member"));
		assertFalse(v6.contains("course_enrollment"));
	}

	private static String sha256(String classpath) throws Exception {
		try (InputStream in = V6SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
			return HexFormat.of().formatHex(digest);
		}
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V6SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
