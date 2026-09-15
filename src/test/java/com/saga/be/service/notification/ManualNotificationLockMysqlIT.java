package com.saga.be.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.saga.be.entity.enums.PushPlatform;
import com.saga.be.entity.notification.FirebaseInstallation;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "saga.verify.mysql", matches = "true")
@TestPropertySource(
		properties = {
			"spring.profiles.active=",
			"spring.datasource.url=${saga.verify.mysql.url}",
			"spring.datasource.username=${SAGA_VERIFY_MYSQL_USERNAME:${saga.verify.mysql.username:root}}",
			"spring.datasource.password=${SAGA_VERIFY_MYSQL_PASSWORD:${saga.verify.mysql.password:}}",
			"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
			"spring.jpa.hibernate.ddl-auto=none",
			"spring.jpa.open-in-view=false",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"spring.flyway.enabled=false",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ManualNotificationLockMysqlIT {

	private static final UUID STUDENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID STUDENT_ADMIN_ONLY_ID = UUID.fromString("11111111-1111-1111-1111-111111111112");
	private static final UUID LECTURER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID LECTURER_B_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	private static final int ROUNDS = 20;

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
	private DataSource dataSource;
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

	@BeforeEach
	void migrateClean() {
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.cleanDisabled(false)
				.load()
				.clean();
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
	}

	@Test
	void audienceColumnIsVarcharWithoutCheckOrEnum() throws Exception {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			ResultSet columns = statement.executeQuery(
					"SELECT COLUMN_TYPE, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS "
							+ "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification_broadcast' "
							+ "AND COLUMN_NAME = 'audience'");
			assertTrue(columns.next());
			String columnType = columns.getString("COLUMN_TYPE").toLowerCase(Locale.ROOT);
			assertEquals("varchar", columns.getString("DATA_TYPE").toLowerCase(Locale.ROOT));
			assertEquals(64L, columns.getLong("CHARACTER_MAXIMUM_LENGTH"));
			assertFalse(columnType.startsWith("enum"));
			assertFalse(columnType.contains("check"));

			ResultSet checks = statement.executeQuery(
					"SELECT cc.CONSTRAINT_NAME FROM information_schema.CHECK_CONSTRAINTS cc "
							+ "JOIN information_schema.TABLE_CONSTRAINTS tc "
							+ "ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA "
							+ "AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME "
							+ "WHERE tc.TABLE_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'notification_broadcast'");
			assertFalse(checks.next());

			ResultSet create = statement.executeQuery("SHOW CREATE TABLE notification_broadcast");
			assertTrue(create.next());
			String ddl = create.getString(2).toLowerCase(Locale.ROOT);
			assertTrue(ddl.contains("varchar(64)"));
			assertFalse(ddl.contains("enum("));
			assertFalse(ddl.contains("check "));
		}
	}

	@Test
	void senderFirstThenCrossRecipientLocksDeadlockOnMysql() throws Exception {
		persistAccount(STUDENT_ID, "deadlock-s@fpt.edu.vn", AccountRole.STUDENT);
		persistAccount(LECTURER_ID, "deadlock-l@fe.edu.vn", AccountRole.LECTURER);
		CountDownLatch bothHoldFirst = new CountDownLatch(2);
		CountDownLatch done = new CountDownLatch(2);
		AtomicInteger deadlocks = new AtomicInteger();
		AtomicReference<Throwable> other = new AtomicReference<>();
		Thread.ofVirtual()
				.start(() -> lockThenCross(
						STUDENT_ID, LECTURER_ID, bothHoldFirst, done, deadlocks, other));
		Thread.ofVirtual()
				.start(() -> lockThenCross(
						LECTURER_ID, STUDENT_ID, bothHoldFirst, done, deadlocks, other));
		assertTrue(done.await(20, TimeUnit.SECONDS));
		assertTrue(
				deadlocks.get() >= 1,
				() -> "old sender-first vs sorted-recipient cycle should deadlock; other=" + other.get());
	}

	@Test
	void adminSystemAndLecturerCourseDoNotDeadlock() throws Exception {
		Fixture fixture = seedAdminLecturerOverlap();
		for (int round = 0; round < ROUNDS; round++) {
			final int n = round;
			reset(userSse);
			long notificationsBefore = notifications.count();
			long deliveriesBefore = deliveries.count();
			AtomicReference<NotificationSendResponse> adminResult = new AtomicReference<>();
			AtomicReference<NotificationSendResponse> lecturerResult = new AtomicReference<>();
			runBoth(
					() -> adminResult.set(sender.sendSystem(
							fixture.admin(),
							"admin-" + n,
							new ManualNotificationRequest("Hello", "System " + n, null),
							audit)),
					() -> lecturerResult.set(sender.sendCourse(
							fixture.lecturer(),
							fixture.courseId(),
							"course-" + n,
							new ManualNotificationRequest("Course", "Work " + n, null),
							audit)));
			assertNotNull(adminResult.get());
			assertNotNull(lecturerResult.get());
			assertEquals(3, adminResult.get().recipientCount());
			assertEquals(3, adminResult.get().createdCount());
			assertEquals(1, lecturerResult.get().recipientCount());
			assertEquals(1, lecturerResult.get().createdCount());
			assertEquals(notificationsBefore + 4, notifications.count());
			assertEquals(deliveriesBefore + 2, deliveries.count());
			verify(userSse, times(2)).notifyCreated(eq(STUDENT_ID), any(), any());
			verify(userSse, times(1)).notifyCreated(eq(STUDENT_ADMIN_ONLY_ID), any(), any());
			verify(userSse, times(1)).notifyCreated(eq(LECTURER_ID), any(), any());
			verify(userSse, times(4)).notifyCreated(any(), any(), any());
		}
		assertEquals(ROUNDS * 2L, broadcasts.count());
		assertEquals(ROUNDS * 4L, notifications.count());
		assertEquals(ROUNDS * 2L, deliveries.count());
		assertEquals(ROUNDS * 2L, notifications.countByRecipientUser_Id(STUDENT_ID));
		assertEquals(ROUNDS, notifications.countByRecipientUser_Id(LECTURER_ID));
		assertEquals(0, notifications.countByRecipientUser_Id(ADMIN_ID));
	}

	@Test
	void twoLecturersWithOverlappingStudentsDoNotDeadlock() throws Exception {
		Fixture fixture = seedTwoLecturersOverlap();
		for (int round = 0; round < ROUNDS; round++) {
			final int n = round;
			reset(userSse);
			long notificationsBefore = notifications.count();
			AtomicReference<NotificationSendResponse> first = new AtomicReference<>();
			AtomicReference<NotificationSendResponse> second = new AtomicReference<>();
			runBoth(
					() -> first.set(sender.sendCourse(
							fixture.lecturer(),
							fixture.courseId(),
							"l1-" + n,
							new ManualNotificationRequest("A", "One " + n, null),
							audit)),
					() -> second.set(sender.sendCourse(
							fixture.lecturerB(),
							fixture.courseBId(),
							"l2-" + n,
							new ManualNotificationRequest("B", "Two " + n, null),
							audit)));
			assertEquals(1, first.get().createdCount());
			assertEquals(1, second.get().createdCount());
			assertEquals(notificationsBefore + 2, notifications.count());
			verify(userSse, times(2)).notifyCreated(eq(STUDENT_ID), any(), any());
		}
		assertEquals(ROUNDS * 2L, notifications.countByRecipientUser_Id(STUDENT_ID));
	}

	@Test
	void sameSenderSameKeyIsExactlyOneBroadcast() throws Exception {
		Fixture fixture = seedAdminLecturerOverlap();
		for (int round = 0; round < ROUNDS; round++) {
			reset(userSse);
			String key = "same-" + round;
			ManualNotificationRequest payload = new ManualNotificationRequest("Hello", "Body " + round, "/inbox");
			AtomicInteger createdSum = new AtomicInteger();
			AtomicReference<UUID> sendId = new AtomicReference<>();
			runBoth(
					() -> captureSend(sender.sendSystem(fixture.admin(), key, payload, audit), createdSum, sendId),
					() -> captureSend(sender.sendSystem(fixture.admin(), key, payload, audit), createdSum, sendId));
			assertEquals(3, createdSum.get());
			assertNotNull(sendId.get());
			assertTrue(broadcasts.findBySenderUser_IdAndIdempotencyKey(ADMIN_ID, key).isPresent());
			assertEquals(3, notifications.countByBroadcast_Id(sendId.get()));
			assertEquals(1, deliveries.countByNotification_Id(
					notifications
							.findByRecipientUser_IdAndEventKey(STUDENT_ID, "manual:" + sendId.get() + ":" + STUDENT_ID)
							.orElseThrow()
							.getId()));
			verify(userSse, times(1)).notifyCreated(eq(STUDENT_ID), any(), any());
			verify(userSse, times(1)).notifyCreated(eq(LECTURER_ID), any(), any());
			verify(userSse, times(1)).notifyCreated(eq(STUDENT_ADMIN_ONLY_ID), any(), any());
			verify(userSse, times(3)).notifyCreated(any(), any(), any());
		}
		assertEquals(ROUNDS, broadcasts.count());
		assertEquals(ROUNDS * 3L, notifications.count());
		assertEquals(ROUNDS, deliveries.count());
	}

	@Test
	void sameSenderSameKeyDifferentPayloadIsConflictWithoutSecondFanout() throws Exception {
		Fixture fixture = seedAdminLecturerOverlap();
		for (int round = 0; round < ROUNDS; round++) {
			final int n = round;
			reset(userSse);
			String key = "conflict-" + n;
			AtomicInteger successes = new AtomicInteger();
			AtomicInteger conflicts = new AtomicInteger();
			AtomicReference<NotificationSendResponse> winner = new AtomicReference<>();
			runBoth(
					() -> recordSend(
							() -> sender.sendSystem(
									fixture.admin(),
									key,
									new ManualNotificationRequest("Hello", "First " + n, null),
									audit),
							successes,
							conflicts,
							winner),
					() -> recordSend(
							() -> sender.sendSystem(
									fixture.admin(),
									key,
									new ManualNotificationRequest("Hello", "Second " + n, null),
									audit),
							successes,
							conflicts,
							winner));
			assertEquals(1, successes.get());
			assertEquals(1, conflicts.get());
			assertNotNull(winner.get());
			assertEquals(3, winner.get().createdCount());
			assertEquals(3, notifications.countByBroadcast_Id(winner.get().sendId()));
			verify(userSse, times(3)).notifyCreated(any(), any(), any());
		}
		assertEquals(ROUNDS, broadcasts.count());
		assertEquals(ROUNDS * 3L, notifications.count());
	}

	private void captureSend(
			NotificationSendResponse result, AtomicInteger createdSum, AtomicReference<UUID> sendId) {
		createdSum.addAndGet(result.createdCount());
		UUID previous = sendId.get();
		if (previous == null) {
			sendId.compareAndSet(null, result.sendId());
		} else {
			assertEquals(previous, result.sendId());
		}
	}

	private void recordSend(
			SendCall call,
			AtomicInteger successes,
			AtomicInteger conflicts,
			AtomicReference<NotificationSendResponse> winner)
			throws Exception {
		try {
			NotificationSendResponse result = call.run();
			successes.incrementAndGet();
			winner.compareAndSet(null, result);
		} catch (AcademicException ex) {
			if (ex.getCode() != AcademicErrorCode.NOTIFICATION_SEND_CONFLICT) {
				throw ex;
			}
			conflicts.incrementAndGet();
		}
	}

	private void runBoth(ThrowingRunnable left, ThrowingRunnable right) throws InterruptedException {
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicInteger unexpectedRollback = new AtomicInteger();
		AtomicInteger deadlocks = new AtomicInteger();
		Thread.ofVirtual().start(() -> runOne(left, start, done, failure, unexpectedRollback, deadlocks));
		Thread.ofVirtual().start(() -> runOne(right, start, done, failure, unexpectedRollback, deadlocks));
		start.countDown();
		assertTrue(done.await(45, TimeUnit.SECONDS), "concurrent sends did not finish");
		assertEquals(0, deadlocks.get(), () -> "deadlock: " + failure.get());
		assertEquals(0, unexpectedRollback.get(), () -> "rollback: " + failure.get());
		assertNull(failure.get());
	}

	private void runOne(
			ThrowingRunnable task,
			CountDownLatch start,
			CountDownLatch done,
			AtomicReference<Throwable> failure,
			AtomicInteger unexpectedRollback,
			AtomicInteger deadlocks) {
		try {
			if (!start.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("start latch timed out");
			}
			task.run();
		} catch (UnexpectedRollbackException ex) {
			unexpectedRollback.incrementAndGet();
			failure.compareAndSet(null, ex);
		} catch (Throwable ex) {
			if (isDeadlock(ex)) {
				deadlocks.incrementAndGet();
			}
			failure.compareAndSet(null, ex);
		} finally {
			done.countDown();
		}
	}

	private void lockThenCross(
			UUID first,
			UUID second,
			CountDownLatch bothHoldFirst,
			CountDownLatch done,
			AtomicInteger deadlocks,
			AtomicReference<Throwable> other) {
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (Statement timeout = connection.createStatement()) {
				timeout.execute("SET SESSION innodb_lock_wait_timeout = 5");
			}
			try (PreparedStatement lock = connection.prepareStatement(
					"SELECT id FROM user_account WHERE id = ? FOR UPDATE")) {
				lock.setString(1, first.toString());
				lock.executeQuery().close();
				bothHoldFirst.countDown();
				assertTrue(bothHoldFirst.await(10, TimeUnit.SECONDS));
				lock.setString(1, second.toString());
				lock.executeQuery().close();
			}
			connection.rollback();
		} catch (Throwable ex) {
			if (isDeadlock(ex)) {
				deadlocks.incrementAndGet();
			} else {
				other.compareAndSet(null, ex);
			}
		} finally {
			done.countDown();
		}
	}

	private static boolean isDeadlock(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof SQLException sql && sql.getErrorCode() == 1213) {
				return true;
			}
			String message = current.getMessage();
			if (message != null && message.toLowerCase(Locale.ROOT).contains("deadlock")) {
				return true;
			}
		}
		return false;
	}

	private Fixture seedAdminLecturerOverlap() {
		UserAccount admin = persistAccount(ADMIN_ID, "lock-admin@saga.local", AccountRole.ADMIN);
		UserAccount lecturer = persistAccount(LECTURER_ID, "lock-lec@fe.edu.vn", AccountRole.LECTURER);
		persistAccount(STUDENT_ID, "lock-stu@fpt.edu.vn", AccountRole.STUDENT);
		persistAccount(STUDENT_ADMIN_ONLY_ID, "lock-other@fpt.edu.vn", AccountRole.STUDENT);
		LecturerProfile profile = persistLecturer(lecturer);
		StudentProfile student = persistStudent(STUDENT_ID, "SELOCK01");
		persistStudent(STUDENT_ADMIN_ONLY_ID, "SELOCK02");
		Course course = persistCourse("OWN", profile);
		persistEnrollment(course, student, EnrollmentStatus.ACTIVE);
		persistInstallation(STUDENT_ID, "fid-overlap", "token-overlap");
		return new Fixture(admin, lecturer, null, course.getId(), null);
	}

	private Fixture seedTwoLecturersOverlap() {
		UserAccount lecturer = persistAccount(LECTURER_ID, "lock-l1@fe.edu.vn", AccountRole.LECTURER);
		UserAccount lecturerB = persistAccount(LECTURER_B_ID, "lock-l2@fe.edu.vn", AccountRole.LECTURER);
		persistAccount(STUDENT_ID, "lock-shared@fpt.edu.vn", AccountRole.STUDENT);
		LecturerProfile profile = persistLecturer(lecturer);
		LecturerProfile profileB = persistLecturer(lecturerB);
		StudentProfile student = persistStudent(STUDENT_ID, "SESHARE1");
		Course course = persistCourse("C1", profile);
		Course courseB = persistCourse("C2", profileB);
		persistEnrollment(course, student, EnrollmentStatus.ACTIVE);
		persistEnrollment(courseB, student, EnrollmentStatus.ACTIVE);
		return new Fixture(null, lecturer, lecturerB, course.getId(), courseB.getId());
	}

	private UserAccount persistAccount(UUID id, String email, AccountRole role) {
		return transactions().execute(status -> {
			UserAccount account = new UserAccount();
			account.setId(id);
			account.setEmail(email);
			account.setUsername("u" + id.toString().replace("-", ""));
			account.setFullName(email);
			account.setAccountRole(role);
			account.setAccountStatus(AccountStatus.ACTIVE);
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

	private StudentProfile persistStudent(UUID userId, String studentCode) {
		return transactions().execute(status -> {
			StudentProfile profile = new StudentProfile();
			profile.setUserAccount(users.findById(userId).orElseThrow());
			profile.setStudentCode(studentCode);
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

	private void persistInstallation(UUID ownerId, String fid, String token) {
		transactions().executeWithoutResult(status -> {
			FirebaseInstallation row = new FirebaseInstallation();
			row.setOwnerUser(users.findById(ownerId).orElseThrow());
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

	private record Fixture(
			UserAccount admin, UserAccount lecturer, UserAccount lecturerB, UUID courseId, UUID courseBId) {}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	@FunctionalInterface
	private interface SendCall {
		NotificationSendResponse run() throws Exception;
	}
}
