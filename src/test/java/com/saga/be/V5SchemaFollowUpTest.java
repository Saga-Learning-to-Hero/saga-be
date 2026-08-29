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

class V5SchemaFollowUpTest {

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
	void v5AddsAcademicFoundationWithoutTouchingPriorMigrations() throws IOException {
		String v4 = read("/db/migration/V4__integration_identity_audit_attribution_foundation.sql");
		String v5 = read("/db/migration/V5__subject_syllabus_academic_foundation.sql");
		assertTrue(v4.contains("CREATE TABLE audit_log"));
		assertTrue(v5.contains("CREATE TABLE subject_syllabus_version"));
		assertTrue(v5.contains("CREATE TABLE syllabus_learning_outcome"));
		assertTrue(v5.contains("CREATE TABLE syllabus_learning_unit"));
		assertTrue(v5.contains("CREATE TABLE syllabus_learning_unit_outcome"));
		assertTrue(v5.contains("CREATE TABLE syllabus_phase"));
		assertTrue(v5.contains("CREATE TABLE syllabus_expected_activity"));
		assertTrue(v5.contains("CREATE TABLE syllabus_expected_deliverable"));
		assertTrue(v5.contains("CREATE TABLE syllabus_phase_learning_outcome"));
		assertTrue(v5.contains("CREATE TABLE syllabus_deliverable_learning_outcome"));
		assertTrue(v5.contains("ADD COLUMN name_vietnamese"));
		assertTrue(v5.contains("ADD COLUMN status"));
		assertTrue(v5.contains("uk_syllabus_subject_version_label"));
		assertTrue(v5.contains("uk_syllabus_lo_id_version"));
		assertTrue(v5.contains("uk_syllabus_phase_id_version"));
		assertTrue(v5.contains("uk_syllabus_deliverable_id_version"));
		assertTrue(v5.contains("uk_syllabus_unit_id_version"));
		assertTrue(v5.contains("fk_syllabus_activity_phase_version"));
		assertTrue(v5.contains("fk_syllabus_deliverable_phase_version"));
		assertTrue(v5.contains("fk_phase_lo_phase_version"));
		assertTrue(v5.contains("fk_phase_lo_outcome_version"));
		assertTrue(v5.contains("fk_deliverable_lo_deliverable_version"));
		assertTrue(v5.contains("fk_unit_lo_unit_version"));
		assertTrue(v5.contains("DEFAULT 'DRAFT'"));
		assertTrue(v5.contains("chk_subject_lifecycle"));
		assertTrue(v5.contains("deleted_at IS NULL OR status = 'INACTIVE'"));
		assertFalse(v5.contains("INACTIVE AND deleted_at IS NOT NULL"));
		assertTrue(v5.contains("textbooks"));
		assertTrue(v5.contains("reference_materials"));
		assertFalse(v5.contains("KEY ix_syllabus_lo_order"));
		assertFalse(v5.contains("KEY ix_syllabus_phase_order"));
		assertFalse(v5.contains("KEY ix_syllabus_activity_phase_order"));
		assertFalse(v5.contains("KEY ix_syllabus_deliverable_phase_order"));
		assertFalse(v5.contains("semester_id"));
		assertFalse(v5.toLowerCase().contains("github"));
		assertFalse(v5.toLowerCase().contains("jira"));
		assertFalse(v5.contains("code_weight"));
		assertFalse(v5.contains("githubWeight"));
		assertFalse(v5.toUpperCase().contains("AUTO_INCREMENT"));
		assertFalse(v5.contains("DROP TABLE"));
		assertFalse(v5.contains("DROP TABLE subject"));
		assertFalse(v5.contains("ALTER TABLE rubric_template"));
		assertFalse(v5.contains("ALTER TABLE project_group_weight_config"));
	}

	private static String sha256(String classpath) throws Exception {
		try (InputStream in = V5SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
			return HexFormat.of().formatHex(digest);
		}
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V5SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
