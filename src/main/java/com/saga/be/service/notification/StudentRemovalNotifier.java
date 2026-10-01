package com.saga.be.service.notification;

import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.mail.template.EmailTemplateModel;
import com.saga.be.mail.template.EmailTemplateService;
import com.saga.be.service.mail.EmailOutboxService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Tells a student -- by in-app notification AND email -- that staff removed them from a course or
 * from their team, with the reason staff entered. Call it inside the removal's own transaction: the
 * notification row and the outbox email then commit (or roll back) together with the removal.
 */
@Component
@Profile("!test")
public class StudentRemovalNotifier {

	public static final int REASON_MAX_LENGTH = 500;
	static final String COURSE_EMAIL_TYPE = "COURSE_WITHDRAWN";
	static final String TEAM_EMAIL_TYPE = "TEAM_REMOVED";

	private final NotificationService notifications;
	private final EmailOutboxService emails;
	private final EmailTemplateService templates;

	public StudentRemovalNotifier(
			NotificationService notifications, EmailOutboxService emails, EmailTemplateService templates) {
		this.notifications = notifications;
		this.emails = emails;
		this.templates = templates;
	}

	/** Trimmed reason, or 400 REQUEST_INVALID when blank or longer than {@value #REASON_MAX_LENGTH}. */
	public static String requireReason(String reason) {
		String trimmed = reason == null ? "" : reason.trim();
		if (trimmed.isEmpty()) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST, "A reason is required to remove a student.");
		}
		if (trimmed.length() > REASON_MAX_LENGTH) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"The reason must be at most " + REASON_MAX_LENGTH + " characters.");
		}
		return trimmed;
	}

	/** "Admin" or "Lecturer", as shown to the student. */
	public static String removedBy(UserAccount actor) {
		return actor != null && actor.getAccountRole() == AccountRole.ADMIN ? "Admin" : "Lecturer";
	}

	public void courseWithdrawn(CourseEnrollment enrollment, Course course, String reason, UserAccount actor) {
		UserAccount student = studentOf(enrollment);
		if (student == null) {
			return;
		}
		String code = courseLabel(course);
		String by = removedBy(actor);
		notifications.createNotification(
				student.getId(),
				NotificationType.COURSE,
				truncate("Removed from " + code, 160),
				truncate("You were removed from " + code + " by the " + by.toLowerCase() + "." + reasonSuffix(reason), 1000),
				null,
				"course-withdrawn:" + enrollment.getId() + ":" + UUID.randomUUID());
		enqueue(student, COURSE_EMAIL_TYPE, EmailTemplateService.COURSE_WITHDRAWN, model(student, course, null), false, reason, by);
	}

	public void teamRemoved(CourseEnrollment enrollment, Course course, Team team, String reason, UserAccount actor) {
		UserAccount student = studentOf(enrollment);
		if (student == null) {
			return;
		}
		String code = courseLabel(course);
		String teamName = team == null ? "your team" : teamLabel(team);
		String by = removedBy(actor);
		notifications.createNotification(
				student.getId(),
				NotificationType.TEAM,
				truncate("Removed from " + teamName, 160),
				truncate(
						"You were removed from " + teamName + " in " + code + " by the " + by.toLowerCase()
								+ ". You are still enrolled in the course." + reasonSuffix(reason),
						1000),
				null,
				"team-removed:" + enrollment.getId() + ":" + UUID.randomUUID());
		enqueue(student, TEAM_EMAIL_TYPE, EmailTemplateService.TEAM_REMOVED, model(student, course, team), true, reason, by);
	}

	private void enqueue(
			UserAccount student,
			String emailType,
			String templateKey,
			EmailTemplateModel model,
			boolean teamOnly,
			String reason,
			String by) {
		if (!StringUtils.hasText(student.getEmail())) {
			return;
		}
		emails.enqueue(new EmailEnqueueRequest(
				student.getEmail(),
				student.getId(),
				emailType,
				templateKey,
				templates.studentRemovalPayload(model, teamOnly, reason, by),
				null));
	}

	private static UserAccount studentOf(CourseEnrollment enrollment) {
		StudentProfile profile = enrollment == null ? null : enrollment.getStudentProfile();
		UserAccount user = profile == null ? null : profile.getUserAccount();
		return user == null || user.getId() == null ? null : user;
	}

	private static EmailTemplateModel model(UserAccount student, Course course, Team team) {
		return EmailTemplateModel.teamAssigned(
				student.getFullName(),
				student.getEmail(),
				course == null ? null : course.getName(),
				course == null ? null : courseCode(course),
				course == null || course.getAcademicClass() == null ? null : course.getAcademicClass().getClassCode(),
				course == null || course.getSemester() == null ? null : course.getSemester().getCode(),
				course == null || course.getSemester() == null ? null : course.getSemester().getName(),
				team == null ? null : team.getTeamNo(),
				team == null ? null : team.getName(),
				null);
	}

	private static String courseCode(Course course) {
		return course.getSubject() != null && StringUtils.hasText(course.getSubject().getSubjectCode())
				? course.getSubject().getSubjectCode()
				: course.getCourseCode();
	}

	private static String courseLabel(Course course) {
		if (course == null) {
			return "your course";
		}
		String code = courseCode(course);
		String classCode = course.getAcademicClass() == null ? null : course.getAcademicClass().getClassCode();
		String base = StringUtils.hasText(code) ? code : StringUtils.hasText(course.getName()) ? course.getName() : "your course";
		return StringUtils.hasText(classCode) ? base + " · " + classCode : base;
	}

	private static String teamLabel(Team team) {
		String name = StringUtils.hasText(team.getName()) ? team.getName() : null;
		if (team.getTeamNo() != null) {
			return "team " + team.getTeamNo() + (name == null ? "" : " — " + name);
		}
		return name == null ? "your team" : name;
	}

	/** " Reason: ..." -- empty when no reason was given (legacy no-body ADMIN removal). */
	private static String reasonSuffix(String reason) {
		return StringUtils.hasText(reason) ? " Reason: " + reason.trim() : "";
	}

	private static String truncate(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max - 1) + "…";
	}
}
