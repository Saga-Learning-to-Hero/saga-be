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
	public static final String SPRINT_PERIOD_OVERLAP = "sprint-period-overlap";
	public static final String COURSE_WITHDRAWN = "course-withdrawn";
	public static final String TEAM_REMOVED = "team-removed";

	/** Label of the call-to-action button and of the plain-text link line. */
	static final String OPEN_SAGA = "Mở SAGA";

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
		String subject = "SAGA — Bạn đã được thêm vào lớp học phần " + code;
		String greeting = greeting(model.fullName());
		String courseLine = courseLine(model);
		String semester = semesterLine(model);
		String cta = links.dashboardUrl();
		String text = greeting
				+ "\n\nBạn đã được thêm vào một lớp học phần.\n\n"
				+ "Học phần: "
				+ courseLine
				+ "\nLớp: "
				+ displayClass(model)
				+ "\nHọc kỳ: "
				+ semester
				+ "\n\n" + OPEN_SAGA + ": "
				+ cta
				+ footerText();
		String inner = """
			<p style="margin:0 0 16px 0;">%s</p>
			<h1 style="margin:0 0 12px 0;font-size:22px;line-height:28px;color:#0f172a;">Bạn đã được thêm vào lớp học phần</h1>
			<p style="margin:0 0 20px 0;">Bạn đã có quyền truy cập lớp học phần này trên SAGA. Bấm nút bên dưới để mở SAGA.</p>
			"""
				.formatted(EmailHtml.escape(greeting));
		String html = SagaEmailLayout.document(
				subject,
				inner,
				OPEN_SAGA,
				cta,
				SagaEmailLayout.infoPanel(
						"Học phần", courseLine, "Lớp", displayClass(model), "Học kỳ", semester));
		return new EmailTemplate(subject, text, html);
	}

	private EmailTemplate invitation(EmailTemplateModel model) {
		String code = displayCode(model);
		String subject = "SAGA — Lời mời tham gia lớp học phần " + code;
		String greeting = greeting(model.fullName());
		String courseLine = courseLine(model);
		String semester = semesterLine(model);
		boolean institutional = model.institutional();
		String cta = institutional ? links.loginUrl() : links.registerUrl();
		String ctaLabel = institutional ? "Đăng nhập bằng Google của trường" : "Tạo tài khoản sinh viên SAGA";
		String invitedEmail = text(model.recipientEmail());
		String textExplain;
		String htmlExplain;
		if (institutional) {
			textExplain = "Hãy đăng nhập bằng email trường này"
					+ (invitedEmail.isEmpty() ? "" : " (" + invitedEmail + ")")
					+ ". Lời mời vào lớp sẽ được tự động liên kết sau khi bạn hoàn tất đăng nhập lần đầu.";
			htmlExplain = "Hãy đăng nhập bằng email trường này"
					+ (invitedEmail.isEmpty() ? "" : " (<strong>" + EmailHtml.escape(invitedEmail) + "</strong>)")
					+ ". Lời mời vào lớp sẽ được tự động liên kết sau khi bạn hoàn tất đăng nhập lần đầu.";
		} else {
			textExplain = "Hãy đăng ký tài khoản sinh viên SAGA bằng email này"
					+ (invitedEmail.isEmpty() ? "" : " (" + invitedEmail + ")")
					+ ". Sau khi bạn tạo tài khoản, lời mời vào lớp sẽ được tự động liên kết.";
			htmlExplain = "Hãy đăng ký tài khoản sinh viên SAGA bằng email này"
					+ (invitedEmail.isEmpty() ? "" : " (<strong>" + EmailHtml.escape(invitedEmail) + "</strong>)")
					+ ". Sau khi bạn tạo tài khoản, lời mời vào lớp sẽ được tự động liên kết.";
		}
		String text = greeting
				+ "\n\nBạn được mời tham gia SAGA.\n\n"
				+ "Học phần: "
				+ courseLine
				+ "\nLớp: "
				+ displayClass(model)
				+ "\nHọc kỳ: "
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
			<h1 style="margin:0 0 12px 0;font-size:22px;line-height:28px;color:#0f172a;">Bạn được mời tham gia SAGA</h1>
			<p style="margin:0 0 20px 0;">%s</p>
			"""
				.formatted(EmailHtml.escape(greeting), htmlExplain);
		String html = SagaEmailLayout.document(
				subject,
				inner,
				ctaLabel,
				cta,
				SagaEmailLayout.infoPanel(
						"Sinh viên được mời",
						displayName(model),
						"Học phần",
						courseLine,
						"Lớp",
						displayClass(model),
						"Học kỳ",
						semester));
		return new EmailTemplate(subject, text, html);
	}

	private EmailTemplate teamAssigned(EmailTemplateModel model) {
		String code = displayCode(model);
		String subject = "SAGA — Bạn đã được xếp nhóm trong lớp học phần " + code;
		String greeting = greeting(model.fullName());
		String courseLine = courseLine(model);
		String semester = semesterLine(model);
		String teamLine = teamLine(model);
		String role = roleVi(model.teamRole());
		String cta = links.dashboardUrl();
		String text = greeting
				+ "\n\nBạn đã được xếp vào một nhóm của lớp học phần.\n\n"
				+ "Học phần: "
				+ courseLine
				+ "\nLớp: "
				+ displayClass(model)
				+ "\nHọc kỳ: "
				+ semester
				+ "\nNhóm: "
				+ teamLine
				+ "\nVai trò: "
				+ role
				+ "\n\n" + OPEN_SAGA + ": "
				+ cta
				+ footerText();
		String inner = """
			<p style="margin:0 0 16px 0;">%s</p>
			<h1 style="margin:0 0 12px 0;font-size:22px;line-height:28px;color:#0f172a;">Bạn đã được xếp vào nhóm</h1>
			<p style="margin:0 0 20px 0;">Thông tin nhóm của bạn trong lớp học phần đã sẵn sàng. Bấm nút bên dưới để mở SAGA.</p>
			"""
				.formatted(EmailHtml.escape(greeting));
		String html = SagaEmailLayout.document(
				subject,
				inner,
				OPEN_SAGA,
				cta,
				SagaEmailLayout.infoPanel(
						"Học phần",
						courseLine,
						"Lớp",
						displayClass(model),
						"Học kỳ",
						semester,
						"Nhóm",
						teamLine,
						"Vai trò",
						role));
		return new EmailTemplate(subject, text, html);
	}

	/**
	 * Warning to a team Leader / course lecturer that two sprints of one project (possibly on
	 * different Jira sites) run at the same time. Each sprint line is pre-formatted by the caller,
	 * e.g. {@code "SAGA Sprint 5 — site-a (20/09/2026 → 03/10/2026, đang chạy)"}.
	 */
	public Map<String, Object> sprintPeriodOverlapPayload(
			String fullName, String recipientEmail, String projectName, String classCode, String firstSprint, String secondSprint) {
		String project = EmailHtml.blankTo(text(projectName), "của bạn");
		String subject = "SAGA — Sprint bị chồng thời gian trong dự án " + project;
		String greeting = greeting(fullName);
		String cta = links.dashboardUrl();
		String explain = "Hai sprint của dự án này đang chạy cùng thời gian. SAGA tính điểm đóng góp và đánh giá chéo "
				+ "theo từng sprint, nên các sprint phải chạy nối tiếp nhau, kể cả khi nhóm dùng nhiều Jira site. "
				+ "Hãy chỉnh lại ngày hoặc đóng một sprint trên Jira.";
		String text = greeting
				+ "\n\n"
				+ explain
				+ "\n\nDự án: "
				+ project
				+ (text(classCode).isEmpty() ? "" : "\nLớp: " + text(classCode))
				+ "\nSprint 1: "
				+ text(firstSprint)
				+ "\nSprint 2: "
				+ text(secondSprint)
				+ "\n\n" + OPEN_SAGA + ": "
				+ cta
				+ footerText();
		String inner = """
			<p style="margin:0 0 16px 0;">%s</p>
			<h1 style="margin:0 0 12px 0;font-size:22px;line-height:28px;color:#0f172a;">Sprint bị chồng thời gian</h1>
			<p style="margin:0 0 20px 0;">%s</p>
			"""
				.formatted(EmailHtml.escape(greeting), EmailHtml.escape(explain));
		String html = SagaEmailLayout.document(
				subject,
				inner,
				OPEN_SAGA,
				cta,
				SagaEmailLayout.infoPanel(
						"Dự án",
						project,
						"Lớp",
						EmailHtml.blankTo(text(classCode), "—"),
						"Sprint 1",
						text(firstSprint),
						"Sprint 2",
						text(secondSprint)));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("subject", subject);
		payload.put("textBody", text);
		payload.put("htmlBody", html);
		payload.put("fullName", text(fullName));
		payload.put("recipientEmail", text(recipientEmail));
		payload.put("projectName", project);
		payload.put("ctaUrl", cta);
		return payload;
	}

	/**
	 * Tells a student they were removed from a course (withdrawn from its roster) or only from their
	 * team (still enrolled), with the staff-entered reason. The reason is user text: it is only ever
	 * placed in the escaped info panel, never in raw HTML.
	 *
	 * @param model course/class/semester and, for a team removal, the team they left
	 * @param teamOnly true = removed from the team but still in the course
	 * @param removedBy "Lecturer" or "Admin" (shown as "Giảng viên" / "Quản trị viên")
	 */
	public Map<String, Object> studentRemovalPayload(
			EmailTemplateModel model, boolean teamOnly, String reason, String removedBy) {
		EmailTemplateModel safe = model == null ? EmailTemplateModel.course("", "", "", "", "", "", "", false) : model;
		String code = displayCode(safe);
		String subject = teamOnly
				? "SAGA — Bạn đã bị rút khỏi nhóm trong lớp học phần " + code
				: "SAGA — Bạn đã bị rút khỏi lớp học phần " + code;
		String greeting = greeting(safe.fullName());
		String courseLine = courseLine(safe);
		String semester = semesterLine(safe);
		String by = removedByVi(removedBy);
		String why = EmailHtml.blankTo(text(reason), "Không có");
		String explain = teamOnly
				? "Bạn đã bị rút khỏi nhóm. Bạn vẫn còn trong lớp học phần; giảng viên có thể xếp bạn vào nhóm khác."
				: "Bạn đã bị rút khỏi danh sách lớp học phần và không còn truy cập được lớp này trên SAGA.";
		String cta = links.dashboardUrl();
		String text = greeting
				+ "\n\n"
				+ explain
				+ "\n\nHọc phần: "
				+ courseLine
				+ "\nLớp: "
				+ displayClass(safe)
				+ "\nHọc kỳ: "
				+ semester
				+ (teamOnly ? "\nNhóm: " + teamLine(safe) : "")
				+ "\nNgười thực hiện: "
				+ by
				+ "\nLý do: "
				+ why
				+ "\n\n" + OPEN_SAGA + ": "
				+ cta
				+ footerText();
		String inner = """
			<p style="margin:0 0 16px 0;">%s</p>
			<h1 style="margin:0 0 12px 0;font-size:22px;line-height:28px;color:#0f172a;">%s</h1>
			<p style="margin:0 0 20px 0;">%s</p>
			"""
				.formatted(
						EmailHtml.escape(greeting),
						teamOnly ? "Bạn đã bị rút khỏi nhóm" : "Bạn đã bị rút khỏi lớp học phần",
						EmailHtml.escape(explain));
		String panel = teamOnly
				? SagaEmailLayout.infoPanel(
						"Học phần", courseLine, "Lớp", displayClass(safe), "Học kỳ", semester, "Nhóm", teamLine(safe),
						"Người thực hiện", by, "Lý do", why)
				: SagaEmailLayout.infoPanel(
						"Học phần", courseLine, "Lớp", displayClass(safe), "Học kỳ", semester, "Người thực hiện", by, "Lý do", why);
		String html = SagaEmailLayout.document(subject, inner, OPEN_SAGA, cta, panel);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("subject", subject);
		payload.put("textBody", text);
		payload.put("htmlBody", html);
		payload.put("fullName", text(safe.fullName()));
		payload.put("recipientEmail", text(safe.recipientEmail()));
		payload.put("courseCode", text(safe.courseCode()));
		payload.put("classCode", text(safe.classCode()));
		payload.put("reason", why);
		payload.put("removedBy", by);
		payload.put("ctaUrl", cta);
		return payload;
	}

	private EmailTemplate smoke(EmailTemplateModel model) {
		String subject = "SAGA — Kiểm tra gửi email";
		String cta = links.dashboardUrl();
		String text = "Đây là email kiểm tra đường gửi mail của SAGA (local/dev).\n\n" + OPEN_SAGA + ": " + cta + footerText();
		String inner = """
			<p style="margin:0 0 16px 0;">Xin chào,</p>
			<h1 style="margin:0 0 12px 0;font-size:22px;line-height:28px;color:#0f172a;">Kiểm tra gửi email</h1>
			<p style="margin:0 0 20px 0;">Đây là email kiểm tra đường gửi mail của SAGA (local/dev).</p>
			""";
		String html = SagaEmailLayout.document(
				subject,
				inner,
				OPEN_SAGA,
				cta,
				SagaEmailLayout.infoPanel("Người nhận", text(model.recipientEmail())));
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
		return name.isEmpty() ? "Xin chào," : "Xin chào " + name + ",";
	}

	private static String displayName(EmailTemplateModel model) {
		String name = text(model.fullName());
		return name.isEmpty() ? text(model.recipientEmail()) : name;
	}

	private static String displayCode(EmailTemplateModel model) {
		return EmailHtml.blankTo(model.courseCode(), "của bạn");
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
		return "Lớp học phần SAGA";
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
			return "Nhóm " + number + " — " + name;
		}
		if (!name.isEmpty()) {
			return name;
		}
		return number.isEmpty() ? "—" : "Nhóm " + number;
	}

	/** Team role as stored ("Leader"/"Member", or the enum name), shown in Vietnamese. */
	static String roleVi(String role) {
		String value = text(role);
		if (value.equalsIgnoreCase("leader")) {
			return "Trưởng nhóm";
		}
		if (value.isEmpty() || value.equalsIgnoreCase("member")) {
			return "Thành viên";
		}
		return value;
	}

	/** "Admin"/"Lecturer" from StudentRemovalNotifier#removedBy, shown in Vietnamese. */
	static String removedByVi(String removedBy) {
		String value = text(removedBy);
		if (value.equalsIgnoreCase("admin")) {
			return "Quản trị viên";
		}
		if (value.equalsIgnoreCase("lecturer")) {
			return "Giảng viên";
		}
		return value.isEmpty() ? "Giảng viên phụ trách lớp" : value;
	}

	private static String footerText() {
		return "\n\nSAGA — Student Activity Graph Based Continuous Assessment\n" + SagaEmailLayout.AUTOMATED_NOTICE;
	}

	private static String text(String value) {
		return value == null ? "" : value.trim();
	}
}
