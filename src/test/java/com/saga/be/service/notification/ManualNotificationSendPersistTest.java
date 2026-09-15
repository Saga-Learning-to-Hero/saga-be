package com.saga.be.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.saga.be.config.FcmProperties;
import com.saga.be.dto.notification.ManualNotificationRequest;
import com.saga.be.dto.notification.NotificationSendResponse;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.PushPlatform;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.entity.notification.UserNotification;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.notification.NotificationCreatedAfterCommitListener;
import com.saga.be.realtime.UserSseHub;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.FirebaseInstallationRepository;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.NotificationBroadcastRepository;
import com.saga.be.repository.NotificationDeliveryRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.repository.UserNotificationRepository;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("tx-it")
@TestPropertySource(
		properties = {
			"spring.flyway.enabled=false",
			"spring.jpa.hibernate.ddl-auto=create-drop",
			"spring.jpa.open-in-view=false",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ManualNotificationSendPersistTest {

	@SpringBootConfiguration
	@EnableAutoConfiguration(
			excludeName = {
				"org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
				"org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
				"org.springframework.boot.session.autoconfigure.SessionAutoConfiguration",
				"org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
				"org.springframework.boot.neo4j.autoconfigure.Neo4jAutoConfiguration",
				"org.springframework.boot.data.neo4j.autoconfigure.DataNeo4jAutoConfiguration",
				"org.springframework.boot.data.neo4j.autoconfigure.DataNeo4jRepositoriesAutoConfiguration",
				"org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
				"org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration",
				"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"
			})
	@EntityScan(basePackages = "com.saga.be.entity")
	@EnableJpaRepositories(basePackages = "com.saga.be.repository")
	static class TxSlice {
		@Bean
		UserSseHub userSseHub() {
			return Mockito.mock(UserSseHub.class);
		}

		@Bean
		NotificationCreatedAfterCommitListener notificationCreatedAfterCommitListener(UserSseHub userSseHub) {
			return new NotificationCreatedAfterCommitListener(userSseHub);
		}

		@Bean
		AuditService auditService() {
			return Mockito.mock(AuditService.class);
		}

		@Bean
		LecturerCourseAuthorization lecturerCourseAuthorization(
				CourseRepository courses, LecturerProfileRepository lecturers) {
			return new LecturerCourseAuthorization(courses, lecturers);
		}

		@Bean
		NotificationService notificationService(
				UserNotificationRepository notifications,
				UserAccountRepository users,
				ApplicationEventPublisher events,
				FirebaseInstallationRepository installations,
				NotificationDeliveryRepository deliveries) {
			FcmProperties properties = new FcmProperties();
			properties.setEnabled(true);
			return new NotificationService(notifications, users, events, installations, deliveries, properties);
		}

		@Bean
		ManualNotificationService manualNotificationService(
				UserAccountRepository users,
				NotificationBroadcastRepository broadcasts,
				NotificationService notifications,
				NotificationDeliveryRepository deliveries,
				UserNotificationRepository userNotifications,
				LecturerCourseAuthorization authorization,
				LecturerProfileRepository lecturerProfiles,
				CourseRepository courses,
				CourseEnrollmentRepository enrollments,
				TeamRepository teams,
				TeamMemberRepository teamMembers,
				StudentProfileRepository students,
				AuditService auditLogs) {
			return new ManualNotificationService(
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
	}

	@Autowired
	private UserAccountRepository users;
	@Autowired
	private StudentProfileRepository studentProfiles;
	@Autowired
	private LecturerProfileRepository lecturerProfiles;
	@Autowired
	private SemesterRepository semesters;
	@Autowired
	private AcademicClassRepository academicClasses;
	@Autowired
	private SubjectRepository subjects;
	@Autowired
	private CourseRepository courses;
	@Autowired
	private CourseEnrollmentRepository enrollments;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private TeamMemberRepository teamMembers;
	@Autowired
	private FirebaseInstallationRepository installations;
	@Autowired
	private UserNotificationRepository notifications;
	@Autowired
	private NotificationDeliveryRepository deliveries;
	@Autowired
	private NotificationBroadcastRepository broadcasts;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private ManualNotificationService sender;
	@Autowired
	private UserSseHub userSse;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private final AuditRequest audit = new AuditRequest("req", "127.0.0.1", "junit");

	private TransactionTemplate transactions() {
		return new TransactionTemplate(transactionManager);
	}

	@Test
	void adminSystemFanoutIsIdempotentPlansFcmAndEmitsSseAfterCommit() {
		UserAccount admin = persistAccount("admin-send@saga.local", AccountRole.ADMIN, AccountStatus.ACTIVE);
		UserAccount student = persistAccount("stu-send@fpt.edu.vn", AccountRole.STUDENT, AccountStatus.ACTIVE);
		UserAccount inactive = persistAccount("inactive-send@fpt.edu.vn", AccountRole.STUDENT, AccountStatus.INACTIVE);
		UserAccount lecturer = persistAccount("lec-recv@fe.edu.vn", AccountRole.LECTURER, AccountStatus.ACTIVE);
		persistInstallation(student, "fid-admin", "token-admin");
		reset(userSse);

		NotificationSendResponse first = sender.sendSystem(
				admin, "admin-key", new ManualNotificationRequest("Hello", "System body", "/inbox"), audit);
		assertEquals(2, first.recipientCount());
		assertEquals(2, first.createdCount());
		assertEquals(2, notifications.countByBroadcast_Id(first.sendId()));
		assertEquals(1, deliveries.count());
		assertEquals(1, notifications.countByRecipientUser_Id(student.getId()));
		assertEquals(1, notifications.countByRecipientUser_Id(lecturer.getId()));
		assertEquals(0, notifications.countByRecipientUser_Id(inactive.getId()));
		assertEquals(0, notifications.countByRecipientUser_Id(admin.getId()));
		assertEquals(
				NotificationType.SYSTEM,
				notifications.findByRecipientUser_IdAndEventKey(
								student.getId(), "manual:" + first.sendId() + ":" + student.getId())
						.orElseThrow()
						.getNotificationType());
		verify(userSse, times(1)).notifyCreated(eq(student.getId()), any(), any());
		verify(userSse, times(1)).notifyCreated(eq(lecturer.getId()), any(), any());

		NotificationSendResponse replay = sender.sendSystem(
				admin, "admin-key", new ManualNotificationRequest("Hello", "System body", "/inbox"), audit);
		assertEquals(first.sendId(), replay.sendId());
		assertEquals(2, replay.recipientCount());
		assertEquals(0, replay.createdCount());
		assertEquals(2, notifications.count());
		assertEquals(1, broadcasts.count());
	}

	@Test
	void lecturerScopesHonorOwnershipMembershipAndTypes() {
		UserAccount lecturer = persistAccount("lec-own@fe.edu.vn", AccountRole.LECTURER, AccountStatus.ACTIVE);
		LecturerProfile profile = persistLecturer(lecturer);
		UserAccount otherLecturer = persistAccount("lec-other@fe.edu.vn", AccountRole.LECTURER, AccountStatus.ACTIVE);
		LecturerProfile otherProfile = persistLecturer(otherLecturer);
		Course owned = persistCourse("OWN", profile);
		Course foreign = persistCourse("FOR", otherProfile);
		UserAccount ada = persistAccount("ada-course@fpt.edu.vn", AccountRole.STUDENT, AccountStatus.ACTIVE);
		UserAccount bob = persistAccount("bob-course@fpt.edu.vn", AccountRole.STUDENT, AccountStatus.ACTIVE);
		UserAccount withdrawn = persistAccount("with-course@fpt.edu.vn", AccountRole.STUDENT, AccountStatus.ACTIVE);
		UserAccount outsider = persistAccount("out-course@fpt.edu.vn", AccountRole.STUDENT, AccountStatus.ACTIVE);
		StudentProfile adaProfile = persistStudent(ada);
		StudentProfile bobProfile = persistStudent(bob);
		StudentProfile withdrawnProfile = persistStudent(withdrawn);
		StudentProfile outsiderProfile = persistStudent(outsider);
		persistEnrollment(owned, adaProfile, EnrollmentStatus.ACTIVE);
		persistEnrollment(owned, bobProfile, EnrollmentStatus.ACTIVE);
		persistEnrollment(owned, withdrawnProfile, EnrollmentStatus.WITHDRAWN);
		persistEnrollment(foreign, outsiderProfile, EnrollmentStatus.ACTIVE);
		Course second = persistCourse("OWN2", profile);
		persistEnrollment(second, bobProfile, EnrollmentStatus.ACTIVE);
		Team team = persistTeam(owned, 1);
		persistMember(team, owned, enrollments.findByStudentProfile_IdAndCourse_Id(adaProfile.getId(), owned.getId()).orElseThrow());
		reset(userSse);

		NotificationSendResponse courseSend = sender.sendCourse(
				lecturer, owned.getId(), "course-key", new ManualNotificationRequest("Course", "Work due.", null), audit);
		assertEquals(2, courseSend.recipientCount());
		assertEquals(2, notifications.countByBroadcast_Id(courseSend.sendId()));
		assertEquals(
				NotificationType.COURSE,
				notifications.findByRecipientUser_IdAndEventKey(
								ada.getId(), "manual:" + courseSend.sendId() + ":" + ada.getId())
						.orElseThrow()
						.getNotificationType());

		AcademicException forbidden = assertThrows(
				AcademicException.class,
				() -> sender.sendCourse(
						lecturer,
						foreign.getId(),
						"foreign-course",
						new ManualNotificationRequest("No", "Nope", null),
						audit));
		assertEquals(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN, forbidden.getCode());

		NotificationSendResponse allCourses = sender.sendAllCourses(
				lecturer, "all-key", new ManualNotificationRequest("All", "Union.", null), audit);
		assertEquals(2, allCourses.recipientCount());

		NotificationSendResponse teamSend = sender.sendTeam(
				lecturer, team.getId(), "team-key", new ManualNotificationRequest("Team", "Meet.", null), audit);
		assertEquals(1, teamSend.recipientCount());
		assertEquals(
				NotificationType.TEAM,
				notifications.findByRecipientUser_IdAndEventKey(
								ada.getId(), "manual:" + teamSend.sendId() + ":" + ada.getId())
						.orElseThrow()
						.getNotificationType());

		NotificationSendResponse studentSend = sender.sendStudent(
				lecturer,
				owned.getId(),
				adaProfile.getId(),
				"stu-key",
				new ManualNotificationRequest("Ada", "Private.", null),
				audit);
		assertEquals(1, studentSend.recipientCount());
		assertEquals(1, studentSend.createdCount());

		AcademicException hidden = assertThrows(
				AcademicException.class,
				() -> sender.sendStudent(
						lecturer,
						owned.getId(),
						outsiderProfile.getId(),
						"hidden-key",
						new ManualNotificationRequest("No", "Nope", null),
						audit));
		assertEquals(AcademicErrorCode.ROSTER_STUDENT_NOT_FOUND, hidden.getCode());
		assertNotNull(userSse);
		verify(userSse, never()).notifyCreated(eq(outsider.getId()), any(), any());
	}

	private UserAccount persistAccount(String email, AccountRole role, AccountStatus status) {
		return transactions().execute(statusTx -> {
			UserAccount account = new UserAccount();
			account.setEmail(email);
			account.setUsername(email.substring(0, Math.min(20, email.indexOf('@'))) + UUID.randomUUID().toString().substring(0, 8));
			account.setFullName(email);
			account.setAccountRole(role);
			account.setAccountStatus(status);
			account.setPasswordHash("not-a-secret-in-api");
			LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
			account.setCreatedAt(now);
			account.setUpdatedAt(now);
			UserAccount saved = users.save(account);
			entityManager.flush();
			return saved;
		});
	}

	private LecturerProfile persistLecturer(UserAccount account) {
		return transactions().execute(status -> {
			LecturerProfile profile = new LecturerProfile();
			profile.setUserAccount(account);
			LecturerProfile saved = lecturerProfiles.save(profile);
			entityManager.flush();
			return saved;
		});
	}

	private StudentProfile persistStudent(UserAccount account) {
		return transactions().execute(status -> {
			StudentProfile profile = new StudentProfile();
			profile.setUserAccount(account);
			profile.setStudentCode("SE" + UUID.randomUUID().toString().substring(0, 8));
			profile.setVersion(0L);
			StudentProfile saved = studentProfiles.save(profile);
			entityManager.flush();
			return saved;
		});
	}

	private Course persistCourse(String name, LecturerProfile instructor) {
		return transactions().execute(status -> {
			Semester semester = new Semester();
			semester.setCode("FA" + UUID.randomUUID().toString().substring(0, 8));
			semester.setName("Fall");
			semester = semesters.save(semester);
			AcademicClass academicClass = new AcademicClass();
			academicClass.setSemester(semester);
			academicClass.setClassCode("SE" + UUID.randomUUID().toString().substring(0, 6));
			academicClass.setName("SE");
			academicClass = academicClasses.save(academicClass);
			Subject subject = new Subject();
			subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 8));
			subject.setName("Software Project");
			subject = subjects.save(subject);
			Course course = new Course();
			course.setName(name);
			course.setSubject(subject);
			course.setAcademicClass(academicClass);
			course.setSemester(semester);
			course.setInstructor(instructor);
			Course saved = courses.save(course);
			entityManager.flush();
			return saved;
		});
	}

	private void persistEnrollment(Course course, StudentProfile student, EnrollmentStatus enrollmentStatus) {
		transactions().executeWithoutResult(status -> {
			CourseEnrollment enrollment = new CourseEnrollment();
			enrollment.setCourse(course);
			enrollment.setStudentProfile(student);
			enrollment.setEnrollmentStatus(enrollmentStatus);
			enrollment.setEnrolledAt(LocalDateTime.of(2026, 1, 1, 0, 0));
			enrollments.save(enrollment);
			entityManager.flush();
		});
	}

	private Team persistTeam(Course course, int teamNo) {
		return transactions().execute(status -> {
			Team team = new Team();
			team.setCourse(course);
			team.setTeamNo(teamNo);
			team.setName("Team " + teamNo);
			Team saved = teams.save(team);
			entityManager.flush();
			return saved;
		});
	}

	private void persistMember(Team team, Course course, CourseEnrollment enrollment) {
		transactions().executeWithoutResult(status -> {
			TeamMember member = new TeamMember();
			member.setTeam(team);
			member.setCourse(course);
			member.setCourseEnrollment(enrollment);
			member.setRoleInTeam(RoleInTeam.MEMBER);
			teamMembers.save(member);
			entityManager.flush();
		});
	}

	private void persistInstallation(UserAccount owner, String fid, String token) {
		transactions().executeWithoutResult(status -> {
			FirebaseInstallation row = new FirebaseInstallation();
			row.setOwnerUser(owner);
			row.setFirebaseInstallationId(fid);
			row.setFcmToken(token);
			row.setPlatform(PushPlatform.WEB);
			row.setActive(true);
			row.setLastRegisteredAt(LocalDateTime.of(2026, 1, 1, 0, 0));
			row.setVersion(0L);
			installations.save(row);
			entityManager.flush();
		});
	}
}
