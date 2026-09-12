package com.saga.be.service.roster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.saga.be.dto.roster.AddRosterStudentRequest;
import com.saga.be.dto.roster.AddRosterStudentResponse;
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
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.RosterRowAction;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
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
	void studentCodeOwnedByAnotherAccountIsConflict() throws Exception {
		student("owner@gmail.com", "SE123456", "Owner");
		RosterPreviewResponse preview = previewRow("SE1705", "Other", "SE123456", "other@gmail.com", "");
		assertEquals(RosterRowAction.CONFLICT, preview.rows().getFirst().action());
		assertTrue(preview.rows().getFirst().errors().getFirst().contains("another account"));
	}

	@Test
	void emailAndStudentCodeMatchingDifferentInvitationsIsConflict() throws Exception {
		seedInvitation("a@gmail.com", "SE000001", StudentInvitationStatus.PENDING);
		seedInvitation("b@gmail.com", "SE000002", StudentInvitationStatus.PENDING);
		RosterPreviewResponse preview = previewRow("SE1705", "Mixed", "SE000002", "a@gmail.com", "");
		assertEquals(RosterRowAction.CONFLICT, preview.rows().getFirst().action());
		assertTrue(preview.rows().getFirst().errors().getFirst().contains("different invitations"));
	}

	@Test
	void claimedInvitationWithoutAccountIsAlreadyEnrolled() throws Exception {
		seedInvitation("ghost@gmail.com", "SE000020", StudentInvitationStatus.CLAIMED);
		RosterPreviewResponse preview = previewRow("SE1705", "Ghost", "SE000020", "ghost@gmail.com", "");
		assertEquals(RosterRowAction.ALREADY_ENROLLED, preview.rows().getFirst().action());
		assertTrue(preview.rows().getFirst().warnings().stream().anyMatch(item -> item.contains("claimed")));
	}

	@Test
	void previewAndConfirmUseBoundedLookupCallsAsRosterGrows() throws Exception {
		CountingCourseRosterStore counting = new CountingCourseRosterStore(store);
		AuthProperties authProperties = new AuthProperties();
		authProperties.setFrontendOrigins(List.of("http://localhost:3000"));
		service = new CourseRosterService(
				counting,
				previews,
				new RosterProperties(),
				authProperties,
				new InstitutionalEmailPolicy(authProperties),
				emails,
				audit);
		student("one@gmail.com", "SE100001", "One");
		student("two@gmail.com", "SE100002", "Two");
		RosterPreviewResponse preview = service.preview(
				course.getId(),
				CourseRosterWorkbookTest.filledWorkbook(
						"SE1705",
						List.of(
								new String[] {"1", "SE1705", "One", "SE100001", "one@gmail.com", ""},
								new String[] {"2", "SE1705", "Two", "SE100002", "two@gmail.com", ""},
								new String[] {"3", "SE1705", "New A", "SE100003", "new-a@gmail.com", ""},
								new String[] {"4", "SE1705", "New B", "SE100004", "new-b@gmail.com", ""},
								new String[] {"5", "SE1705", "New C", "SE100005", "new-c@gmail.com", ""})),
				admin);
		assertEquals(RosterRowAction.READY_ENROLL, preview.rows().get(0).action());
		assertEquals(RosterRowAction.READY_ENROLL, preview.rows().get(1).action());
		assertEquals(RosterRowAction.READY_INVITE, preview.rows().get(2).action());
		assertEquals(1, counting.usersByEmails);
		assertEquals(1, counting.studentsByCodes);
		assertEquals(1, counting.studentsByUserIds);
		assertEquals(1, counting.listEnrollments);
		assertEquals(1, counting.listInvitations);
		assertEquals(0, counting.userByEmail);
		assertEquals(0, counting.studentByCode);
		assertEquals(0, counting.studentByUserId);
		assertEquals(0, counting.enrollmentByProfile);
		assertEquals(0, counting.invitationByEmail);
		assertEquals(0, counting.invitationByCode);
		counting.reset();
		service.confirm(course.getId(), preview.previewToken(), admin, auditReq());
		assertEquals(1, counting.usersByEmails);
		assertEquals(1, counting.studentsByCodes);
		assertEquals(1, counting.listEnrollments);
		assertEquals(1, counting.listInvitations);
		assertEquals(0, counting.userByEmail);
		assertEquals(0, counting.invitationByEmail);
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

	@Test
	void addStudentEnrollsExistingAccount() {
		student("student@gmail.com", "SE123456", "A");
		AddRosterStudentResponse response =
				service.addStudent(course.getId(), addRequest("A", "SE123456", "student@gmail.com"), admin, auditReq());
		assertEquals("ENROLLED", response.result());
		assertEquals("ENROLLMENT", response.entry().kind());
		assertEquals(EnrollmentStatus.ACTIVE, store.enrollments.values().iterator().next().getEnrollmentStatus());
		assertEquals("SE123456", response.entry().studentCode());
		ArgumentCaptor<EmailEnqueueRequest> captor = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(captor.capture());
		assertEquals("COURSE_ENROLLED", captor.getValue().emailType());
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
	}

	@Test
	void addStudentInvitesUnknownAccountWithoutCreatingUser() {
		AddRosterStudentResponse response =
				service.addStudent(course.getId(), addRequest("New", "SE000001", "new@gmail.com"), admin, auditReq());
		assertEquals("INVITED", response.result());
		assertEquals("INVITATION", response.entry().kind());
		assertEquals("PENDING", response.entry().invitationStatus());
		assertTrue(store.users.values().stream().noneMatch(user -> "new@gmail.com".equals(user.getEmail())));
		assertTrue(store.students.isEmpty());
		ArgumentCaptor<EmailEnqueueRequest> captor = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(captor.capture());
		assertEquals("COURSE_INVITATION", captor.getValue().emailType());
		assertEquals(null, captor.getValue().recipientUserId());
	}

	@Test
	void addStudentInvitationOutboxMatchesXlsxConfirm() throws Exception {
		AddRosterStudentResponse added =
				service.addStudent(course.getId(), addRequest("New", "SE000002", "invitee@gmail.com"), admin, auditReq());
		assertEquals("INVITED", added.result());
		ArgumentCaptor<EmailEnqueueRequest> addCaptor = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails, times(1)).enqueue(addCaptor.capture());
		EmailEnqueueRequest fromAdd = addCaptor.getValue();

		org.mockito.Mockito.clearInvocations(emails);
		RosterPreviewResponse preview = previewRow("SE1705", "Other", "SE000003", "other-invite@gmail.com", "");
		service.confirm(course.getId(), preview.previewToken(), admin, auditReq());
		ArgumentCaptor<EmailEnqueueRequest> confirmCaptor = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails, times(1)).enqueue(confirmCaptor.capture());
		EmailEnqueueRequest fromConfirm = confirmCaptor.getValue();

		assertEquals(fromConfirm.emailType(), fromAdd.emailType());
		assertEquals(fromConfirm.templateKey(), fromAdd.templateKey());
		assertEquals(fromConfirm.recipientUserId(), fromAdd.recipientUserId());
		assertEquals(fromConfirm.scheduledAt(), fromAdd.scheduledAt());
	}

	@Test
	void addStudentMemberCodeIsOptionalAndNotPersisted() {
		AddRosterStudentResponse response = service.addStudent(
				course.getId(),
				new AddRosterStudentRequest("New", "SE000004", "membercode@gmail.com", "HaiLHSE000004"),
				admin,
				auditReq());
		assertEquals("INVITED", response.result());
		StudentCourseInvitation invitation = store.invitations.values().iterator().next();
		assertEquals("SE000004", invitation.getStudentCode());
		assertEquals("membercode@gmail.com", invitation.getEmail());
		assertFalse(invitation.getFullName().contains("HaiLH"));
	}

	@Test
	void addStudentGoogleLocalPartStudentCodeMismatchRemainsBlocked() {
		student("hailhse183904@fpt.edu.vn", "hailhse183904", "Le Hoang Hai (K18 HCM)");
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.addStudent(
						course.getId(),
						addRequest("Lê Hoàng Hải", "SE183904", "hailhse183904@fpt.edu.vn"),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_CONFIRM_BLOCKED, ex.getCode());
		assertTrue(ex.getMessage().contains("StudentCode does not match"));
		assertEquals("hailhse183904", store.students.values().iterator().next().getStudentCode());
		assertTrue(store.enrollments.isEmpty());
		verify(emails, never()).enqueue(any());
	}

	@Test
	void addStudentBlankStudentCodeIsFilledOnceFromRoster() {
		StudentProfile profile = student("hailhse183904@fpt.edu.vn", null, "Le Hoang Hai");
		assertNull(profile.getStudentCode());
		AddRosterStudentResponse response = service.addStudent(
				course.getId(),
				addRequest("Le Hoang Hai", "SE183904", "hailhse183904@fpt.edu.vn"),
				admin,
				auditReq());
		assertEquals("ENROLLED", response.result());
		assertEquals("SE183904", profile.getStudentCode());
		assertEquals(EnrollmentStatus.ACTIVE, store.enrollments.values().iterator().next().getEnrollmentStatus());
	}

	@Test
	void xlsxConfirmBlankStudentCodeIsFilledOnceSameAsManualAdd() throws Exception {
		StudentProfile profile = student("blank@gmail.com", null, "Blank");
		RosterPreviewResponse preview = previewRow("SE1705", "Blank", "SE777777", "blank@gmail.com", "");
		assertEquals(RosterRowAction.READY_ENROLL, preview.rows().getFirst().action());
		service.confirm(course.getId(), preview.previewToken(), admin, auditReq());
		assertEquals("SE777777", profile.getStudentCode());
	}

	@Test
	void addStudentDoesNotOverwriteNonblankStudentCode() {
		student("student@gmail.com", "SE111111", "A");
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.addStudent(
						course.getId(), addRequest("A", "SE222222", "student@gmail.com"), admin, auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_CONFIRM_BLOCKED, ex.getCode());
		assertEquals("SE111111", store.students.values().iterator().next().getStudentCode());
	}

	@Test
	void addStudentIncomingCodeOwnedByAnotherProfileIsBlocked() {
		student("owner@gmail.com", "SE183904", "Owner");
		student("hailhse183904@fpt.edu.vn", null, "Hai");
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.addStudent(
						course.getId(),
						addRequest("Hai", "SE183904", "hailhse183904@fpt.edu.vn"),
						admin,
						auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_CONFIRM_BLOCKED, ex.getCode());
		StudentProfile hai = store.students.values().stream()
				.filter(p -> p.getUserAccount().getEmail().equals("hailhse183904@fpt.edu.vn"))
				.findFirst()
				.orElseThrow();
		assertNull(hai.getStudentCode());
	}

	@Test
	void addStudentInactiveAccountMatchesXlsxConfirmBehavior() throws Exception {
		StudentProfile profile = student("inactive@gmail.com", "SE555555", "Inactive");
		profile.getUserAccount().setAccountStatus(AccountStatus.INACTIVE);
		AddRosterStudentResponse added = service.addStudent(
				course.getId(), addRequest("Inactive", "SE555555", "inactive@gmail.com"), admin, auditReq());
		assertEquals("ENROLLED", added.result());
		assertEquals(EnrollmentStatus.ACTIVE, store.enrollments.values().iterator().next().getEnrollmentStatus());
		assertEquals(AccountStatus.INACTIVE, profile.getUserAccount().getAccountStatus());

		StudentProfile profile2 = student("inactive2@gmail.com", "SE555556", "Inactive2");
		profile2.getUserAccount().setAccountStatus(AccountStatus.SUSPENDED);
		RosterPreviewResponse preview = previewRow("SE1705", "Inactive2", "SE555556", "inactive2@gmail.com", "");
		assertEquals(RosterRowAction.READY_ENROLL, preview.rows().getFirst().action());
		service.confirm(course.getId(), preview.previewToken(), admin, auditReq());
		assertEquals(AccountStatus.SUSPENDED, profile2.getUserAccount().getAccountStatus());
	}

	@Test
	void addStudentAlreadyEnrolledIsIdempotent() {
		student("student@gmail.com", "SE123456", "A");
		service.addStudent(course.getId(), addRequest("A", "SE123456", "student@gmail.com"), admin, auditReq());
		AddRosterStudentResponse again =
				service.addStudent(course.getId(), addRequest("A", "SE123456", "student@gmail.com"), admin, auditReq());
		assertEquals("ALREADY_ENROLLED", again.result());
		assertEquals(1, store.enrollments.size());
		verify(emails, times(1)).enqueue(any());
	}

	@Test
	void addStudentAlreadyInvitedIsIdempotent() {
		seedInvitation("pending@gmail.com", "SE000010", StudentInvitationStatus.PENDING);
		AddRosterStudentResponse response =
				service.addStudent(course.getId(), addRequest("P", "SE000010", "pending@gmail.com"), admin, auditReq());
		assertEquals("ALREADY_INVITED", response.result());
		assertEquals(1, store.invitations.size());
		verify(emails, never()).enqueue(any());
	}

	@Test
	void addStudentEmailOwnedByAnotherStudentCodeIsConflict() {
		student("owner@gmail.com", "SE123456", "Owner");
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.addStudent(
						course.getId(), addRequest("Other", "SE123456", "other@gmail.com"), admin, auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_CONFIRM_BLOCKED, ex.getCode());
		assertTrue(store.enrollments.isEmpty());
		verify(emails, never()).enqueue(any());
	}

	@Test
	void addStudentStudentCodeMismatchIsConflict() {
		student("student@gmail.com", "SE111111", "A");
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.addStudent(
						course.getId(), addRequest("A", "SE222222", "student@gmail.com"), admin, auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_CONFIRM_BLOCKED, ex.getCode());
		verify(emails, never()).enqueue(any());
	}

	@Test
	void addStudentRejectsNonStudentAccount() {
		account(AccountRole.LECTURER, "lecturer@fe.edu.vn");
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.addStudent(
						course.getId(), addRequest("Lecturer", "SE999999", "lecturer@fe.edu.vn"), admin, auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_CONFIRM_BLOCKED, ex.getCode());
		verify(emails, never()).enqueue(any());
	}

	@Test
	void addStudentUsesOneBoundedLookupPass() {
		CountingCourseRosterStore counting = new CountingCourseRosterStore(store);
		AuthProperties authProperties = new AuthProperties();
		authProperties.setFrontendOrigins(List.of("http://localhost:3000"));
		service = new CourseRosterService(
				counting,
				previews,
				new RosterProperties(),
				authProperties,
				new InstitutionalEmailPolicy(authProperties),
				emails,
				audit);
		student("one@gmail.com", "SE100001", "One");
		service.addStudent(course.getId(), addRequest("One", "SE100001", "one@gmail.com"), admin, auditReq());
		assertEquals(1, counting.usersByEmails);
		assertEquals(1, counting.studentsByCodes);
		assertEquals(1, counting.studentsByUserIds);
		assertEquals(1, counting.listEnrollments);
		assertEquals(1, counting.listInvitations);
		assertEquals(0, counting.userByEmail);
		assertEquals(0, counting.studentByCode);
		assertEquals(0, counting.invitationByEmail);
	}

	@Test
	void importWorkbookContainingOnlyDLeavesAbcAndAddsD() throws Exception {
		enrollExisting("a@gmail.com", "SE00000A", "A");
		enrollExisting("b@gmail.com", "SE00000B", "B");
		enrollExisting("c@gmail.com", "SE00000C", "C");
		RosterPreviewResponse preview = service.preview(
				course.getId(),
				CourseRosterWorkbookTest.filledWorkbook(
						"SE1705", List.<String[]>of(new String[] {"1", "SE1705", "D", "SE00000D", "d@gmail.com", ""})),
				admin);
		assertEquals(RosterRowAction.READY_INVITE, preview.rows().getFirst().action());
		service.confirm(course.getId(), preview.previewToken(), admin, auditReq());
		assertEquals(3, store.enrollments.size());
		assertEquals(1, store.invitations.size());
		assertTrue(store.enrollments.values().stream()
				.allMatch(row -> row.getEnrollmentStatus() == EnrollmentStatus.ACTIVE));
		assertEquals("d@gmail.com", store.invitations.values().iterator().next().getEmail());
		CourseRosterResponse roster = service.getRoster(course.getId());
		assertEquals(3, roster.enrolledCount());
		assertEquals(1, roster.pendingInvitationCount());
	}

	@Test
	void importWorkbookOmittingBDoesNotDeleteOrWithdrawB() throws Exception {
		enrollExisting("a@gmail.com", "SE00000A", "A");
		CourseEnrollment b = enrollExisting("b@gmail.com", "SE00000B", "B");
		enrollExisting("c@gmail.com", "SE00000C", "C");
		RosterPreviewResponse preview = service.preview(
				course.getId(),
				CourseRosterWorkbookTest.filledWorkbook(
						"SE1705",
						List.<String[]>of(
								new String[] {"1", "SE1705", "A", "SE00000A", "a@gmail.com", ""},
								new String[] {"2", "SE1705", "C", "SE00000C", "c@gmail.com", ""})),
				admin);
		assertEquals(RosterRowAction.ALREADY_ENROLLED, preview.rows().get(0).action());
		assertEquals(RosterRowAction.ALREADY_ENROLLED, preview.rows().get(1).action());
		RosterConfirmResponse confirmed = service.confirm(course.getId(), preview.previewToken(), admin, auditReq());
		assertEquals(2, confirmed.unchanged());
		assertEquals(3, store.enrollments.size());
		assertEquals(EnrollmentStatus.ACTIVE, store.enrollments.get(b.getId()).getEnrollmentStatus());
		verify(emails, never()).enqueue(any());
	}

	@Test
	void adminRemovesActiveStudentSuccessfully() {
		CourseEnrollment enrollment = enrollExisting("a@gmail.com", "SE00000A", "A");
		var response = service.removeEnrollment(course.getId(), enrollment.getId(), admin, auditReq());
		assertEquals("WITHDRAWN", response.enrollmentStatus());
		assertEquals(EnrollmentStatus.WITHDRAWN, store.enrollments.get(enrollment.getId()).getEnrollmentStatus());
		// Account/profile are untouched by removal — only the enrollment row's status changes.
		assertEquals(1, store.students.size());
		assertNotNull(store.users.get(enrollment.getStudentProfile().getUserAccount().getId()));
	}

	@Test
	void removingAlreadyWithdrawnEnrollmentIsConflict() {
		CourseEnrollment enrollment = enrollExisting("a@gmail.com", "SE00000A", "A");
		service.removeEnrollment(course.getId(), enrollment.getId(), admin, auditReq());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.removeEnrollment(course.getId(), enrollment.getId(), admin, auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_STUDENT_ALREADY_REMOVED, ex.getCode());
	}

	@Test
	void removingUnknownEnrollmentIsNotFound() {
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.removeEnrollment(course.getId(), UUID.randomUUID(), admin, auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_STUDENT_NOT_FOUND, ex.getCode());
	}

	@Test
	void removingEnrollmentFromAnotherCourseIsNotFound() {
		Course otherCourse = course("SE1706");
		CourseEnrollment enrollment = enrollExisting("a@gmail.com", "SE00000A", "A");
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.removeEnrollment(otherCourse.getId(), enrollment.getId(), admin, auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_STUDENT_NOT_FOUND, ex.getCode());
		assertEquals(
				EnrollmentStatus.ACTIVE,
				store.enrollments.get(enrollment.getId()).getEnrollmentStatus());
	}

	@Test
	void removingNormalTeamMemberWithdrawsEnrollmentDeletesMembershipKeepsTeam() {
		CourseEnrollment enrollment = enrollExisting("member@gmail.com", "SE00000M", "Member");
		Team team = teamWith(enrollment, RoleInTeam.MEMBER);
		var response = service.removeEnrollment(course.getId(), enrollment.getId(), admin, auditReq());
		assertEquals("WITHDRAWN", response.enrollmentStatus());
		// The TeamMember row is deleted (nothing references team_member.id, so no evidence is
		// lost) — this is what stops a later re-add from silently resurrecting old team
		// membership, since TeamMember "active"-ness is derived purely from enrollment status.
		// The Team row itself is untouched.
		assertNull(store.teamMembersByEnrollment.get(enrollment.getId()));
		assertNotNull(store.courses.get(course.getId()));
		assertEquals("Team 1", team.getName());
	}

	@Test
	void reAddingFormerTeamMemberDoesNotResurrectOldTeamMembership() {
		CourseEnrollment enrollment = enrollExisting("member@gmail.com", "SE00000M", "Member");
		teamWith(enrollment, RoleInTeam.MEMBER);
		service.removeEnrollment(course.getId(), enrollment.getId(), admin, auditReq());
		service.addStudent(course.getId(), addRequest("Member", "SE00000M", "member@gmail.com"), admin, auditReq());
		// Enrollment is reactivated (same row reused)...
		assertEquals(EnrollmentStatus.ACTIVE, store.enrollments.get(enrollment.getId()).getEnrollmentStatus());
		// ...but the old team membership is gone and stays gone: re-add does not put the student
		// back on their old team.
		assertNull(store.teamMembersByEnrollment.get(enrollment.getId()));
	}

	@Test
	void removingActiveLeaderIsBlockedAndNothingChanges() {
		CourseEnrollment enrollment = enrollExisting("leader@gmail.com", "SE00000L", "Leader");
		teamWith(enrollment, RoleInTeam.LEADER);
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.removeEnrollment(course.getId(), enrollment.getId(), admin, auditReq()));
		assertEquals(AcademicErrorCode.TEAM_LEADER_REMOVAL_REQUIRES_REASSIGNMENT, ex.getCode());
		assertEquals(
				EnrollmentStatus.ACTIVE,
				store.enrollments.get(enrollment.getId()).getEnrollmentStatus());
		// Blocked whole: the Leader's TeamMember row is NOT deleted either.
		assertNotNull(store.teamMembersByEnrollment.get(enrollment.getId()));
		assertEquals(RoleInTeam.LEADER, store.teamMembersByEnrollment.get(enrollment.getId()).getRoleInTeam());
	}

	@Test
	void removingStudentFromCourseADoesNotAffectCourseB() {
		StudentProfile profile = student("both@gmail.com", "SE0000AB", "Both");
		CourseEnrollment enrollmentA = enrollForCourse(profile, course);
		Course courseB = course("SE1706");
		CourseEnrollment enrollmentB = enrollForCourse(profile, courseB);
		service.removeEnrollment(course.getId(), enrollmentA.getId(), admin, auditReq());
		assertEquals(EnrollmentStatus.WITHDRAWN, store.enrollments.get(enrollmentA.getId()).getEnrollmentStatus());
		assertEquals(EnrollmentStatus.ACTIVE, store.enrollments.get(enrollmentB.getId()).getEnrollmentStatus());
	}

	@Test
	void adminCancelsPendingInvitationSuccessfully() {
		StudentCourseInvitation invitation = seedInvitation("new@gmail.com", "SE000099", StudentInvitationStatus.PENDING);
		var response = service.cancelInvitation(course.getId(), invitation.getId(), admin, auditReq());
		assertEquals("CANCELLED", response.invitationStatus());
		assertEquals(
				StudentInvitationStatus.CANCELLED,
				store.invitations.get(invitation.getId()).getInvitationStatus());
	}

	@Test
	void cancellingAlreadyCancelledInvitationIsConflict() {
		StudentCourseInvitation invitation = seedInvitation("new@gmail.com", "SE000099", StudentInvitationStatus.PENDING);
		service.cancelInvitation(course.getId(), invitation.getId(), admin, auditReq());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.cancelInvitation(course.getId(), invitation.getId(), admin, auditReq()));
		assertEquals(AcademicErrorCode.ROSTER_STUDENT_ALREADY_REMOVED, ex.getCode());
	}

	@Test
	void cancelledInvitationDoesNotCountAsRosterEntry() {
		StudentCourseInvitation invitation = seedInvitation("new@gmail.com", "SE000099", StudentInvitationStatus.PENDING);
		service.cancelInvitation(course.getId(), invitation.getId(), admin, auditReq());
		CourseRosterResponse roster = service.getRoster(course.getId());
		assertEquals(0, roster.pendingInvitationCount());
		assertTrue(roster.entries().isEmpty());
	}

	@Test
	void laterSignupDoesNotConsumeCancelledInvitation() {
		StudentCourseInvitation invitation = seedInvitation("new@gmail.com", "SE000099", StudentInvitationStatus.PENDING);
		service.cancelInvitation(course.getId(), invitation.getId(), admin, auditReq());
		// Simulate a later admin re-add for the same identity: classifyIdentity treats a
		// CANCELLED (non-outstanding, non-CLAIMED) invitation as READY_INVITE, reusing this same
		// row rather than requiring a duplicate — verified via addStudent below.
		service.addStudent(course.getId(), addRequest("New Student", "SE000099", "new@gmail.com"), admin, auditReq());
		assertEquals(1, store.invitations.size());
		assertEquals(
				StudentInvitationStatus.PENDING,
				store.invitations.get(invitation.getId()).getInvitationStatus());
	}

	@Test
	void reAddingWithdrawnStudentReactivatesSameEnrollmentRow() {
		CourseEnrollment enrollment = enrollExisting("a@gmail.com", "SE00000A", "A");
		service.removeEnrollment(course.getId(), enrollment.getId(), admin, auditReq());
		service.addStudent(course.getId(), addRequest("A", "SE00000A", "a@gmail.com"), admin, auditReq());
		assertEquals(1, store.enrollments.size());
		assertEquals(EnrollmentStatus.ACTIVE, store.enrollments.get(enrollment.getId()).getEnrollmentStatus());
	}

	private CourseEnrollment enrollForCourse(StudentProfile profile, Course targetCourse) {
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setCourse(targetCourse);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		enrollment.setEnrolledAt(LocalDateTime.now());
		store.saveEnrollment(enrollment);
		return enrollment;
	}

	private Team teamWith(CourseEnrollment enrollment, RoleInTeam role) {
		Team team = new Team();
		team.setId(UUID.randomUUID());
		team.setCourse(course);
		team.setTeamNo(1);
		team.setName("Team 1");
		TeamMember member = new TeamMember();
		member.setId(UUID.randomUUID());
		member.setTeam(team);
		member.setCourse(course);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		store.putTeamMember(member);
		return team;
	}

	private AddRosterStudentRequest addRequest(String fullName, String studentCode, String email) {
		return new AddRosterStudentRequest(fullName, studentCode, email, null);
	}

	private CourseEnrollment enrollExisting(String email, String studentCode, String fullName) {
		StudentProfile profile = student(email, studentCode, fullName);
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		enrollment.setEnrolledAt(LocalDateTime.now());
		store.saveEnrollment(enrollment);
		return enrollment;
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

	private static final class CountingCourseRosterStore implements CourseRosterStore {
		private final CourseRosterStore delegate;
		int usersByEmails;
		int studentsByCodes;
		int studentsByUserIds;
		int listEnrollments;
		int listInvitations;
		int userByEmail;
		int studentByCode;
		int studentByUserId;
		int enrollmentByProfile;
		int invitationByEmail;
		int invitationByCode;

		CountingCourseRosterStore(CourseRosterStore delegate) {
			this.delegate = delegate;
		}

		void reset() {
			usersByEmails = 0;
			studentsByCodes = 0;
			studentsByUserIds = 0;
			listEnrollments = 0;
			listInvitations = 0;
			userByEmail = 0;
			studentByCode = 0;
			studentByUserId = 0;
			enrollmentByProfile = 0;
			invitationByEmail = 0;
			invitationByCode = 0;
		}

		@Override
		public java.util.Optional<Course> findCourse(UUID courseId) {
			return delegate.findCourse(courseId);
		}

		@Override
		public java.util.Optional<UserAccount> findUserByEmail(String email) {
			userByEmail++;
			return delegate.findUserByEmail(email);
		}

		@Override
		public List<UserAccount> findUsersByEmails(java.util.Collection<String> emails) {
			usersByEmails++;
			return delegate.findUsersByEmails(emails);
		}

		@Override
		public java.util.Optional<StudentProfile> findStudentByUserId(UUID userId) {
			studentByUserId++;
			return delegate.findStudentByUserId(userId);
		}

		@Override
		public List<StudentProfile> findStudentsByUserIds(java.util.Collection<UUID> userIds) {
			studentsByUserIds++;
			return delegate.findStudentsByUserIds(userIds);
		}

		@Override
		public java.util.Optional<StudentProfile> findStudentByCode(String studentCode) {
			studentByCode++;
			return delegate.findStudentByCode(studentCode);
		}

		@Override
		public List<StudentProfile> findStudentsByCodes(java.util.Collection<String> studentCodes) {
			studentsByCodes++;
			return delegate.findStudentsByCodes(studentCodes);
		}

		@Override
		public java.util.Optional<CourseEnrollment> findEnrollment(UUID studentProfileId, UUID courseId) {
			enrollmentByProfile++;
			return delegate.findEnrollment(studentProfileId, courseId);
		}

		@Override
		public List<CourseEnrollment> listEnrollments(UUID courseId) {
			listEnrollments++;
			return delegate.listEnrollments(courseId);
		}

		@Override
		public CourseEnrollment saveEnrollment(CourseEnrollment enrollment) {
			return delegate.saveEnrollment(enrollment);
		}

		@Override
		public java.util.Optional<CourseEnrollment> findEnrollmentById(UUID enrollmentId) {
			return delegate.findEnrollmentById(enrollmentId);
		}

		@Override
		public java.util.Optional<StudentCourseInvitation> findInvitationByCourseAndEmail(UUID courseId, String email) {
			invitationByEmail++;
			return delegate.findInvitationByCourseAndEmail(courseId, email);
		}

		@Override
		public java.util.Optional<StudentCourseInvitation> findInvitationByCourseAndStudentCode(
				UUID courseId, String studentCode) {
			invitationByCode++;
			return delegate.findInvitationByCourseAndStudentCode(courseId, studentCode);
		}

		@Override
		public java.util.Optional<StudentCourseInvitation> findInvitationById(UUID invitationId) {
			return delegate.findInvitationById(invitationId);
		}

		@Override
		public List<StudentCourseInvitation> listInvitations(UUID courseId) {
			listInvitations++;
			return delegate.listInvitations(courseId);
		}

		@Override
		public List<StudentCourseInvitation> listPendingByEmail(String email) {
			return delegate.listPendingByEmail(email);
		}

		@Override
		public StudentCourseInvitation saveInvitation(StudentCourseInvitation invitation) {
			return delegate.saveInvitation(invitation);
		}

		@Override
		public StudentProfile saveStudent(StudentProfile profile) {
			return delegate.saveStudent(profile);
		}

		@Override
		public java.util.Optional<UUID> findTeamIdByEnrollment(UUID enrollmentId) {
			return delegate.findTeamIdByEnrollment(enrollmentId);
		}

		@Override
		public java.util.Optional<com.saga.be.entity.project.TeamMember> lockTeamAndReloadMembership(
				UUID teamId, UUID enrollmentId) {
			return delegate.lockTeamAndReloadMembership(teamId, enrollmentId);
		}

		@Override
		public void deleteTeamMembership(com.saga.be.entity.project.TeamMember member) {
			delegate.deleteTeamMembership(member);
		}

		@Override
		public <T> T inTransaction(java.util.function.Supplier<T> action) {
			return delegate.inTransaction(action);
		}
	}
}
