package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.WorkSessionStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.service.evidence.TaskEvidenceService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real H2 + real {@link TaskRepository#lockActiveById} pessimistic write lock. Proves two concurrent
 * START requests for the same student+task cannot both insert an OPEN {@code task_work_session}.
 *
 * <p>{@code TaskEvidenceService} is {@code @Profile("!test")} so this constructs it manually. The
 * service's {@code @Transactional} is therefore not a Spring proxy; each START is wrapped in a
 * {@link TransactionTemplate} so the Task-row lock is held for the check-then-insert.
 *
 * <p>Placed in {@code com.saga.be.repository} so the nested {@code TxSlice} is not picked up by a
 * sibling plain {@code @SpringBootTest}.
 */
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
class TaskWorkSessionConcurrencyTest {

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
	static class TxSlice {}

	@Autowired
	private SemesterRepository semesters;
	@Autowired
	private AcademicClassRepository academicClasses;
	@Autowired
	private SubjectRepository subjects;
	@Autowired
	private CourseRepository courses;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserAccountRepository users;
	@Autowired
	private StudentProfileRepository students;
	@Autowired
	private CourseEnrollmentRepository enrollments;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private TeamByProjectRepository teamsByProject;
	@Autowired
	private TeamMemberRepository members;
	@Autowired
	private TaskRepository tasks;
	@Autowired
	private TaskWorkSessionRepository sessions;
	@Autowired
	private ContributionConfirmationRepository confirmations;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private TaskEvidenceService service;
	private UUID userId;
	private UUID taskId;

	@BeforeEach
	void setUp() {
		IntegrationProperties properties = new IntegrationProperties();
		properties.setReauthWindow(Duration.ofMinutes(10));
		service = new TaskEvidenceService(
				tasks,
				sessions,
				confirmations,
				teamsByProject,
				members,
				users,
				unusedPasswordEncoder(),
				properties,
				new ProjectRealtimePublisher(event -> {}));

		Course course = persistCourse();
		Project project = projects.save(project(course));
		UserAccount student = users.save(studentAccount());
		StudentProfile profile = students.save(studentProfile(student));
		CourseEnrollment enrollment = enrollments.save(activeEnrollment(profile, course));
		Team team = teams.save(team(course, project));
		members.save(teamMember(team, course, enrollment));
		Task task = tasks.save(task(project));

		userId = student.getId();
		taskId = task.getId();
	}

	@Test
	void concurrentStartsForSameStudentAndTaskLeaveExactlyOneOpenSession() throws Exception {
		int threads = 8;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		for (int i = 0; i < threads; i++) {
			Thread.ofVirtual().start(() -> {
				try {
					start.await();
					tx.executeWithoutResult(status -> service.start(userId, taskId));
				} catch (Throwable ex) {
					failure.compareAndSet(null, ex);
				} finally {
					done.countDown();
				}
			});
		}

		start.countDown();
		assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
		assertThat(failure.get()).isNull();

		List<TaskWorkSession> open = sessions.findByTask_IdAndUser_IdAndStatusOrderByStartedAtAsc(
				taskId, userId, WorkSessionStatus.OPEN);
		List<TaskWorkSession> all = sessions.findByTask_IdAndUser_IdOrderByStartedAtAsc(taskId, userId);
		assertThat(open).hasSize(1);
		assertThat(all).hasSize(1);
		assertThat(open.getFirst().getEndedAt()).isNull();
		assertThat(open.getFirst().getStatus()).isEqualTo(WorkSessionStatus.OPEN);
	}

	private static PasswordEncoder unusedPasswordEncoder() {
		return new PasswordEncoder() {
			@Override
			public String encode(CharSequence rawPassword) {
				return rawPassword.toString();
			}

			@Override
			public boolean matches(CharSequence rawPassword, String encodedPassword) {
				return true;
			}
		};
	}

	private Course persistCourse() {
		Semester semester = new Semester();
		semester.setCode("SEM-" + UUID.randomUUID());
		semester.setName("Test Semester");
		semester = semesters.save(semester);
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + (int) (Math.random() * 100000));
		academicClass.setName("Test Class");
		academicClass = academicClasses.save(academicClass);
		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 8));
		subject.setName("Software Project");
		subject = subjects.save(subject);
		Course course = new Course();
		course.setName("Course");
		course.setAcademicClass(academicClass);
		course.setSubject(subject);
		course.setSemester(semester);
		return courses.save(course);
	}

	private static Project project(Course course) {
		Project project = new Project();
		project.setName("Project");
		project.setCourse(course);
		return project;
	}

	private static UserAccount studentAccount() {
		UserAccount account = new UserAccount();
		account.setEmail("worker-" + UUID.randomUUID() + "@fpt.edu.vn");
		account.setFullName("Worker");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}

	private static StudentProfile studentProfile(UserAccount account) {
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(account);
		profile.setStudentCode("SE" + UUID.randomUUID().toString().substring(0, 8));
		return profile;
	}

	private static CourseEnrollment activeEnrollment(StudentProfile profile, Course course) {
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		enrollment.setEnrolledAt(LocalDateTime.now());
		return enrollment;
	}

	private static Team team(Course course, Project project) {
		Team team = new Team();
		team.setCourse(course);
		team.setProject(project);
		team.setTeamNo(1);
		team.setName("Team 1");
		return team;
	}

	private static TeamMember teamMember(Team team, Course course, CourseEnrollment enrollment) {
		TeamMember member = new TeamMember();
		member.setTeam(team);
		member.setCourse(course);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(RoleInTeam.MEMBER);
		return member;
	}

	private static Task task(Project project) {
		Task task = new Task();
		task.setProject(project);
		task.setExternalKey("SAGA-" + UUID.randomUUID().toString().substring(0, 6));
		task.setExternalId(UUID.randomUUID().toString());
		task.setTitle("Work session task");
		task.setStatus(TaskStatus.IN_PROGRESS);
		return task;
	}
}
