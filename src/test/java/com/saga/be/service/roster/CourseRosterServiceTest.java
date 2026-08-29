package com.saga.be.service.roster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.auth.InstitutionalEmailPolicy;
import com.saga.be.config.AuthProperties;
import com.saga.be.config.RosterProperties;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.dto.roster.CourseRosterResponse;
import com.saga.be.dto.roster.RosterConfirmResponse;
import com.saga.be.dto.roster.RosterPreviewResponse;
import com.saga.be.dto.roster.RosterPreviewRow;
import com.saga.be.entity.account.StudentCourseInvitation;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.RosterRowAction;
import com.saga.be.entity.enums.StudentInvitationStatus;
import com.saga.be.entity.enums.StudentInvitationType;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.mail.EmailOutboxService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class CourseRosterServiceTest {

	@Mock
	private EmailOutboxService emails;
	@Mock
	private AuditService audit;

	private InMemoryCourseRosterStore store;
	private InMemoryRosterPreviewStore previews;
	private CourseRosterService service;
	private UserAccount admin;
	private Course course;

	@BeforeEach
	void setUp() {
		store = new InMemoryCourseRosterStore();
		previews = new InMemoryRosterPreviewStore();
		RosterProperties rosterProperties = new RosterProperties();
		AuthProperties authProperties = new AuthProperties();
		authProperties.setFrontendOrigins(List.of("http://localhost:3000"));
		service = new CourseRosterService(
				store,
				previews,
				rosterProperties,
				authProperties,
				new InstitutionalEmailPolicy(authProperties),
				emails,
				audit);
		admin = account(AccountRole.ADMIN, "admin@saga.local");
		store.putUser(admin);
		course = course("SE1705");
	}

	@Test
	void templateDownloadUsesCourseClassWithoutInternalIds() throws Exception {
		byte[] bytes = service.template(course.getId());
		assertTrue(new String(bytes).contains("Danh_Sach_SV") || bytes[0] == 'P');
		assertFalse(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains(course.getId().toString()));
		List<CourseRosterWorkbook.RawRow> parsed = CourseRosterWorkbook.parse(bytes);
		assertTrue(parsed.isEmpty());
	}

	@Test
	void existingStudentIsReadyToEnroll() throws Exception {
		student("student@gmail.com", "SE123456", "Nguyễn Văn Ánh");
		RosterPreviewResponse preview = previewRow("SE1705", "Nguyễn Văn Ánh", "SE123456", "student@gmail.com", "M1");
		RosterPreviewRow row = preview.rows().getFirst();
		assertEquals(RosterRowAction.READY_ENROLL, row.action());
		assertEquals("M1", row.memberCode());
		assertEquals(1, preview.summary().existingAccounts());
		assertEquals(0, preview.summary().newInvitations());
	}

	@Test
	void unknownAccountIsReadyToInvite() throws Exception {
		RosterPreviewResponse preview = previewRow("SE1705", "New Student", "SE000001", "new@gmail.com", "");
		assertEquals(RosterRowAction.READY_INVITE, preview.rows().getFirst().action());
		assertEquals(1, preview.summary().newInvitations());
	}

	@Test
	void wrongClassIsInvalid() throws Exception {
		RosterPreviewResponse preview = previewRow("SE1706", "A", "SE123456", "a@gmail.com", "");
		assertEquals(RosterRowAction.INVALID, preview.rows().getFirst().action());
		assertTrue(preview.rows().getFirst().errors().stream().anyMatch(error -> error.contains("Class")));
		assertEquals(1, preview.summary().invalidRows());
	}

	@Test
	void duplicateEmailInWorkbookIsRejected() throws Exception {
		RosterPreviewResponse preview = service.preview(
				course.getId(),
				CourseRosterWorkbookTest.filledWorkbook(
						"SE1705",
						List.of(
								new String[] {"1", "SE1705", "A", "SE111111", "dup@gmail.com", ""},
								new String[] {"2", "SE1705", "B", "SE222222", "DUP@gmail.com", ""})),
				admin);
		assertTrue(preview.rows().get(1).errors().stream().anyMatch(error -> error.contains("Duplicate email")));
		assertEquals(RosterRowAction.INVALID, preview.rows().get(1).action());
	}

	@Test
	void duplicateStudentCodeInWorkbookIsRejected() throws Exception {
		RosterPreviewResponse preview = service.preview(
				course.getId(),
				CourseRosterWorkbookTest.filledWorkbook(
						"SE1705",
						List.of(
								new String[] {"1", "SE1705", "A", "se111111", "a@gmail.com", ""},
								new String[] {"2", "SE1705", "B", "SE111111", "b@gmail.com", ""})),
				admin);
		assertTrue(preview.rows().get(1).errors().stream().anyMatch(error -> error.contains("Duplicate StudentCode")));
	}

	@Test
	void lecturerCannotBeRosterStudent() throws Exception {
		account(AccountRole.LECTURER, "lecturer@fe.edu.vn");
		RosterPreviewResponse preview = previewRow("SE1705", "Lecturer", "SE999999", "lecturer@fe.edu.vn", "");
		assertEquals(RosterRowAction.CONFLICT, preview.rows().getFirst().action());
	}

	@Test
	void studentCodeIdentityMismatchIsRejected() throws Exception {
		student("student@gmail.com", "SE111111", "A");
		RosterPreviewResponse preview = previewRow("SE1705", "A", "SE222222", "student@gmail.com", "");
		assertEquals(RosterRowAction.CONFLICT, preview.rows().getFirst().action());
		assertTrue(preview.rows().getFirst().errors().getFirst().contains("StudentCode"));
	}

	@Test
	void confirmEnrollsExistingStudentAndEnqueuesCourseEnrolled() throws Exception {
		student("student@gmail.com", "SE123456", "A");
		RosterPreviewResponse preview = previewRow("SE1705", "A", "SE123456", "student@gmail.com", "MC");
		RosterConfirmResponse confirmed = service.confirm(course.getId(), preview.previewToken(), admin, auditReq());
		assertEquals(1, confirmed.enrolled());
		assertEquals(0, confirmed.invited());
		assertEquals(1, store.enrollments.size());
		assertEquals(EnrollmentStatus.ACTIVE, store.enrollments.values().iterator().next().getEnrollmentStatus());
		ArgumentCaptor<EmailEnqueueRequest> captor = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(captor.capture());
		assertEquals("COURSE_ENROLLED", captor.getValue().emailType());
		assertTrue(String.valueOf(captor.getValue().payload().get("textBody")).contains("SE1705"));
		assertTrue(String.valueOf(captor.getValue().payload().get("htmlBody")).contains("You've been added to a course"));
		assertTrue(String.valueOf(captor.getValue().payload().get("htmlBody")).contains("http://localhost:3000/dashboard"));
		verify(audit)
				.record(
						eq(admin),
						isNull(),
						isNull(),
						eq(CourseRosterService.COURSE_ROSTER_IMPORTED),
						eq("course"),
						eq(course.getId()),
						isNull(),
						any(),
						any(),
						eq(AuditSource.API),
						any(),
						any(),
						any());
		assertTrue(store.invitations.isEmpty());
		AcademicException reused = assertThrows(
				AcademicException.class,
				() -> service.confirm(course.getId(), preview.previewToken(), admin, auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_PREVIEW_EXPIRED, reused.getCode());
	}

	@Test
	void confirmInvitesUnknownStudentWithoutCreatingAnAccount() throws Exception {
		RosterPreviewResponse preview = previewRow("SE1705", "New", "SE000001", "new@gmail.com", "MC-9");
		RosterConfirmResponse confirmed = service.confirm(course.getId(), preview.previewToken(), admin, auditReq());
		assertEquals(1, confirmed.invited());
		assertTrue(store.users.values().stream().noneMatch(user -> "new@gmail.com".equals(user.getEmail())));
		assertTrue(store.students.isEmpty());
		StudentCourseInvitation invitation = store.invitations.values().iterator().next();
		assertEquals(StudentInvitationStatus.PENDING, invitation.getInvitationStatus());
		assertEquals("new@gmail.com", invitation.getEmail());
		assertEquals("SE000001", invitation.getStudentCode());
		assertEquals("New", invitation.getFullName());
		ArgumentCaptor<EmailEnqueueRequest> captor = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(captor.capture());
		assertEquals("COURSE_INVITATION", captor.getValue().emailType());
		assertFalse(Boolean.TRUE.equals(captor.getValue().payload().get("institutionalGoogle")));
		assertTrue(String.valueOf(captor.getValue().payload().get("textBody")).contains("Register"));
		assertTrue(String.valueOf(captor.getValue().payload().get("htmlBody")).contains("You're invited to join SAGA"));
		assertTrue(String.valueOf(captor.getValue().payload().get("htmlBody")).contains("http://localhost:3000/register"));
		CourseRosterResponse roster = service.getRoster(course.getId());
		assertEquals(1, roster.pendingInvitationCount());
		assertEquals("NOT_REGISTERED", roster.entries().getFirst().accountState());
		assertEquals("PENDING", roster.entries().getFirst().invitationStatus());
	}

	@Test
	void institutionalInvitationTellsStudentToUseGoogle() throws Exception {
		RosterPreviewResponse preview = previewRow("SE1705", "FPT Student", "SE170102", "anvse170102@fpt.edu.vn", "");
		service.confirm(course.getId(), preview.previewToken(), admin, auditReq());
		ArgumentCaptor<EmailEnqueueRequest> captor = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(captor.capture());
		assertEquals("COURSE_INVITATION", captor.getValue().emailType());
		assertEquals(Boolean.TRUE, captor.getValue().payload().get("institutionalGoogle"));
		String text = String.valueOf(captor.getValue().payload().get("textBody"));
		String html = String.valueOf(captor.getValue().payload().get("htmlBody"));
		assertTrue(text.contains("institutional email"));
		assertTrue(html.contains("Sign in with institutional Google"));
		assertTrue(html.contains("anvse170102@fpt.edu.vn"));
		assertFalse(text.toLowerCase(Locale.ROOT).contains("create a local password"));
		assertFalse(html.toLowerCase(Locale.ROOT).contains("create a local password"));
	}

	@Test
	void repeatedImportIsIdempotentAndDoesNotResendMail() throws Exception {
		student("student@gmail.com", "SE123456", "A");
		RosterPreviewResponse first = previewRow("SE1705", "A", "SE123456", "student@gmail.com", "");
		service.confirm(course.getId(), first.previewToken(), admin, auditReq());
		RosterPreviewResponse second = previewRow("SE1705", "A", "SE123456", "student@gmail.com", "");
		assertEquals(RosterRowAction.ALREADY_ENROLLED, second.rows().getFirst().action());
		RosterConfirmResponse confirmed = service.confirm(course.getId(), second.previewToken(), admin, auditReq());
		assertEquals(1, confirmed.unchanged());
		assertEquals(1, store.enrollments.size());
		verify(emails, times(1)).enqueue(any());
	}

	@Test
	void blockingFailureRollsBackTheEntireConfirmationAndKeepsTheToken() throws Exception {
		student("student@gmail.com", "SE123456", "A");
		when(emails.enqueue(any())).thenThrow(new RuntimeException("blocked"));
		RosterPreviewResponse preview = service.preview(
				course.getId(),
				CourseRosterWorkbookTest.filledWorkbook(
						"SE1705",
						List.of(
								new String[] {"1", "SE1705", "A", "SE123456", "student@gmail.com", ""},
								new String[] {"2", "SE1705", "B", "SE000002", "new@gmail.com", ""})),
				admin);
		assertThrows(RuntimeException.class, () -> service.confirm(course.getId(), preview.previewToken(), admin, auditReq()));
		assertTrue(store.enrollments.isEmpty());
		assertTrue(store.invitations.isEmpty());
		assertTrue(previews.find(preview.previewToken()).isPresent());
		verify(audit, never())
				.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void expiredWrongUserAndWrongCoursePreviewTokensAreRejected() throws Exception {
		RosterPreviewResponse preview = previewRow("SE1705", "A", "SE000001", "a@gmail.com", "");
		AcademicException expired = assertThrows(
				AcademicException.class, () -> service.confirm(course.getId(), "missing", admin, auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_PREVIEW_EXPIRED, expired.getCode());

		UserAccount otherAdmin = account(AccountRole.ADMIN, "other-admin@saga.local");
		AcademicException wrongUser = assertThrows(
				AcademicException.class,
				() -> service.confirm(course.getId(), preview.previewToken(), otherAdmin, auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_PREVIEW_MISMATCH, wrongUser.getCode());
		assertEquals(HttpStatus.FORBIDDEN, wrongUser.getStatus());

		Course other = course("SE1706");
		AcademicException wrongCourse = assertThrows(
				AcademicException.class,
				() -> service.confirm(other.getId(), preview.previewToken(), admin, auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_PREVIEW_MISMATCH, wrongCourse.getCode());
	}

	@Test
	void confirmWithBlockingPreviewIsRejected() throws Exception {
		RosterPreviewResponse preview = previewRow("SE1706", "A", "SE000001", "a@gmail.com", "");
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.confirm(course.getId(), preview.previewToken(), admin, auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_CONFIRM_BLOCKED, ex.getCode());
		assertTrue(store.enrollments.isEmpty());
		assertTrue(previews.find(preview.previewToken()).isPresent());
	}

	@Test
	void pendingAndSentInvitationsAreIdempotentReuse() throws Exception {
		seedInvitation("pending@gmail.com", "SE000010", StudentInvitationStatus.PENDING);
		seedInvitation("sent@gmail.com", "SE000011", StudentInvitationStatus.SENT);
		RosterPreviewResponse pending = previewRow("SE1705", "P", "SE000010", "pending@gmail.com", "");
		RosterPreviewResponse sent = previewRow("SE1705", "S", "SE000011", "sent@gmail.com", "");
		assertEquals(RosterRowAction.ALREADY_INVITED, pending.rows().getFirst().action());
		assertEquals(RosterRowAction.ALREADY_INVITED, sent.rows().getFirst().action());
		service.confirm(course.getId(), pending.previewToken(), admin, auditReq());
		service.confirm(course.getId(), sent.previewToken(), admin, auditReq());
		assertEquals(2, store.invitations.size());
		verify(emails, never()).enqueue(any());
	}

	@Test
	void cancelledAndFailedInvitationsReactivateTheExistingRow() throws Exception {
		StudentCourseInvitation cancelled = seedInvitation("old@gmail.com", "SE000012", StudentInvitationStatus.CANCELLED);
		StudentCourseInvitation failed = seedInvitation("fail@gmail.com", "SE000013", StudentInvitationStatus.FAILED);
		RosterPreviewResponse cancelledPreview = previewRow("SE1705", "Old", "SE000012", "old@gmail.com", "");
		RosterPreviewResponse failedPreview = previewRow("SE1705", "Fail", "SE000013", "fail@gmail.com", "");
		assertEquals(RosterRowAction.READY_INVITE, cancelledPreview.rows().getFirst().action());
		assertEquals(RosterRowAction.READY_INVITE, failedPreview.rows().getFirst().action());
		service.confirm(course.getId(), cancelledPreview.previewToken(), admin, auditReq());
		service.confirm(course.getId(), failedPreview.previewToken(), admin, auditReq());
		assertEquals(2, store.invitations.size());
		assertEquals(cancelled.getId(), store.findInvitationByCourseAndEmail(course.getId(), "old@gmail.com").orElseThrow().getId());
		assertEquals(failed.getId(), store.findInvitationByCourseAndEmail(course.getId(), "fail@gmail.com").orElseThrow().getId());
		assertEquals(StudentInvitationStatus.PENDING, cancelled.getInvitationStatus());
		assertEquals(StudentInvitationStatus.PENDING, failed.getInvitationStatus());
		verify(emails, times(2)).enqueue(any());
	}

	@Test
	void claimedInvitationWithExistingAccountFollowsEnrollmentPath() throws Exception {
		student("claimed@gmail.com", "SE000014", "Claimed");
		seedInvitation("claimed@gmail.com", "SE000014", StudentInvitationStatus.CLAIMED);
		RosterPreviewResponse preview = previewRow("SE1705", "Claimed", "SE000014", "claimed@gmail.com", "");
		assertEquals(RosterRowAction.READY_ENROLL, preview.rows().getFirst().action());
		service.confirm(course.getId(), preview.previewToken(), admin, auditReq());
		assertEquals(1, store.enrollments.size());
		assertEquals(1, store.invitations.size());
		assertEquals(StudentInvitationStatus.CLAIMED, store.invitations.values().iterator().next().getInvitationStatus());
		ArgumentCaptor<EmailEnqueueRequest> captor = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(captor.capture());
		assertEquals("COURSE_ENROLLED", captor.getValue().emailType());
	}

	@Test
	void oversizedFileIsRejected() {
		RosterProperties tiny = new RosterProperties();
		tiny.setMaxFileBytes(10);
		CourseRosterService limited = new CourseRosterService(
				store,
				previews,
				tiny,
				new AuthProperties(),
				new InstitutionalEmailPolicy(new AuthProperties()),
				emails,
				audit);
		AcademicException ex = assertThrows(
				AcademicException.class, () -> limited.preview(course.getId(), new byte[11], admin));
		assertEquals(AcademicErrorCode.ROSTER_FILE_TOO_LARGE, ex.getCode());
	}

	@Test
	void rosterServiceDoesNotDependOnJavaMailSender() {
		for (var ctor : CourseRosterService.class.getDeclaredConstructors()) {
			assertFalse(List.of(ctor.getParameterTypes()).contains(JavaMailSender.class));
		}
	}

	@Test
	void withdrawnEnrollmentIsReactivatedWithoutDuplicating() throws Exception {
		StudentProfile profile = student("student@gmail.com", "SE123456", "A");
		CourseEnrollment existing = new CourseEnrollment();
		existing.setStudentProfile(profile);
		existing.setCourse(course);
		existing.setEnrollmentStatus(EnrollmentStatus.WITHDRAWN);
		existing.setEnrolledAt(LocalDateTime.now().minusDays(10));
		store.saveEnrollment(existing);
		RosterPreviewResponse preview = previewRow("SE1705", "A", "SE123456", "student@gmail.com", "");
		assertEquals(RosterRowAction.READY_ENROLL, preview.rows().getFirst().action());
		service.confirm(course.getId(), preview.previewToken(), admin, auditReq());
		assertEquals(1, store.enrollments.size());
		assertEquals(EnrollmentStatus.ACTIVE, store.enrollments.values().iterator().next().getEnrollmentStatus());
		verify(emails, times(1)).enqueue(any());
	}

	private RosterPreviewResponse previewRow(
			String classCode, String fullName, String studentCode, String email, String memberCode) throws Exception {
		return service.preview(
				course.getId(),
				CourseRosterWorkbookTest.filledWorkbook(
						"SE1705",
						List.<String[]>of(new String[] {"1", classCode, fullName, studentCode, email, memberCode})),
				admin);
	}

	private StudentCourseInvitation seedInvitation(String email, String studentCode, StudentInvitationStatus status) {
		StudentCourseInvitation invitation = new StudentCourseInvitation();
		invitation.setCourse(course);
		invitation.setEmail(email);
		invitation.setStudentCode(studentCode);
		invitation.setFullName("Seed");
		invitation.setInvitationType(StudentInvitationType.COURSE_JOIN);
		invitation.setInvitationStatus(status);
		invitation.setAttemptCount(0);
		invitation.setVersion(0L);
		store.saveInvitation(invitation);
		return invitation;
	}

	private StudentProfile student(String email, String studentCode, String fullName) {
		UserAccount account = account(AccountRole.STUDENT, email);
		account.setFullName(fullName);
		StudentProfile profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(account);
		profile.setStudentCode(studentCode);
		profile.setVersion(0L);
		store.putStudent(profile);
		return profile;
	}

	private UserAccount account(AccountRole role, String email) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail(email);
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		store.putUser(account);
		return account;
	}

	private Course course(String classCode) {
		Semester semester = new Semester();
		semester.setId(UUID.randomUUID());
		semester.setCode("FA26");
		AcademicClass academicClass = new AcademicClass();
		academicClass.setId(UUID.randomUUID());
		academicClass.setClassCode(classCode);
		academicClass.setName(classCode);
		academicClass.setSemester(semester);
		Subject subject = new Subject();
		subject.setId(UUID.randomUUID());
		subject.setSubjectCode("SWP391");
		Course created = new Course();
		created.setId(UUID.randomUUID());
		created.setName("SWP391 · " + classCode);
		created.setAcademicClass(academicClass);
		created.setSemester(semester);
		created.setSubject(subject);
		store.putCourse(created);
		return created;
	}

	private static AuditRequest auditReq() {
		return new AuditRequest("req-1", "127.0.0.1", "JUnit");
	}
}
