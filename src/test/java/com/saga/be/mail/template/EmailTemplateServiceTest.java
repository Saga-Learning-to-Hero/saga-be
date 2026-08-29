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
		assertEquals("SAGA — You were added to SWP391", rendered.subject());
		assertTrue(rendered.textBody().contains("Nguyễn Văn Ánh"));
		assertTrue(rendered.textBody().contains("SE1705"));
		assertTrue(rendered.textBody().contains("FA26"));
		assertTrue(rendered.textBody().contains("Open SAGA: http://localhost:3000/dashboard"));
		assertTrue(rendered.htmlBody().contains("You've been added to a course"));
		assertTrue(rendered.htmlBody().contains("Nguyễn Văn Ánh"));
		assertTrue(rendered.htmlBody().contains("SWP391 - Software Development Project"));
		assertTrue(rendered.htmlBody().contains("SE1705"));
		assertTrue(rendered.htmlBody().contains("FA26"));
		assertTrue(rendered.htmlBody().contains("http://localhost:3000/dashboard"));
		assertTrue(rendered.htmlBody().contains("Open SAGA"));
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
		assertEquals("SAGA — Course invitation for SWP391", rendered.subject());
		assertTrue(rendered.htmlBody().contains("You're invited to join SAGA"));
		assertTrue(rendered.htmlBody().contains("Sign in with institutional Google"));
		assertTrue(rendered.htmlBody().contains("anvse170102@fpt.edu.vn"));
		assertTrue(rendered.htmlBody().contains("http://localhost:3000/login"));
		assertTrue(rendered.textBody().contains("institutional email"));
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
		assertTrue(rendered.htmlBody().contains("Create your SAGA student account"));
		assertTrue(rendered.textBody().contains("public Student onboarding"));
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
		assertEquals("SAGA — You were added to SWP391", payload.get("subject"));
		assertTrue(String.valueOf(payload.get("htmlBody")).contains("You've been added to a course"));
		assertTrue(String.valueOf(payload.get("textBody")).contains("SE1705"));
		assertEquals("http://localhost:3000/dashboard", payload.get("ctaUrl"));
		assertFalse(payload.containsKey("courseId"));
		assertFalse(payload.containsKey("userId"));
		assertFalse(payload.containsKey("invitationId"));
		assertFalse(String.valueOf(payload.get("htmlBody")).contains("id="));
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
