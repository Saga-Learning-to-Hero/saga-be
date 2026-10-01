package com.saga.be.mail.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.config.AuthProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmailTemplateServiceTest {

	private EmailTemplateService templates;

	@BeforeEach
	void setUp() {
		AuthProperties auth = new AuthProperties();
		auth.setFrontendOrigins(List.of("http://localhost:3000"));
		auth.getGoogle().setSuccessUrl("http://localhost:3000/dashboard");
		auth.getGoogle().setFailureUrl("http://localhost:3000/login");
		templates = new EmailTemplateService(auth);
	}

	@Test
	void enrolledContainsEscapedValuesCtaAndTextFallback() {
		EmailTemplateModel model = EmailTemplateModel.course(
				"Nguyễn Văn Ánh",
				"student@gmail.com",
				"Software Development Project",
				"SWP391",
				"SE1705",
				"FA26",
				"Fall 2026",
				false);
		EmailTemplate rendered = templates.render(EmailTemplateService.COURSE_ENROLLED, model);
		assertEquals("SAGA — Bạn đã được thêm vào lớp học phần SWP391", rendered.subject());
		assertTrue(rendered.textBody().contains("Nguyễn Văn Ánh"));
		assertTrue(rendered.textBody().contains("SE1705"));
		assertTrue(rendered.textBody().contains("FA26"));
		assertTrue(rendered.textBody().startsWith("Xin chào Nguyễn Văn Ánh,"));
		assertTrue(rendered.textBody().contains("Mở SAGA: http://localhost:3000/dashboard"));
		assertTrue(rendered.htmlBody().contains("Bạn đã được thêm vào lớp học phần"));
		assertTrue(rendered.htmlBody().contains("Nguyễn Văn Ánh"));
		assertTrue(rendered.htmlBody().contains("SWP391 - Software Development Project"));
		assertTrue(rendered.htmlBody().contains("SE1705"));
		assertTrue(rendered.htmlBody().contains("FA26"));
		assertTrue(rendered.htmlBody().contains("http://localhost:3000/dashboard"));
		assertTrue(rendered.htmlBody().contains("Mở SAGA"));
		assertFalse(rendered.htmlBody().isBlank());
		assertFalse(rendered.textBody().isBlank());
		assertNoUuid(rendered, UUID.randomUUID());
	}

	@Test
	void institutionalInvitationUsesGoogleWordingAndShowsInvitedEmail() {
		EmailTemplateModel model = EmailTemplateModel.course(
				"FPT Student",
				"anvse170102@fpt.edu.vn",
				"Software Development Project",
				"SWP391",
				"SE1705",
				"FA26",
				"Fall 2026",
				true);
		EmailTemplate rendered = templates.render(EmailTemplateService.COURSE_INVITATION, model);
		assertEquals("SAGA — Lời mời tham gia lớp học phần SWP391", rendered.subject());
		assertTrue(rendered.htmlBody().contains("Bạn được mời tham gia SAGA"));
		assertTrue(rendered.htmlBody().contains("Đăng nhập bằng Google của trường"));
		assertTrue(rendered.htmlBody().contains("anvse170102@fpt.edu.vn"));
		assertTrue(rendered.htmlBody().contains("http://localhost:3000/login"));
		assertTrue(rendered.textBody().contains("đăng nhập bằng email trường"));
		assertTrue(rendered.textBody().contains("anvse170102@fpt.edu.vn"));
		assertFalse(rendered.textBody().toLowerCase().contains("create a local password"));
		assertFalse(rendered.htmlBody().toLowerCase().contains("create a local password"));
		assertFalse(rendered.htmlBody().contains("http://localhost:3000/register"));
	}

	@Test
	void personalInvitationUsesPublicStudentOnboarding() {
		EmailTemplateModel model = EmailTemplateModel.course(
				"New Student",
				"new@gmail.com",
				"Software Development Project",
				"SWP391",
				"SE1705",
				"FA26",
				null,
				false);
		EmailTemplate rendered = templates.render(EmailTemplateService.COURSE_INVITATION, model);
		assertTrue(rendered.htmlBody().contains("http://localhost:3000/register"));
		assertTrue(rendered.htmlBody().contains("Tạo tài khoản sinh viên SAGA"));
		assertTrue(rendered.textBody().contains("đăng ký tài khoản sinh viên SAGA bằng email này"));
		assertTrue(rendered.htmlBody().contains("new@gmail.com"));
	}

	@Test
	void dynamicValuesAreHtmlEscaped() {
		EmailTemplateModel model = EmailTemplateModel.course(
				"<script>alert(1)</script>",
				"evil@example.com",
				"SWP & <b>Project</b>",
				"SWP\"391",
				"SE<script>",
				"FA&26",
				null,
				false);
		EmailTemplate rendered = templates.render(EmailTemplateService.COURSE_ENROLLED, model);
		assertFalse(rendered.htmlBody().contains("<script>"));
		assertFalse(rendered.htmlBody().contains("<b>Project</b>"));
		assertTrue(rendered.htmlBody().contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
		assertTrue(rendered.htmlBody().contains("SWP &amp; &lt;b&gt;Project&lt;/b&gt;"));
		assertTrue(rendered.htmlBody().contains("SE&lt;script&gt;"));
		assertTrue(rendered.htmlBody().contains("FA&amp;26"));
		assertTrue(rendered.textBody().contains("<script>alert(1)</script>"));
	}

	@Test
	void payloadHasRenderedBodiesAndNoInternalIds() {
		EmailTemplateModel model = EmailTemplateModel.course(
				"A", "a@gmail.com", "Software Development Project", "SWP391", "SE1705", "FA26", "Fall", false);
		Map<String, Object> payload = templates.payload(EmailTemplateService.COURSE_ENROLLED, model);
		assertEquals("SAGA — Bạn đã được thêm vào lớp học phần SWP391", payload.get("subject"));
		assertTrue(String.valueOf(payload.get("htmlBody")).contains("Bạn đã được thêm vào lớp học phần"));
		assertTrue(String.valueOf(payload.get("textBody")).contains("SE1705"));
		assertEquals("http://localhost:3000/dashboard", payload.get("ctaUrl"));
		assertFalse(payload.containsKey("courseId"));
		assertFalse(payload.containsKey("userId"));
		assertFalse(payload.containsKey("invitationId"));
		assertFalse(String.valueOf(payload.get("htmlBody")).contains("id="));
	}

	@Test
	void teamAssignedContainsTeamDetailsAndTextFallback() {
		EmailTemplateModel model = EmailTemplateModel.teamAssigned(
				"Nguyễn Văn Ánh",
				"student@gmail.com",
				"Software Development Project",
				"SWP391",
				"SE1705",
				"FA26",
				"Fall 2026",
				1,
				"Alpha",
				"Leader");
		EmailTemplate rendered = templates.render(EmailTemplateService.TEAM_ASSIGNED, model);
		assertEquals("SAGA — Bạn đã được xếp nhóm trong lớp học phần SWP391", rendered.subject());
		assertTrue(rendered.textBody().contains("Nguyễn Văn Ánh"));
		assertTrue(rendered.textBody().contains("Nhóm: Nhóm 1 — Alpha"));
		assertTrue(rendered.textBody().contains("Vai trò: Trưởng nhóm"));
		assertTrue(rendered.textBody().contains("Mở SAGA: http://localhost:3000/dashboard"));
		assertTrue(rendered.htmlBody().contains("Bạn đã được xếp vào nhóm"));
		assertTrue(rendered.htmlBody().contains("Nhóm 1 — Alpha"));
		assertTrue(rendered.htmlBody().contains("Trưởng nhóm"));
		assertTrue(rendered.htmlBody().contains("http://localhost:3000/dashboard"));
		assertFalse(rendered.htmlBody().contains("student@gmail.com") && rendered.htmlBody().contains("mailto:"));
		Map<String, Object> payload = templates.payload(EmailTemplateService.TEAM_ASSIGNED, model);
		assertEquals("1", payload.get("teamNo"));
		assertEquals("Alpha", payload.get("teamName"));
		assertEquals("Leader", payload.get("teamRole"));
	}

	@Test
	void configuredFrontendOriginIsUsedWithoutHardcodedHostWhenOverridden() {
		AuthProperties auth = new AuthProperties();
		auth.setFrontendOrigins(List.of("https://app.saga.example/"));
		auth.getGoogle().setSuccessUrl("https://app.saga.example/home");
		auth.getGoogle().setFailureUrl("https://app.saga.example/signin");
		EmailTemplateService custom = new EmailTemplateService(auth);
		EmailTemplate enrolled = custom.render(
				EmailTemplateService.COURSE_ENROLLED,
				EmailTemplateModel.course("A", "a@x.com", "Course", "SWP391", "SE1705", "FA26", null, false));
		EmailTemplate invite = custom.render(
				EmailTemplateService.COURSE_INVITATION,
				EmailTemplateModel.course("A", "a@fpt.edu.vn", "Course", "SWP391", "SE1705", "FA26", null, true));
		assertTrue(enrolled.htmlBody().contains("https://app.saga.example/home"));
		assertTrue(invite.htmlBody().contains("https://app.saga.example/signin"));
		assertFalse(enrolled.htmlBody().contains("http://localhost:3000"));
	}

	@Test
	void memberRoleAndRemovalActorAreShownInVietnamese() {
		assertEquals("Thành viên", EmailTemplateService.roleVi("Member"));
		assertEquals("Thành viên", EmailTemplateService.roleVi(null));
		assertEquals("Trưởng nhóm", EmailTemplateService.roleVi("LEADER"));
		assertEquals("Quản trị viên", EmailTemplateService.removedByVi("Admin"));
		assertEquals("Giảng viên", EmailTemplateService.removedByVi("Lecturer"));
		assertEquals("Giảng viên phụ trách lớp", EmailTemplateService.removedByVi(" "));
	}

	@Test
	void sprintOverlapEmailIsVietnamese() {
		Map<String, Object> payload = templates.sprintPeriodOverlapPayload(
				"Lê Leader", "leader@fpt.edu.vn", "SAGA Project", "SE1802",
				"\"Sprint 1\" — site-a (01/09/2026 → 14/09/2026, đã đóng)",
				"\"Sprint 2\" — site-b (10/09/2026 → 24/09/2026, đang chạy)");
		assertEquals("SAGA — Sprint bị chồng thời gian trong dự án SAGA Project", payload.get("subject"));
		String text = String.valueOf(payload.get("textBody"));
		assertTrue(text.startsWith("Xin chào Lê Leader,"));
		assertTrue(text.contains("Dự án: SAGA Project\nLớp: SE1802\nSprint 1: \"Sprint 1\""));
		assertTrue(text.contains("Hãy chỉnh lại ngày hoặc đóng một sprint trên Jira."));
		assertTrue(String.valueOf(payload.get("htmlBody")).contains("Sprint bị chồng thời gian"));
	}

	@Test
	void studentRemovalEmailIsVietnameseAndEscapesTheReason() {
		EmailTemplateModel model = EmailTemplateModel.teamAssigned(
				"Nguyễn Văn Ánh", "student@gmail.com", "Software Requirement", "SWR302", "SE1802", "FA26", "Fall 2026",
				2, "Beta", null);
		Map<String, Object> course = templates.studentRemovalPayload(model, false, "Chuyển <b>lớp</b>", "Lecturer");
		assertEquals("SAGA — Bạn đã bị rút khỏi lớp học phần SWR302", course.get("subject"));
		assertTrue(String.valueOf(course.get("textBody")).contains("Người thực hiện: Giảng viên\nLý do: Chuyển <b>lớp</b>"));
		String html = String.valueOf(course.get("htmlBody"));
		assertTrue(html.contains("Bạn đã bị rút khỏi lớp học phần"));
		assertTrue(html.contains("Chuyển &lt;b&gt;lớp&lt;/b&gt;"));
		assertFalse(html.contains("<b>lớp</b>"));

		Map<String, Object> team = templates.studentRemovalPayload(model, true, null, "Admin");
		assertEquals("SAGA — Bạn đã bị rút khỏi nhóm trong lớp học phần SWR302", team.get("subject"));
		assertTrue(String.valueOf(team.get("textBody")).contains("Nhóm: Nhóm 2 — Beta"));
		assertTrue(String.valueOf(team.get("textBody")).contains("Người thực hiện: Quản trị viên\nLý do: Không có"));
		assertTrue(String.valueOf(team.get("textBody")).contains("Bạn vẫn còn trong lớp học phần"));
	}

	@Test
	void noTemplateLeavesEnglishBoilerplate() {
		EmailTemplateModel model = EmailTemplateModel.teamAssigned(
				"A", "a@fpt.edu.vn", "Course", "SWP391", "SE1705", "FA26", "Fall 2026", 1, "Alpha", "Member");
		List<String> bodies = new java.util.ArrayList<>();
		for (String key : List.of(
				EmailTemplateService.COURSE_ENROLLED, EmailTemplateService.COURSE_INVITATION,
				EmailTemplateService.TEAM_ASSIGNED, EmailTemplateService.DEV_SMOKE)) {
			EmailTemplate rendered = templates.render(key, model);
			bodies.add(rendered.subject());
			bodies.add(rendered.textBody());
			bodies.add(rendered.htmlBody());
		}
		for (Map<String, Object> payload : List.of(
				templates.sprintPeriodOverlapPayload("A", "a@x.com", "P", "SE1", "s1", "s2"),
				templates.studentRemovalPayload(model, false, "r", "Admin"),
				templates.studentRemovalPayload(model, true, "r", "Lecturer"))) {
			bodies.add(String.valueOf(payload.get("subject")));
			bodies.add(String.valueOf(payload.get("textBody")));
			bodies.add(String.valueOf(payload.get("htmlBody")));
		}
		for (String body : bodies) {
			for (String english : List.of(
					"Open SAGA", "Hello", "automated email", "Course:", "Class:", "Semester:", "Team:", "Role:",
					"Reason", "Removed by", "You were", "You've", "You're", "invited", "Project:", "Recipient",
					"lang=\"en\"")) {
				assertFalse(body.contains(english), () -> "English \"" + english + "\" left in: " + body);
			}
		}
		assertTrue(bodies.stream().filter(b -> b.contains("<html")).allMatch(b -> b.contains("lang=\"vi\"")
				&& b.contains("Đây là email tự động, vui lòng không trả lời.")));
	}

	@Test
	void unknownTemplateIsRejected() {
		assertThrows(
				IllegalArgumentException.class,
				() -> templates.render("unknown-template", EmailTemplateModel.course("", "", "", "", "", "", "", false)));
	}

	private static void assertNoUuid(EmailTemplate rendered, UUID unexpected) {
		assertFalse(rendered.subject().contains(unexpected.toString()));
		assertFalse(rendered.textBody().contains(unexpected.toString()));
		assertFalse(rendered.htmlBody().contains(unexpected.toString()));
	}
}
