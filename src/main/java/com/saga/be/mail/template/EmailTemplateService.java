package com.saga.be.mail.template;

import com.saga.be.config.AuthProperties;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EmailTemplateService {

	public static final String COURSE_ENROLLED = "course-enrolled";
	public static final String COURSE_INVITATION = "course-invitation";
	public static final String TEAM_ASSIGNED = "team-assigned";
	public static final String DEV_SMOKE = "dev-smoke";

	private final FrontendLinkResolver links;

	public EmailTemplateService(AuthProperties authProperties) {
		this.links = new FrontendLinkResolver(authProperties);
	}

	public EmailTemplate render(String templateKey, EmailTemplateModel model) {
		EmailTemplateModel safe = model == null
				? EmailTemplateModel.course("", "", "", "", "", "", "", false)
				: model;
		return switch (normalize(templateKey)) {
			case COURSE_ENROLLED -> enrolled(safe);
			case COURSE_INVITATION -> invitation(safe);
			case TEAM_ASSIGNED -> teamAssigned(safe);
			case DEV_SMOKE -> smoke(safe);
			default -> throw new IllegalArgumentException("Unknown email template.");
		};
	}

	public Map<String, Object> payload(String templateKey, EmailTemplateModel model) {
		EmailTemplate rendered = render(templateKey, model);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("subject", rendered.subject());
		payload.put("textBody", rendered.textBody());
		payload.put("htmlBody", rendered.htmlBody());
		payload.put("fullName", text(model == null ? null : model.fullName()));
		payload.put("recipientEmail", text(model == null ? null : model.recipientEmail()));
		payload.put("courseName", text(model == null ? null : model.courseName()));
		payload.put("courseCode", text(model == null ? null : model.courseCode()));
		payload.put("subjectCode", text(model == null ? null : model.courseCode()));
		payload.put("classCode", text(model == null ? null : model.classCode()));
		payload.put("semesterCode", text(model == null ? null : model.semesterCode()));
		payload.put("semesterName", text(model == null ? null : model.semesterName()));
		payload.put("ctaUrl", ctaUrl(normalize(templateKey), model));
		payload.put("institutionalGoogle", model != null && model.institutional());
		payload.put("teamNo", model == null || model.teamNo() == null ? "" : String.valueOf(model.teamNo()));
		payload.put("teamName", text(model == null ? null : model.teamName()));
		payload.put("teamRole", text(model == null ? null : model.teamRole()));
		return payload;
	}

	public static String normalize(String templateKey) {
		if (!StringUtils.hasText(templateKey)) {
			return DEV_SMOKE;
		}
		String key = templateKey.trim().toLowerCase(Locale.ROOT).replace('_', '-');
		return switch (key) {
			case "course-enrolled", "courseenrolled" -> COURSE_ENROLLED;
			case "course-invitation", "courseinvitation" -> COURSE_INVITATION;
			case "team-assigned", "teamassigned" -> TEAM_ASSIGNED;
			case "dev-smoke", "devsmoke" -> DEV_SMOKE;
			default -> key;
		};
	}

	private EmailTemplate enrolled(EmailTemplateModel model) {
		String code = displayCode(model);
		String subject = "SAGA — You were added to " + code;
		String greeting = greeting(model.fullName());
		String courseLine = courseLine(model);
		String semester = semesterLine(model);
		String cta = links.dashboardUrl();
		String text = greeting
				+ "\n\nYou've been added to a course.\n\n"
				+ "Course: "
				+ courseLine
				+ "\nClass: "
				+ displayClass(model)
				+ "\nSemester: "
				+ semester
				+ "\n\nOpen SAGA: "
				+ cta
				+ footerText();
		String inner = """
			<p style="margin:0 0 16px 0;">%s</p>
			<h1 style="margin:0 0 12px 0;font-size:22px;line-height:28px;color:#0f172a;">You've been added to a course</h1>
			<p style="margin:0 0 20px 0;">You now have access to this SAGA course. Use the button below to open SAGA.</p>
			"""
				.formatted(EmailHtml.escape(greeting));
		String html = SagaEmailLayout.document(
				subject,
				inner,
				"Open SAGA",
				cta,
				SagaEmailLayout.infoPanel(
						"Course", courseLine, "Class", displayClass(model), "Semester", semester));
		return new EmailTemplate(subject, text, html);
	}

	private EmailTemplate invitation(EmailTemplateModel model) {
		String code = displayCode(model);
		String subject = "SAGA — Course invitation for " + code;
		String greeting = greeting(model.fullName());
		String courseLine = courseLine(model);
		String semester = semesterLine(model);
		boolean institutional = model.institutional();
		String cta = institutional ? links.loginUrl() : links.registerUrl();
		String ctaLabel = institutional ? "Sign in with institutional Google" : "Create your SAGA student account";
		String invitedEmail = text(model.recipientEmail());
		String textExplain;
		String htmlExplain;
		if (institutional) {
			textExplain = "Sign in using this institutional email"
					+ (invitedEmail.isEmpty() ? "" : " (" + invitedEmail + ")")
					+ ". Your pending course invitation will be linked automatically after onboarding.";
			htmlExplain = "Sign in using this institutional email"
					+ (invitedEmail.isEmpty() ? "" : " (<strong>" + EmailHtml.escape(invitedEmail) + "</strong>)")
					+ ". Your pending course invitation will be linked automatically after onboarding.";
		} else {
			textExplain = "Register with this email using the public Student onboarding flow"
					+ (invitedEmail.isEmpty() ? "" : " (" + invitedEmail + ")")
					+ ". After you create your student account, your pending course invitation will be linked automatically.";
			htmlExplain = "Register with this email using the public Student onboarding flow"
					+ (invitedEmail.isEmpty() ? "" : " (<strong>" + EmailHtml.escape(invitedEmail) + "</strong>)")
					+ ". After you create your student account, your pending course invitation will be linked automatically.";
		}
		String text = greeting
				+ "\n\nYou're invited to join SAGA.\n\n"
				+ "Course: "
				+ courseLine
				+ "\nClass: "
				+ displayClass(model)
				+ "\nSemester: "
				+ semester
				+ "\n\n"
				+ textExplain
				+ "\n\n"
				+ ctaLabel
				+ ": "
				+ cta
				+ footerText();
		String inner = """
			<p style="margin:0 0 16px 0;">%s</p>
			<h1 style="margin:0 0 12px 0;font-size:22px;line-height:28px;color:#0f172a;">You're invited to join SAGA</h1>
			<p style="margin:0 0 20px 0;">%s</p>
			"""
				.formatted(EmailHtml.escape(greeting), htmlExplain);
		String html = SagaEmailLayout.document(
				subject,
				inner,
				ctaLabel,
				cta,
				SagaEmailLayout.infoPanel(
						"Invited student",
						displayName(model),
						"Course",
						courseLine,
						"Class",
						displayClass(model),
						"Semester",
						semester));
		return new EmailTemplate(subject, text, html);
	}

	private EmailTemplate teamAssigned(EmailTemplateModel model) {
		String code = displayCode(model);
		String subject = "SAGA — Team assignment for " + code;
		String greeting = greeting(model.fullName());
		String courseLine = courseLine(model);
		String semester = semesterLine(model);
		String teamLine = teamLine(model);
		String role = EmailHtml.blankTo(model.teamRole(), "Member");
		String cta = links.dashboardUrl();
		String text = greeting
				+ "\n\nYou were assigned to a course team.\n\n"
				+ "Course: "
				+ courseLine
				+ "\nClass: "
				+ displayClass(model)
				+ "\nSemester: "
				+ semester
				+ "\nTeam: "
				+ teamLine
				+ "\nRole: "
				+ role
				+ "\n\nOpen SAGA: "
				+ cta
				+ footerText();
		String inner = """
			<p style="margin:0 0 16px 0;">%s</p>
			<h1 style="margin:0 0 12px 0;font-size:22px;line-height:28px;color:#0f172a;">You were assigned to a team</h1>
			<p style="margin:0 0 20px 0;">Your SAGA course team assignment is ready. Use the button below to open SAGA.</p>
			"""
				.formatted(EmailHtml.escape(greeting));
		String html = SagaEmailLayout.document(
				subject,
				inner,
				"Open SAGA",
				cta,
				SagaEmailLayout.infoPanel(
						"Course",
						courseLine,
						"Class",
						displayClass(model),
						"Semester",
						semester,
						"Team",
						teamLine,
						"Role",
						role));
		return new EmailTemplate(subject, text, html);
	}

	private EmailTemplate smoke(EmailTemplateModel model) {
		String subject = "SAGA — Mail delivery test";
		String cta = links.dashboardUrl();
		String text = "This is a SAGA local/dev mail pipeline smoke test.\n\nOpen SAGA: " + cta + footerText();
		String inner = """
			<p style="margin:0 0 16px 0;">Hello,</p>
			<h1 style="margin:0 0 12px 0;font-size:22px;line-height:28px;color:#0f172a;">Mail delivery test</h1>
			<p style="margin:0 0 20px 0;">This is a SAGA local/dev mail pipeline smoke test.</p>
			""";
		String html = SagaEmailLayout.document(
				subject,
				inner,
				"Open SAGA",
				cta,
				SagaEmailLayout.infoPanel("Recipient", text(model.recipientEmail())));
		return new EmailTemplate(subject, text, html);
	}

	private String ctaUrl(String key, EmailTemplateModel model) {
		return switch (key) {
			case COURSE_ENROLLED, TEAM_ASSIGNED, DEV_SMOKE -> links.dashboardUrl();
			case COURSE_INVITATION -> model != null && model.institutional() ? links.loginUrl() : links.registerUrl();
			default -> links.dashboardUrl();
		};
	}

	private static String greeting(String fullName) {
		String name = text(fullName);
		return name.isEmpty() ? "Hello," : "Hello " + name + ",";
	}

	private static String displayName(EmailTemplateModel model) {
		String name = text(model.fullName());
		return name.isEmpty() ? text(model.recipientEmail()) : name;
	}

	private static String displayCode(EmailTemplateModel model) {
		return EmailHtml.blankTo(model.courseCode(), "your course");
	}

	private static String displayClass(EmailTemplateModel model) {
		return EmailHtml.blankTo(model.classCode(), "—");
	}

	private static String courseLine(EmailTemplateModel model) {
		String code = text(model.courseCode());
		String name = text(model.courseName());
		if (!code.isEmpty() && !name.isEmpty()) {
			return code + " - " + name;
		}
		if (!name.isEmpty()) {
			return name;
		}
		if (!code.isEmpty()) {
			return code;
		}
		return "SAGA course";
	}

	private static String semesterLine(EmailTemplateModel model) {
		String code = text(model.semesterCode());
		String name = text(model.semesterName());
		if (!code.isEmpty() && !name.isEmpty() && !code.equalsIgnoreCase(name)) {
			return code + " — " + name;
		}
		return EmailHtml.blankTo(code.isEmpty() ? name : code, "—");
	}

	private static String teamLine(EmailTemplateModel model) {
		String number = model.teamNo() == null ? "" : String.valueOf(model.teamNo());
		String name = text(model.teamName());
		if (!number.isEmpty() && !name.isEmpty()) {
			return number + " — " + name;
		}
		if (!name.isEmpty()) {
			return name;
		}
		return EmailHtml.blankTo(number, "—");
	}

	private static String footerText() {
		return "\n\nSAGA — Student Activity Graph Based Continuous Assessment\nThis is an automated email.";
	}

	private static String text(String value) {
		return value == null ? "" : value.trim();
	}
}
