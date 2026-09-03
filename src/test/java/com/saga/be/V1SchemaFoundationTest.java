package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class V1SchemaFoundationTest {

	private static final List<String> TARGET_TABLES = List.of(
			"user_account",
			"student_profile",
			"lecturer_profile",
			"student_course_invitation",
			"subject",
			"academic_class",
			"semester",
			"active_semester_setting",
			"course",
			"course_enrollment",
			"project_type",
			"project",
			"team",
			"team_member",
			"jira_integration",
			"sprint",
			"task",
			"task_attachment",
			"jira_write_operation",
			"github_installation",
			"git_repo",
			"git_issue",
			"pull_request",
			"git_commit",
			"pr_review",
			"comment",
			"task_git_issue_link",
			"git_issue_commit_link",
			"git_issue_pull_request_link",
			"commit_review_intent",
			"commit_review_result",
			"identity_map",
			"identity_mapping_history",
			"webhook_receipt",
			"sync_job_log",
			"peer_review",
			"peer_review_detail",
			"rubric_template",
			"project_group_weight_config",
			"contribution_override",
			"assessment_run",
			"assessment_result",
			"user_notification",
			"notification_broadcast",
			"notification_delivery",
			"firebase_installation",
			"email_outbox",
			"business_warning",
			"ai_agent_delegation_context",
			"ai_agent_conversation_scope",
			"graph_processing_run",
			"outbox_event");

	private static final List<String> EXCLUDED_LEGACY_TABLES = List.of(
			"assessment_evidence",
			"cam_config",
			"peer_review_config",
			"meeting_log",
			"meeting_attendee",
			"commit_file",
			"file_module",
			"ai_interaction_log",
			"risk_alert",
			"task_weight_config",
			"task_web_link",
			"system_audit_log",
			"warning_email_outbox",
			"policy_override_request",
			"commit_data",
			"jira_board");

	@Test
	void v1CreatesExactlyThe52TargetTables() throws IOException {
		String sql = readV1();
		Set<String> created = createdTables(sql);
		assertEquals(52, TARGET_TABLES.size());
		assertEquals(52, created.size(), "V1 created tables: " + created);
		assertEquals(new LinkedHashSet<>(TARGET_TABLES), created);
	}

	@Test
	void v1DoesNotRecreateExcludedLegacyTables() throws IOException {
		String sql = readV1();
		Set<String> created = createdTables(sql);
		for (String excluded : EXCLUDED_LEGACY_TABLES) {
			assertFalse(created.contains(excluded), "excluded table was created: " + excluded);
		}
		assertFalse(created.contains("assessment"));
		assertFalse(created.contains("document"));
		assertFalse(sql.contains("student_uuid_binary"));
		assertTrue(created.contains("assessment_run"));
		assertTrue(created.contains("assessment_result"));
	}

	@Test
	void v1SeedsFourProjectTypes() throws IOException {
		String sql = readV1();
		assertTrue(sql.contains("INSERT INTO project_type"));
		assertTrue(sql.contains("'DESIGN_ARCHITECTURE'"));
		assertTrue(sql.contains("'RESEARCH'"));
		assertTrue(sql.contains("'TESTER'"));
		assertTrue(sql.contains("'DOCUMENT'"));
	}

	private static String readV1() throws IOException {
		try (InputStream in = V1SchemaFoundationTest.class.getResourceAsStream("/db/migration/V1__initial_schema.sql")) {
			assertTrue(in != null, "missing Flyway V1");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static Set<String> createdTables(String sql) {
		Matcher matcher = Pattern.compile("(?i)CREATE TABLE\\s+([a-z0-9_]+)").matcher(sql);
		Set<String> names = new LinkedHashSet<>();
		while (matcher.find()) {
			names.add(matcher.group(1));
		}
		return names;
	}
}
