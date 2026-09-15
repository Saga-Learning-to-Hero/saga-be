package com.saga.be.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.notification.ManualNotificationRequest;
import com.saga.be.dto.notification.NotificationSendResponse;
import com.saga.be.dto.notification.UserNotificationResponse;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.BroadcastAudience;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.notification.NotificationBroadcast;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.NotificationBroadcastRepository;
import com.saga.be.repository.NotificationDeliveryRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.repository.UserNotificationRepository;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ManualNotificationServiceTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private NotificationBroadcastRepository broadcasts;
	@Mock
	private NotificationService notifications;
	@Mock
	private NotificationDeliveryRepository deliveries;
	@Mock
	private UserNotificationRepository userNotifications;
	@Mock
	private LecturerCourseAuthorization authorization;
	@Mock
	private LecturerProfileRepository lecturerProfiles;
	@Mock
	private CourseRepository courses;
	@Mock
	private CourseEnrollmentRepository enrollments;
	@Mock
	private TeamRepository teams;
	@Mock
	private TeamMemberRepository teamMembers;
	@Mock
	private StudentProfileRepository students;
	@Mock
	private AuditService auditLogs;

	private ManualNotificationService service;
	private final AuditRequest audit = new AuditRequest("req-1", "127.0.0.1", "test-agent");

	@BeforeEach
	void setUp() {
		service = new ManualNotificationService(
				users,
				broadcasts,
				notifications,
				deliveries,
				userNotifications,
				authorization,
				lecturerProfiles,
				courses,
				enrollments,
				teams,
				teamMembers,
				students,
				auditLogs);
	}

	@Test
	void adminSystemSendsToActiveStudentsAndLecturersInSortedOrder() {
		UserAccount admin = account(AccountRole.ADMIN);
		UUID later = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		UUID earlier = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		when(users.findIdsByAccountStatusAndAccountRoleInOrderByIdAsc(eq(AccountStatus.ACTIVE), any()))
				.thenReturn(List.of(later, earlier, admin.getId()));
		stubNewBroadcast(admin);
		when(notifications.createNotification(any(), any(), any(), any(), any(), any(), any()))
				.thenAnswer(invocation -> response(invocation.getArgument(0)));
		when(deliveries.countByNotification_Id(any())).thenReturn(1L);
		when(userNotifications.countByBroadcast_Id(any())).thenReturn(2L);

		NotificationSendResponse result =
				service.sendSystem(admin, "key-1", new ManualNotificationRequest("Hello", "Body", "/inbox"), audit);

		assertEquals(ManualNotificationService.SCOPE_ALL, result.scope());
		assertEquals(2, result.recipientCount());
		assertEquals(2, result.createdCount());
		InOrder order = Mockito.inOrder(notifications);
		order.verify(notifications)
				.createNotification(
						eq(earlier),
						eq(NotificationType.SYSTEM),
						eq("Hello"),
						eq("Body"),
						eq("/inbox"),
						any(),
						any());
		order.verify(notifications)
				.createNotification(
						eq(later), eq(NotificationType.SYSTEM), eq("Hello"), eq("Body"), eq("/inbox"), any(), any());
		verify(users).findIdsByAccountStatusAndAccountRoleInOrderByIdAsc(eq(AccountStatus.ACTIVE), any());
		verify(auditLogs)
				.record(
						eq(admin),
						isNull(),
						isNull(),
						eq(ManualNotificationService.ACTION_SYSTEM),
						eq("NOTIFICATION_BROADCAST"),
						eq(result.sendId()),
						isNull(),
						isNull(),
						any(),
						eq(AuditSource.API),
						eq("req-1"),
						eq("127.0.0.1"),
						eq("test-agent"));
	}

	@Test
	void lecturerAndStudentCannotSendAdminSystem() {
		AcademicException lecturer = assertThrows(
				AcademicException.class,
				() -> service.sendSystem(
						account(AccountRole.LECTURER),
						"k",
						new ManualNotificationRequest("Hello", "Body", null),
						audit));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, lecturer.getCode());
		assertEquals(HttpStatus.FORBIDDEN, lecturer.getStatus());
		AcademicException student = assertThrows(
				AcademicException.class,
				() -> service.sendSystem(
						account(AccountRole.STUDENT), "k", new ManualNotificationRequest("Hello", "Body", null), audit));
		assertEquals(HttpStatus.FORBIDDEN, student.getStatus());
		verify(notifications, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void unsafeActionUrlIsRejected() {
		UserAccount admin = account(AccountRole.ADMIN);
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.sendSystem(
						admin, "k", new ManualNotificationRequest("Hello", "Body", "javascript:alert(1)"), audit));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, ex.getCode());
		verify(broadcasts, never()).saveAndFlush(any());
	}

	@Test
	void locksSenderAndRecipientsInGlobalUuidOrderBeforeFanout() {
		UserAccount admin = account(AccountRole.ADMIN);
		admin.setId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
		UUID student = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID lecturer = UUID.fromString("22222222-2222-2222-2222-222222222222");
		when(users.findIdsByAccountStatusAndAccountRoleInOrderByIdAsc(eq(AccountStatus.ACTIVE), any()))
				.thenReturn(List.of(lecturer, student));
		stubNewBroadcast(admin);
		when(notifications.createNotification(any(), any(), any(), any(), any(), any(), any()))
				.thenAnswer(invocation -> response(invocation.getArgument(0)));
		when(deliveries.countByNotification_Id(any())).thenReturn(0L);
		when(userNotifications.countByBroadcast_Id(any())).thenReturn(2L);

		service.sendSystem(admin, "lock-order", new ManualNotificationRequest("Hello", "Body", null), audit);

		InOrder locks = Mockito.inOrder(users);
		locks.verify(users).findByIdForUpdate(student);
		locks.verify(users).findByIdForUpdate(lecturer);
		locks.verify(users).findByIdForUpdate(admin.getId());
	}

	@Test
	void idempotentRetryReturnsOriginalCountsWithoutFanout() {
		UserAccount admin = account(AccountRole.ADMIN);
		when(users.findIdsByAccountStatusAndAccountRoleInOrderByIdAsc(eq(AccountStatus.ACTIVE), any()))
				.thenReturn(List.of(UUID.randomUUID()));
		stubLocks(admin);
		NotificationBroadcast existing = new NotificationBroadcast();
		existing.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
		existing.setRecipientCount(4);
		existing.setRequestFingerprint(ManualNotificationService.fingerprint(
				new ManualNotificationService.SendPlan(
						BroadcastAudience.ALL,
						ManualNotificationService.SCOPE_ALL,
						NotificationType.SYSTEM,
						ManualNotificationService.ACTION_SYSTEM,
						null,
						null,
						null,
						List.of(),
						null),
				"Hello",
				"Body",
				null));
		when(broadcasts.findBySenderUser_IdAndIdempotencyKeyForUpdate(admin.getId(), "same-key")).thenReturn(Optional.of(existing));

		NotificationSendResponse result =
				service.sendSystem(admin, "same-key", new ManualNotificationRequest("Hello", "Body", null), audit);

		assertEquals(existing.getId(), result.sendId());
		assertEquals(4, result.recipientCount());
		assertEquals(0, result.createdCount());
		verify(notifications, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
		verify(auditLogs, never())
				.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void idempotencyKeyConflictWhenPayloadDiffers() {
		UserAccount admin = account(AccountRole.ADMIN);
		when(users.findIdsByAccountStatusAndAccountRoleInOrderByIdAsc(eq(AccountStatus.ACTIVE), any()))
				.thenReturn(List.of(UUID.randomUUID()));
		stubLocks(admin);
		NotificationBroadcast existing = new NotificationBroadcast();
		existing.setId(UUID.randomUUID());
		existing.setRecipientCount(1);
		existing.setRequestFingerprint(ManualNotificationService.fingerprint(
				new ManualNotificationService.SendPlan(
						BroadcastAudience.ALL,
						ManualNotificationService.SCOPE_ALL,
						NotificationType.SYSTEM,
						ManualNotificationService.ACTION_SYSTEM,
						null,
						null,
						null,
						List.of(),
						null),
				"Hello",
				"Body",
				null));
		when(broadcasts.findBySenderUser_IdAndIdempotencyKeyForUpdate(admin.getId(), "same-key")).thenReturn(Optional.of(existing));
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.sendSystem(admin, "same-key", new ManualNotificationRequest("Hello", "Other", null), audit));
		assertEquals(AcademicErrorCode.NOTIFICATION_SEND_CONFLICT, ex.getCode());
		assertEquals(HttpStatus.CONFLICT, ex.getStatus());
	}

	@Test
	void allCoursesDeduplicatesAcrossOwnedCoursesAndSkipsForeignCourse() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		LecturerProfile profile = new LecturerProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(lecturer);
		Course ownedA = course(UUID.randomUUID(), profile);
		Course ownedB = course(UUID.randomUUID(), profile);
		UUID shared = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID onlyB = UUID.fromString("22222222-2222-2222-2222-222222222222");
		when(lecturerProfiles.findByUserAccount_Id(lecturer.getId())).thenReturn(Optional.of(profile));
		when(courses.search(null, null, null, profile.getId())).thenReturn(List.of(ownedA, ownedB));
		when(enrollments.findFetchedByCourse_IdInAndEnrollmentStatus(any(), eq(EnrollmentStatus.ACTIVE)))
				.thenReturn(List.of(
						enrollment(shared, AccountStatus.ACTIVE),
						enrollment(shared, AccountStatus.ACTIVE),
						enrollment(onlyB, AccountStatus.ACTIVE),
						enrollment(UUID.randomUUID(), AccountStatus.INACTIVE)));
		stubNewBroadcast(lecturer);
		when(notifications.createNotification(any(), any(), any(), any(), any(), any(), any()))
				.thenAnswer(invocation -> response(invocation.getArgument(0)));
		when(deliveries.countByNotification_Id(any())).thenReturn(0L);
		when(userNotifications.countByBroadcast_Id(any())).thenReturn(2L);

		NotificationSendResponse result = service.sendAllCourses(
				lecturer, "all-courses", new ManualNotificationRequest("Class", "Please submit.", null), audit);

		assertEquals(ManualNotificationService.SCOPE_ALL_COURSES, result.scope());
		assertEquals(2, result.recipientCount());
		ArgumentCaptor<UUID> recipients = ArgumentCaptor.forClass(UUID.class);
		verify(notifications, times(2))
				.createNotification(
						recipients.capture(),
						eq(NotificationType.COURSE),
						eq("Class"),
						eq("Please submit."),
						isNull(),
						any(),
						any());
		assertEquals(List.of(shared, onlyB).stream().sorted().toList(), recipients.getAllValues());
		verify(auditLogs)
				.record(
						eq(lecturer),
						isNull(),
						isNull(),
						eq(ManualNotificationService.ACTION_LECTURER),
						eq("NOTIFICATION_BROADCAST"),
						eq(result.sendId()),
						isNull(),
						isNull(),
						any(),
						eq(AuditSource.API),
						any(),
						any(),
						any());
	}

	@Test
	void courseSendUsesOwnedCourseAndCourseType() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course(UUID.randomUUID(), null);
		when(authorization.requireCourse(lecturer, course.getId())).thenReturn(course);
		UUID student = UUID.fromString("33333333-3333-3333-3333-333333333333");
		when(enrollments.findFetchedByCourse_IdAndEnrollmentStatus(course.getId(), EnrollmentStatus.ACTIVE))
				.thenReturn(List.of(enrollment(student, AccountStatus.ACTIVE)));
		stubNewBroadcast(lecturer);
		when(notifications.createNotification(any(), any(), any(), any(), any(), any(), any()))
				.thenAnswer(invocation -> response(invocation.getArgument(0)));
		when(deliveries.countByNotification_Id(any())).thenReturn(0L);
		when(userNotifications.countByBroadcast_Id(any())).thenReturn(1L);

		NotificationSendResponse result = service.sendCourse(
				lecturer, course.getId(), "course-key", new ManualNotificationRequest("Hello", "Body", null), audit);

		assertEquals(ManualNotificationService.SCOPE_COURSE, result.scope());
		assertEquals(1, result.recipientCount());
		verify(notifications)
				.createNotification(
						eq(student), eq(NotificationType.COURSE), eq("Hello"), eq("Body"), isNull(), any(), any());
	}

	@Test
	void adminCannotUseLecturerCourseSend() {
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.sendCourse(
						account(AccountRole.ADMIN),
						UUID.randomUUID(),
						"k",
						new ManualNotificationRequest("Hello", "Body", null),
						audit));
		assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
		verify(authorization, never()).requireCourse(any(), any());
	}

	@Test
	void teamSendNotifiesActiveMembersAndHidesForeignTeam() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course(UUID.randomUUID(), null);
		Team team = new Team();
		team.setId(UUID.randomUUID());
		team.setCourse(course);
		when(teams.findFetchedById(team.getId())).thenReturn(Optional.of(team));
		when(authorization.requireCourse(lecturer, course.getId())).thenReturn(course);
		UUID active = UUID.fromString("44444444-4444-4444-4444-444444444444");
		when(teamMembers.findFetchedByTeam_Id(team.getId()))
				.thenReturn(List.of(
						member(active, EnrollmentStatus.ACTIVE, AccountStatus.ACTIVE),
						member(UUID.randomUUID(), EnrollmentStatus.WITHDRAWN, AccountStatus.ACTIVE),
						member(UUID.randomUUID(), EnrollmentStatus.ACTIVE, AccountStatus.INACTIVE)));
		stubNewBroadcast(lecturer);
		when(notifications.createNotification(any(), any(), any(), any(), any(), any(), any()))
				.thenAnswer(invocation -> response(invocation.getArgument(0)));
		when(deliveries.countByNotification_Id(any())).thenReturn(0L);
		when(userNotifications.countByBroadcast_Id(any())).thenReturn(1L);

		NotificationSendResponse result = service.sendTeam(
				lecturer, team.getId(), "team-key", new ManualNotificationRequest("Standup", "Tomorrow.", null), audit);

		assertEquals(ManualNotificationService.SCOPE_TEAM, result.scope());
		assertEquals(1, result.recipientCount());
		verify(notifications)
				.createNotification(
						eq(active), eq(NotificationType.TEAM), eq("Standup"), eq("Tomorrow."), isNull(), any(), any());

		UserAccount other = account(AccountRole.LECTURER);
		when(teams.findFetchedById(team.getId())).thenReturn(Optional.of(team));
		when(authorization.requireCourse(other, course.getId()))
				.thenThrow(new AcademicException(
						AcademicErrorCode.LECTURER_COURSE_FORBIDDEN,
						HttpStatus.FORBIDDEN,
						"Lecturer is not assigned to this course."));
		AcademicException hidden = assertThrows(
				AcademicException.class,
				() -> service.sendTeam(
						other, team.getId(), "other", new ManualNotificationRequest("Standup", "Tomorrow.", null), audit));
		assertEquals(AcademicErrorCode.TEAM_NOT_FOUND, hidden.getCode());
		assertEquals(HttpStatus.NOT_FOUND, hidden.getStatus());
	}

	@Test
	void studentSendRequiresEligibleMembershipAndUsesCourseType() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course(UUID.randomUUID(), null);
		when(authorization.requireCourse(lecturer, course.getId())).thenReturn(course);
		UUID studentUserId = UUID.fromString("55555555-5555-5555-5555-555555555555");
		StudentProfile profile = studentProfile(studentUserId, AccountStatus.ACTIVE);
		when(students.findById(profile.getId())).thenReturn(Optional.of(profile));
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		when(enrollments.findByStudentProfile_IdAndCourse_Id(profile.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		stubNewBroadcast(lecturer);
		when(notifications.createNotification(any(), any(), any(), any(), any(), any(), any()))
				.thenAnswer(invocation -> response(invocation.getArgument(0)));
		when(deliveries.countByNotification_Id(any())).thenReturn(0L);
		when(userNotifications.countByBroadcast_Id(any())).thenReturn(1L);

		NotificationSendResponse result = service.sendStudent(
				lecturer,
				course.getId(),
				profile.getId(),
				"student-key",
				new ManualNotificationRequest("Feedback", "See inbox.", null),
				audit);

		assertEquals(ManualNotificationService.SCOPE_STUDENT, result.scope());
		assertEquals(1, result.recipientCount());
		verify(notifications)
				.createNotification(
						eq(studentUserId),
						eq(NotificationType.COURSE),
						eq("Feedback"),
						eq("See inbox."),
						isNull(),
						any(),
						any());
	}

	@Test
	void studentOutsideCourseIsHidden() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course(UUID.randomUUID(), null);
		when(authorization.requireCourse(lecturer, course.getId())).thenReturn(course);
		UUID outsider = UUID.randomUUID();
		when(students.findById(outsider)).thenReturn(Optional.empty());
		when(students.findByUserAccount_Id(outsider)).thenReturn(Optional.empty());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.sendStudent(
						lecturer,
						course.getId(),
						outsider,
						"k",
						new ManualNotificationRequest("Hello", "Body", null),
						audit));
		assertEquals(AcademicErrorCode.ROSTER_STUDENT_NOT_FOUND, ex.getCode());
		assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
		verify(notifications, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
	}

	private void stubNewBroadcast(UserAccount sender) {
		stubLocks(sender);
		when(broadcasts.findBySenderUser_IdAndIdempotencyKeyForUpdate(eq(sender.getId()), any())).thenReturn(Optional.empty());
		when(broadcasts.saveAndFlush(any())).thenAnswer(invocation -> {
			NotificationBroadcast row = invocation.getArgument(0);
			if (row.getId() == null) {
				row.setId(UUID.randomUUID());
			}
			return row;
		});
		when(broadcasts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private void stubLocks(UserAccount sender) {
		when(users.findByIdForUpdate(any())).thenAnswer(invocation -> {
			UUID id = invocation.getArgument(0);
			if (sender.getId().equals(id)) {
				return Optional.of(sender);
			}
			UserAccount other = new UserAccount();
			other.setId(id);
			other.setAccountRole(AccountRole.STUDENT);
			other.setAccountStatus(AccountStatus.ACTIVE);
			return Optional.of(other);
		});
	}

	private static UserNotificationResponse response(UUID recipientId) {
		return new UserNotificationResponse(
				UUID.nameUUIDFromBytes(recipientId.toString().getBytes()),
				"SYSTEM",
				"Hello",
				"Body",
				null,
				null,
				LocalDateTime.of(2026, 1, 1, 0, 0));
	}

	private static UserAccount account(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setEmail(role.name().toLowerCase() + "@fpt.edu.vn");
		return account;
	}

	private static Course course(UUID id, LecturerProfile instructor) {
		Course course = new Course();
		course.setId(id);
		course.setInstructor(instructor);
		return course;
	}

	private static CourseEnrollment enrollment(UUID userId, AccountStatus status) {
		UserAccount user = new UserAccount();
		user.setId(userId);
		user.setAccountRole(AccountRole.STUDENT);
		user.setAccountStatus(status);
		StudentProfile profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(user);
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		return enrollment;
	}

	private static TeamMember member(UUID userId, EnrollmentStatus enrollmentStatus, AccountStatus accountStatus) {
		UserAccount user = new UserAccount();
		user.setId(userId);
		user.setAccountStatus(accountStatus);
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(user);
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setEnrollmentStatus(enrollmentStatus);
		TeamMember member = new TeamMember();
		member.setCourseEnrollment(enrollment);
		return member;
	}

	private static StudentProfile studentProfile(UUID userId, AccountStatus status) {
		UserAccount user = new UserAccount();
		user.setId(userId);
		user.setAccountStatus(status);
		StudentProfile profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(user);
		return profile;
	}
}
