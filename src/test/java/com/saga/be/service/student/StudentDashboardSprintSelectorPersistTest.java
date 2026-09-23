package com.saga.be.service.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.dto.student.dashboard.StudentDashboardResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real H2, real JPQL: {@code GET .../dashboard?sprintId=} (section-by-section per the
 * Student-Dashboard-Sprint-Filter gate). Reuses the same {@code @DataJpaTest} TxSlice pattern as
 * {@link StudentDashboardPersistTest}, kept in its own file since it needs its own fixture shape
 * (two Jira sources on one project, a second project entirely).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("tx-it")
@TestPropertySource(
		properties = {
			"spring.flyway.enabled=false",
			"spring.jpa.hibernate.ddl-auto=create-drop",
			"spring.jpa.open-in-view=false",
			"spring.jpa.properties.hibernate.generate_statistics=true",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StudentDashboardSprintSelectorPersistTest {

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
		StudentDashboardService studentDashboardService(
				CourseEnrollmentRepository enrollments,
				TeamMemberRepository members,
				JiraIntegrationRepository jiraIntegrations,
				GitRepoRepository repos,
				SprintRepository sprints,
				TaskRepository tasks,
				com.saga.be.repository.GitCommitRepository commits,
				com.saga.be.repository.TaskGitCommitLinkRepository commitLinks,
				com.saga.be.repository.PeerReviewRepository peerReviews,
				com.saga.be.repository.IdentityMapRepository identities) {
			return new StudentDashboardService(
					enrollments, members, jiraIntegrations, repos, sprints, tasks, commits, commitLinks,
					peerReviews, identities, Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC));
		}
	}

	@Autowired private StudentDashboardService service;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private EntityManager entityManager;
	@Autowired private UserAccountRepository users;
	@Autowired private StudentProfileRepository students;
	@Autowired private SemesterRepository semesters;
	@Autowired private AcademicClassRepository classes;
	@Autowired private SubjectRepository subjects;
	@Autowired private CourseRepository courses;
	@Autowired private CourseEnrollmentRepository enrollments;
	@Autowired private TeamRepository teams;
	@Autowired private TeamMemberRepository members;
	@Autowired private ProjectRepository projects;
	@Autowired private JiraIntegrationRepository jiraIntegrations;
	@Autowired private SprintRepository sprints;
	@Autowired private TaskRepository tasks;

	private TransactionTemplate tx;
	private Fixture fixture;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		fixture = seed();
	}

	@Test
	void noSprintIdIsByteIdenticalToTheTwoArgOverload() {
		StudentDashboardResponse viaThreeArgNull = tx.execute(status -> service.get(fixture.memberId, fixture.courseId, null));
		StudentDashboardResponse viaTwoArg = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(viaThreeArgNull).isEqualTo(viaTwoArg);
	}

	@Test
	void explicitCurrentSprintIdIsEquivalentToTheDefault() {
		StudentDashboardResponse byDefault = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		StudentDashboardResponse byExplicitCurrent =
				tx.execute(status -> service.get(fixture.memberId, fixture.courseId, fixture.activeSprintId));
		assertThat(byExplicitCurrent.currentSprint()).isEqualTo(byDefault.currentSprint());
		assertThat(byExplicitCurrent.myMetrics()).isEqualTo(byDefault.myMetrics());
		assertThat(byExplicitCurrent.myActiveTasks()).isEqualTo(byDefault.myActiveTasks());
		assertThat(byExplicitCurrent.actionableAlerts()).isEqualTo(byDefault.actionableAlerts());
	}

	@Test
	void explicitCompletedHistoricalSprintReturnsThatSprintsOwnStats() {
		StudentDashboardResponse historical =
				tx.execute(status -> service.get(fixture.memberId, fixture.courseId, fixture.closedSprintId));
		assertThat(historical.currentSprint().id()).isEqualTo(fixture.closedSprintId);
		assertThat(historical.currentSprint().state()).isEqualTo("closed");
		// The closed sprint has exactly 1 DONE task seeded below (see seed()).
		assertThat(historical.currentSprint().totalTasks()).isEqualTo(1);
		assertThat(historical.currentSprint().completedTasks()).isEqualTo(1);
		assertThat(historical.currentSprint().completionPercent()).isEqualTo(100.0);
	}

	@Test
	void sprintASelectionNeverContainsSprintBDataAndViceVersa() {
		StudentDashboardResponse viaActive =
				tx.execute(status -> service.get(fixture.memberId, fixture.courseId, fixture.activeSprintId));
		StudentDashboardResponse viaClosed =
				tx.execute(status -> service.get(fixture.memberId, fixture.courseId, fixture.closedSprintId));
		assertThat(viaActive.currentSprint().id()).isEqualTo(fixture.activeSprintId);
		assertThat(viaClosed.currentSprint().id()).isEqualTo(fixture.closedSprintId);
		// Both sprints have exactly 1 seeded task, but different status: active's is TODO (0
		// completed), closed's is DONE (1 completed) -- proves A's data never leaks into B's block.
		assertThat(viaActive.currentSprint().completedTasks()).isZero();
		assertThat(viaClosed.currentSprint().completedTasks()).isEqualTo(1);
		assertThat(viaActive.currentSprint().completionPercent()).isEqualTo(0.0);
		assertThat(viaClosed.currentSprint().completionPercent()).isEqualTo(100.0);
	}

	@Test
	void multiJiraDuplicateExternalSprintIdStaysIsolatedByLocalUuid() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			JiraIntegration sourceB = jiraIntegrations.save(jiraB(project));
			Sprint sprintB = sprints.save(sprintWithExternalId(sourceB, "same-external-id", "b-sprint"));
			tasks.save(task(project, sprintB, TaskStatus.TODO));
			tasks.save(task(project, sprintB, TaskStatus.TODO));
			entityManager.flush();
			fixture = fixture.withOtherSourceSprintId(sprintB.getId());
		});

		StudentDashboardResponse viaA = tx.execute(status -> service.get(fixture.memberId, fixture.courseId, fixture.activeSprintId));
		StudentDashboardResponse viaB =
				tx.execute(status -> service.get(fixture.memberId, fixture.courseId, fixture.otherSourceSprintId));

		assertThat(viaA.currentSprint().id()).isEqualTo(fixture.activeSprintId);
		assertThat(viaA.currentSprint().externalSprintId()).isEqualTo("same-external-id");
		assertThat(viaB.currentSprint().id()).isEqualTo(fixture.otherSourceSprintId);
		assertThat(viaB.currentSprint().externalSprintId()).isEqualTo("same-external-id");
		// Same externalSprintId string, but genuinely different local sprints with different task counts.
		assertThat(viaA.currentSprint().totalTasks()).isNotEqualTo(viaB.currentSprint().totalTasks());
		assertThat(viaB.currentSprint().totalTasks()).isEqualTo(2);
	}

	@Test
	void sprintFromAnotherProjectIsBlockedNonLeaking() {
		UUID foreignSprintId = tx.execute(status -> {
			Project otherProject = new Project();
			otherProject.setCourse(courses.findById(fixture.courseId).orElseThrow());
			otherProject.setName("OTHER-PROJECT");
			otherProject = projects.save(otherProject);
			JiraIntegration otherJira = jiraIntegrations.save(jira(otherProject, "cloud-foreign", "99999"));
			Sprint foreign = sprints.save(sprint(otherJira, "active", "foreign-1"));
			return foreign.getId();
		});

		assertThatThrownBy(() -> tx.execute(status -> service.get(fixture.memberId, fixture.courseId, foreignSprintId)))
				.isInstanceOf(AcademicException.class)
				.satisfies(ex -> assertThat(((AcademicException) ex).getCode()).isEqualTo(AcademicErrorCode.SPRINT_NOT_FOUND));
	}

	@Test
	void unknownSprintUuidGetsTheIdenticalSafeNotFoundAsAForeignProjectSprint() {
		UUID unknown = UUID.randomUUID();
		assertThatThrownBy(() -> tx.execute(status -> service.get(fixture.memberId, fixture.courseId, unknown)))
				.isInstanceOf(AcademicException.class)
				.satisfies(ex -> assertThat(((AcademicException) ex).getCode()).isEqualTo(AcademicErrorCode.SPRINT_NOT_FOUND));
	}

	@Test
	void historicalSprintSelectionNeverFabricatesGhostingOrMsrOrPeerReviewAlertsForThatSprint() {
		// Ghosting/MSR/peer-review-pending are current-actionable by contract: selecting an old
		// completed sprint must not invent a "ghosting at that sprint" snapshot -- alerts stay
		// computed against whatever is genuinely happening right now (empty here, since the fixture
		// has no qualifying anomaly/ghosting/peer-review condition), identical for every sprintId.
		StudentDashboardResponse byDefault = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		StudentDashboardResponse byHistorical =
				tx.execute(status -> service.get(fixture.memberId, fixture.courseId, fixture.closedSprintId));
		assertThat(byHistorical.actionableAlerts()).isEqualTo(byDefault.actionableAlerts());
		assertThat(byHistorical.myActiveTasks()).isEqualTo(byDefault.myActiveTasks());
	}

	@Test
	void queryCountWithExplicitSprintIdIsBoundedAtDefaultPlusOne() {
		Statistics stats = statistics();
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		long withoutSprintId = stats.getPrepareStatementCount();

		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		tx.execute(status -> service.get(fixture.memberId, fixture.courseId, fixture.activeSprintId));
		long withSprintId = stats.getPrepareStatementCount();

		assertThat(withSprintId).isEqualTo(withoutSprintId + 1);
		assertThat(withSprintId).isLessThanOrEqualTo(20L);
	}

	private Statistics statistics() {
		return entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
	}

	private Fixture seed() {
		return tx.execute(status -> {
			String suffix = UUID.randomUUID().toString().substring(0, 8);
			Semester semester = new Semester();
			semester.setCode("FA" + suffix);
			semester.setName("Fall 2026");
			semester = semesters.save(semester);

			AcademicClass academicClass = new AcademicClass();
			academicClass.setSemester(semester);
			academicClass.setClassCode("SE" + suffix);
			academicClass.setName("SE" + suffix);
			academicClass = classes.save(academicClass);

			Subject subject = new Subject();
			subject.setSubjectCode("SWP" + suffix);
			subject.setName("Software Project");
			subject.setStatus(SubjectStatus.ACTIVE);
			subject = subjects.save(subject);

			SubjectSyllabusVersion syllabus = new SubjectSyllabusVersion();
			syllabus.setSubject(subject);
			syllabus.setVersionLabel("1.0");
			syllabus.setStatus(SyllabusStatus.PUBLISHED);
			entityManager.persist(syllabus);

			Course course = new Course();
			course.setName("Main");
			course.setCourseCode(subject.getSubjectCode());
			course.setSubject(subject);
			course.setAcademicClass(academicClass);
			course.setSemester(semester);
			course.setSyllabusVersion(syllabus);
			course = courses.save(course);

			UserAccount memberAccount = account("member-" + suffix + "@gmail.com");
			StudentProfile memberProfile = profile(memberAccount, "SE2" + suffix);
			CourseEnrollment enrollment = enroll(memberProfile, course);

			Project project = new Project();
			project.setCourse(course);
			project.setName("SAGA");
			project = projects.save(project);

			Team team = new Team();
			team.setCourse(course);
			team.setProject(project);
			team.setTeamNo(1);
			team.setName("Alpha");
			team = teams.save(team);
			members.save(memberOf(team, course, enrollment, RoleInTeam.MEMBER));

			JiraIntegration jiraA = jiraIntegrations.save(jira(project, "cloud-a", "10000"));
			Sprint active = sprints.save(sprintWithExternalId(jiraA, "same-external-id", "active"));
			Sprint closed = sprints.save(sprintWithExternalId(jiraA, "closed-ext-id", "closed"));

			tasks.save(task(project, active, TaskStatus.TODO));
			Task closedDone = task(project, closed, TaskStatus.DONE);
			tasks.save(closedDone);

			entityManager.flush();
			return new Fixture(memberAccount.getId(), course.getId(), project.getId(), active.getId(), closed.getId(), null);
		});
	}

	private UserAccount account(String email) {
		UserAccount account = new UserAccount();
		account.setEmail(email);
		account.setFullName(email);
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return users.save(account);
	}

	private StudentProfile profile(UserAccount account, String code) {
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(account);
		profile.setStudentCode(code);
		profile.setVersion(0L);
		return students.save(profile);
	}

	private CourseEnrollment enroll(StudentProfile profile, Course course) {
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		enrollment.setEnrolledAt(LocalDateTime.of(2026, 9, 1, 0, 0));
		return enrollments.save(enrollment);
	}

	private static TeamMember memberOf(Team team, Course course, CourseEnrollment enrollment, RoleInTeam role) {
		TeamMember member = new TeamMember();
		member.setTeam(team);
		member.setCourse(course);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		return member;
	}

	private static JiraIntegration jira(Project project, String cloudId, String jiraProjectId) {
		JiraIntegration jira = new JiraIntegration();
		jira.setProject(project);
		jira.setName("board-" + cloudId);
		jira.setCloudId(cloudId);
		jira.setJiraProjectId(jiraProjectId);
		jira.setProjectKey("SAGA");
		jira.setConnectionStatus(IntegrationStatus.ACTIVE);
		jira.setConsecutiveFailures(0);
		jira.setVersion(0L);
		return jira;
	}

	private static JiraIntegration jiraB(Project project) {
		return jira(project, "cloud-b", "20000");
	}

	private static Sprint sprintWithExternalId(JiraIntegration jira, String externalSprintId, String state) {
		Sprint sprint = new Sprint();
		sprint.setJiraIntegration(jira);
		sprint.setExternalSprintId(externalSprintId);
		sprint.setName(state + " sprint");
		sprint.setState(state);
		sprint.setStartDate(LocalDateTime.of(2026, 9, 1, 0, 0));
		return sprint;
	}

	private static Sprint sprint(JiraIntegration jira, String state, String externalSprintId) {
		return sprintWithExternalId(jira, externalSprintId, state);
	}

	private static Task task(Project project, Sprint sprint, TaskStatus status) {
		Task task = new Task();
		task.setProject(project);
		task.setJiraIntegration(sprint.getJiraIntegration());
		task.setSprint(sprint);
		task.setStatus(status);
		task.setTitle(status.name());
		task.setExternalId(UUID.randomUUID().toString());
		return task;
	}

	private record Fixture(
			UUID memberId,
			UUID courseId,
			UUID projectId,
			UUID activeSprintId,
			UUID closedSprintId,
			UUID otherSourceSprintId) {
		Fixture withOtherSourceSprintId(UUID id) {
			return new Fixture(memberId, courseId, projectId, activeSprintId, closedSprintId, id);
		}
	}
}
