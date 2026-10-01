package com.saga.be.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.saga.be.config.AuthProperties;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.mail.template.EmailTemplateService;
import com.saga.be.service.mail.EmailOutboxService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StudentRemovalNotifierTest {

	private NotificationService notifications;
	private EmailOutboxService emails;
	private StudentRemovalNotifier notifier;
	private UserAccount student;
	private CourseEnrollment enrollment;
	private Course course;

	@BeforeEach
	void setUp() {
		notifications = mock(NotificationService.class);
		emails = mock(EmailOutboxService.class);
		notifier = new StudentRemovalNotifier(notifications, emails, new EmailTemplateService(new AuthProperties()));
		student = new UserAccount();
		student.setId(UUID.randomUUID());
		student.setEmail("student@fpt.edu.vn");
		student.setFullName("Huynh Phuoc Thien");
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(student);
		Subject subject = new Subject();
		subject.setSubjectCode("SWR302");
		subject.setName("Software Requirement");
		AcademicClass academicClass = new AcademicClass();
		academicClass.setClassCode("SE1802");
		Semester semester = new Semester();
		semester.setCode("FA26");
		semester.setName("Fall 2026");
		course = new Course();
		course.setName("SWR302 · SE1802");
		course.setSubject(subject);
		course.setAcademicClass(academicClass);
		course.setSemester(semester);
		enrollment = new CourseEnrollment();
		enrollment.setId(UUID.randomUUID());
		enrollment.setStudentProfile(profile);
		enrollment.setCourse(course);
	}

	@Test
	void courseWithdrawalNotifiesInAppAndByEmailWithTheReason() {
		notifier.courseWithdrawn(enrollment, course, "Transferred to SE1803", lecturer());

		ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> eventKey = ArgumentCaptor.forClass(String.class);
		verify(notifications).createNotification(
				eq(student.getId()), eq(NotificationType.COURSE), title.capture(), message.capture(), isNull(), eventKey.capture());
		assertThat(title.getValue()).isEqualTo("Removed from SWR302 · SE1802");
		assertThat(message.getValue()).contains("by the lecturer").endsWith("Reason: Transferred to SE1803");
		assertThat(eventKey.getValue()).startsWith("course-withdrawn:" + enrollment.getId() + ":");

		ArgumentCaptor<EmailEnqueueRequest> mail = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(mail.capture());
		assertThat(mail.getValue().recipientEmail()).isEqualTo("student@fpt.edu.vn");
		assertThat(mail.getValue().emailType()).isEqualTo("COURSE_WITHDRAWN");
		assertThat(mail.getValue().templateKey()).isEqualTo(EmailTemplateService.COURSE_WITHDRAWN);
		assertThat(String.valueOf(mail.getValue().payload().get("subject"))).isEqualTo("SAGA — You were removed from SWR302");
		assertThat(String.valueOf(mail.getValue().payload().get("textBody")))
				.contains("Reason: Transferred to SE1803")
				.contains("Removed by: Lecturer");
	}

	@Test
	void teamRemovalSaysTheStudentIsStillEnrolled() {
		Team team = new Team();
		team.setTeamNo(1);
		team.setName("SAGA Team");

		notifier.teamRemoved(enrollment, course, team, "Moved to team 2", admin());

		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		verify(notifications).createNotification(
				eq(student.getId()), eq(NotificationType.TEAM), eq("Removed from team 1 — SAGA Team"), message.capture(),
				isNull(), any());
		assertThat(message.getValue()).contains("still enrolled").contains("by the admin").contains("Moved to team 2");
		ArgumentCaptor<EmailEnqueueRequest> mail = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(mail.capture());
		assertThat(mail.getValue().emailType()).isEqualTo("TEAM_REMOVED");
		assertThat(String.valueOf(mail.getValue().payload().get("htmlBody"))).contains("1 — SAGA Team").contains("Moved to team 2");
	}

	@Test
	void everyRemovalGetsItsOwnNotificationEvenForTheSameEnrollment() {
		notifier.courseWithdrawn(enrollment, course, "First", lecturer());
		notifier.courseWithdrawn(enrollment, course, "Second", lecturer());

		ArgumentCaptor<String> eventKey = ArgumentCaptor.forClass(String.class);
		verify(notifications, times(2)).createNotification(any(), any(), any(), any(), any(), eventKey.capture());
		assertThat(eventKey.getAllValues().get(0)).isNotEqualTo(eventKey.getAllValues().get(1));
	}

	@Test
	void reasonIsHtmlEscapedInTheEmail() {
		notifier.courseWithdrawn(enrollment, course, "<script>alert(1)</script> & more", lecturer());

		ArgumentCaptor<EmailEnqueueRequest> mail = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(mail.capture());
		String html = String.valueOf(mail.getValue().payload().get("htmlBody"));
		assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
	}

	@Test
	void legacyRemovalWithoutReasonOmitsTheReasonLine() {
		notifier.courseWithdrawn(enrollment, course, null, admin());

		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		verify(notifications).createNotification(any(), any(), any(), message.capture(), any(), any());
		assertThat(message.getValue()).isEqualTo("You were removed from SWR302 · SE1802 by the admin.");
		ArgumentCaptor<EmailEnqueueRequest> mail = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(mail.capture());
		assertThat(String.valueOf(mail.getValue().payload().get("textBody")))
				.contains("Reason: Not provided")
				.doesNotContain("null");
	}

	@Test
	void studentWithoutEmailStillGetsTheInAppNotification() {
		student.setEmail(" ");

		notifier.courseWithdrawn(enrollment, course, "Reason", lecturer());

		verify(notifications).createNotification(any(), any(), any(), any(), any(), any());
		verify(emails, never()).enqueue(any());
	}

	@Test
	void reasonIsTrimmedAndMustBeOneTo500Characters() {
		assertThat(StudentRemovalNotifier.requireReason("  Moved  ")).isEqualTo("Moved");
		assertThat(StudentRemovalNotifier.requireReason("x".repeat(500))).hasSize(500);
		for (String bad : new String[] {null, "", "   ", "x".repeat(501)}) {
			assertThatThrownBy(() -> StudentRemovalNotifier.requireReason(bad))
					.isInstanceOf(AcademicException.class)
					.extracting(ex -> ((AcademicException) ex).getCode())
					.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
		}
	}

	private static UserAccount lecturer() {
		UserAccount account = new UserAccount();
		account.setAccountRole(AccountRole.LECTURER);
		return account;
	}

	private static UserAccount admin() {
		UserAccount account = new UserAccount();
		account.setAccountRole(AccountRole.ADMIN);
		return account;
	}
}
