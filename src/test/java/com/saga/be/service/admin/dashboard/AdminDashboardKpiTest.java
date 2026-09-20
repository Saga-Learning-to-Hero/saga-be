package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardKpisResponse;
import com.saga.be.dto.admin.dashboard.SemesterPeriodStatus;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.ActiveSemesterSetting;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.ActiveSemesterSettingRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
class AdminDashboardKpiTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-19T04:00:00Z"), ZoneOffset.UTC);

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
	private EntityManager entityManager;
	@Autowired
	private SemesterRepository semesters;
	@Autowired
	private ActiveSemesterSettingRepository activeSettings;
	@Autowired
	private AcademicClassRepository classes;
	@Autowired
	private SubjectRepository subjects;
	@Autowired
	private CourseRepository courses;
	@Autowired
	private CourseEnrollmentRepository enrollments;
	@Autowired
	private UserAccountRepository users;
	@Autowired
	private StudentProfileRepository students;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private com.saga.be.repository.ProjectRepository projects;
	@Autowired
	private JiraIntegrationRepository jiras;
	@Autowired
	private GitRepoRepository repos;
	@Autowired
	private GitCommitRepository commits;
	@Autowired
	private TaskRepository tasks;
	@Autowired
	private TaskGitCommitLinkRepository links;

	private AdminDashboardQueryService queries;
	private Subject subject;
	private SubjectSyllabusVersion syllabus;

	@BeforeEach
	void setUp() {
		queries = new AdminDashboardQueryService(
				semesters,
				activeSettings,
				courses,
				enrollments,
				teams,
				commits,
				tasks,
				links,
				CLOCK);
		subject = persistSubject();
		syllabus = persistSyllabus(subject);
	}

	@Test
	void studentInTwoCoursesCountsOnce_disabledAccountStillCounts_nonActiveEnrollmentsExcluded() {
		Semester semester = persistSemester("FA26", "Fall", LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 12, 15, 0, 0));
		setActive(semester);
		Course courseA = persistCourse(semester, "A");
		Course courseB = persistCourse(semester, "B");
		StudentProfile twice = persistStudent("twice@fe.edu.vn", "SE0001", AccountStatus.ACTIVE);
		StudentProfile disabled = persistStudent("disabled@fe.edu.vn", "SE0002", AccountStatus.INACTIVE);
		StudentProfile withdrawn = persistStudent("out@fe.edu.vn", "SE0003", AccountStatus.ACTIVE);
		StudentProfile completed = persistStudent("done@fe.edu.vn", "SE0004", AccountStatus.ACTIVE);
		enroll(twice, courseA, EnrollmentStatus.ACTIVE);
		enroll(twice, courseB, EnrollmentStatus.ACTIVE);
		enroll(disabled, courseA, EnrollmentStatus.ACTIVE);
		enroll(withdrawn, courseA, EnrollmentStatus.WITHDRAWN);
		enroll(completed, courseA, EnrollmentStatus.COMPLETED);
		AdminDashboardKpisResponse kpis = compute(semester).kpis();
		assertThat(kpis.totalStudents()).isEqualTo(2);
		assertThat(kpis.studentsGrowthPercentage()).isNull();
		assertThat(kpis.comparedSemesterCode()).isNull();
		assertThat(kpis.totalCourses()).isEqualTo(2);
	}

	@Test
	void previousZeroStudentsLeavesGrowthNullButKeepsCode() {
		Semester previous = persistSemester("SP26", "Spring", LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 4, 30, 0, 0));
		Semester current = persistSemester("FA26", "Fall", LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 12, 15, 0, 0));
		setActive(current);
		Course course = persistCourse(current, "Now");
		enroll(persistStudent("a@fe.edu.vn", "SE1001", AccountStatus.ACTIVE), course, EnrollmentStatus.ACTIVE);
		persistCourse(previous, "Old");
		AdminDashboardKpisResponse kpis = compute(current).kpis();
		assertThat(kpis.totalStudents()).isEqualTo(1);
		assertThat(kpis.studentsGrowthPercentage()).isNull();
		assertThat(kpis.comparedSemesterCode()).isEqualTo("SP26");
	}

	@Test
	void growthUsesSameStudentDefinition() {
		Semester previous = persistSemester("SP26", "Spring", LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 4, 30, 0, 0));
		Semester current = persistSemester("FA26", "Fall", LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 12, 15, 0, 0));
		setActive(current);
		Course oldCourse = persistCourse(previous, "Old");
		Course nowCourse = persistCourse(current, "Now");
		enroll(persistStudent("p1@fe.edu.vn", "SE2001", AccountStatus.ACTIVE), oldCourse, EnrollmentStatus.ACTIVE);
		enroll(persistStudent("c1@fe.edu.vn", "SE2002", AccountStatus.ACTIVE), nowCourse, EnrollmentStatus.ACTIVE);
		enroll(persistStudent("c2@fe.edu.vn", "SE2003", AccountStatus.ACTIVE), nowCourse, EnrollmentStatus.ACTIVE);
		AdminDashboardKpisResponse kpis = compute(current).kpis();
		assertThat(kpis.studentsGrowthPercentage()).isEqualTo(100.0d);
		assertThat(kpis.comparedSemesterCode()).isEqualTo("SP26");
	}

	@Test
	void teamsWithoutProjectCount_andConnectionRequiresBothActive() {
		Semester semester = persistSemester("FA26", "Fall", LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 12, 15, 0, 0));
		setActive(semester);
		Course course = persistCourse(semester, "SWP");
		persistTeam(course, 1, "No project", null);

		Project projectOnly = persistProject(course, "P1");
		persistTeam(course, 2, "Project only", projectOnly);

		Project jiraOnly = persistProject(course, "P2");
		persistTeam(course, 3, "Jira only", jiraOnly);
		persistJira(jiraOnly, IntegrationStatus.ACTIVE);

		Project gitOnly = persistProject(course, "P3");
		persistTeam(course, 4, "Git only", gitOnly);
		persistRepo(gitOnly, IntegrationStatus.ACTIVE);

		Project both = persistProject(course, "P4");
		persistTeam(course, 5, "Both", both);
		persistJira(both, IntegrationStatus.ACTIVE);
		persistRepo(both, IntegrationStatus.ACTIVE);

		Project legacy = persistProject(course, "P5");
		persistTeam(course, 6, "Legacy", legacy);
		persistJira(legacy, IntegrationStatus.CONNECTED);
		persistRepo(legacy, IntegrationStatus.CONNECTED);

		AdminDashboardCachedPayload payload = compute(semester);
		AdminDashboardKpisResponse kpis = payload.kpis();
		assertThat(kpis.totalTeams()).isEqualTo(6);
		assertThat(kpis.connectedTeamsCount()).isEqualTo(1);
		assertThat(payload.unconnectedTeamsAlert()).hasSize(5);
		assertThat(kpis.connectedTeamsCount() + payload.unconnectedTeamsAlert().size()).isEqualTo(kpis.totalTeams());
		assertThat(kpis.connectedTeamsRate()).isEqualTo(100.0d / 6.0d);
		assertThat(kpis.connectedTeamsRate()).isLessThanOrEqualTo(100.0d);
	}

	@Test
	void connectedTeamsCountIsTeamsNotActiveRepos() {
		Semester semester = persistSemester("FA26", "Fall", LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 12, 15, 0, 0));
		setActive(semester);
		Course course = persistCourse(semester, "SWP");

		Project oneRepo = persistProject(course, "P1");
		persistTeam(course, 1, "One repo", oneRepo);
		persistJira(oneRepo, IntegrationStatus.ACTIVE);
		persistRepo(oneRepo, IntegrationStatus.ACTIVE);
		AdminDashboardKpisResponse one = compute(semester).kpis();
		assertThat(one.connectedTeamsCount()).isEqualTo(1);
		assertThat(one.totalTeams()).isEqualTo(1);
		assertThat(one.connectedTeamsRate()).isEqualTo(100.0d);

		persistRepo(oneRepo, IntegrationStatus.ACTIVE);
		persistRepo(oneRepo, IntegrationStatus.ACTIVE);
		AdminDashboardKpisResponse stillOne = compute(semester).kpis();
		assertThat(stillOne.connectedTeamsCount()).isEqualTo(1);
		assertThat(stillOne.totalTeams()).isEqualTo(1);
		assertThat(stillOne.connectedTeamsRate()).isEqualTo(100.0d);
		assertThat(stillOne.connectedTeamsRate()).isLessThanOrEqualTo(100.0d);

		Project multi = persistProject(course, "P2");
		persistTeam(course, 2, "Multi repo", multi);
		persistJira(multi, IntegrationStatus.ACTIVE);
		persistRepo(multi, IntegrationStatus.ACTIVE);
		persistRepo(multi, IntegrationStatus.ACTIVE);
		persistRepo(multi, IntegrationStatus.ACTIVE);
		AdminDashboardKpisResponse two = compute(semester).kpis();
		assertThat(two.totalTeams()).isEqualTo(2);
		assertThat(two.connectedTeamsCount()).isEqualTo(2);
		assertThat(two.connectedTeamsRate()).isEqualTo(100.0d);
		assertThat(two.connectedTeamsRate()).isLessThanOrEqualTo(100.0d);
	}

	@Test
	void rawCommitsIncludeMerges_traceabilityExcludesMergesAndSoftDeletedTasks() {
		Semester semester = persistSemester("FA26", "Fall", LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 12, 15, 0, 0));
		setActive(semester);
		Course course = persistCourse(semester, "SWP");
		Project project = persistProject(course, "P");
		persistTeam(course, 1, "T", project);
		GitRepo repo = persistRepo(project, IntegrationStatus.ACTIVE);
		GitCommit unknown = persistCommit(repo, "u", null);
		GitCommit root = persistCommit(repo, "r", 0);
		GitCommit normal = persistCommit(repo, "n", 1);
		GitCommit merge = persistCommit(repo, "m", 2);
		Task live = persistTask(project, "SAGA-1", null);
		Task deleted = persistTask(project, "SAGA-2", LocalDateTime.of(2026, 9, 10, 0, 0));
		link(live, unknown);
		link(live, merge);
		link(deleted, root);
		AdminDashboardKpisResponse kpis = compute(semester).kpis();
		assertThat(kpis.totalCommitsSynced()).isEqualTo(4);
		assertThat(kpis.totalJiraTasksSynced()).isEqualTo(1);
		assertThat(kpis.traceabilityRate()).isEqualTo(100.0d / 3.0d);
	}

	@Test
	void traceabilityZeroDenominatorIsNull() {
		Semester semester = persistSemester("FA26", "Fall", LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 12, 15, 0, 0));
		setActive(semester);
		Course course = persistCourse(semester, "SWP");
		Project project = persistProject(course, "P");
		GitRepo repo = persistRepo(project, IntegrationStatus.ACTIVE);
		persistCommit(repo, "merge-only", 2);
		AdminDashboardKpisResponse kpis = compute(semester).kpis();
		assertThat(kpis.totalCommitsSynced()).isEqualTo(1);
		assertThat(kpis.traceabilityRate()).isNull();
	}

	@Test
	void selectedSemesterWeekMetadataAndAvailablePeriodStatus() {
		Semester previous = persistSemester("SP26", "Spring", LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 4, 30, 0, 0));
		Semester current = persistSemester("FA26", "Fall", LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 12, 15, 0, 0));
		Semester future = persistSemester("SP27", "Next", LocalDateTime.of(2027, 1, 1, 0, 0), LocalDateTime.of(2027, 4, 30, 0, 0));
		setActive(current);
		AdminDashboardCachedPayload payload = compute(current);
		assertThat(payload.selectedSemester().totalWeeks()).isEqualTo(16);
		assertThat(payload.selectedSemester().currentWeekIndex()).isEqualTo(3);
		assertThat(payload.weeklyTimeline()).hasSize(16);
		assertThat(payload.weeklyTimeline().get(2).isCurrentWeek()).isTrue();
		assertThat(payload.weeklyTimeline().get(2).weekIndex()).isEqualTo(3);
		assertThat(payload.weeklyTimeline().stream().filter(point -> point.isCurrentWeek())).hasSize(1);
		assertThat(payload.selectedSemester().active()).isTrue();
		assertThat(payload.availableSemesters()).extracting(row -> row.code()).containsExactly("SP27", "FA26", "SP26");
		assertThat(payload.availableSemesters().stream().filter(row -> row.code().equals("FA26")).findFirst())
				.hasValueSatisfying(row -> {
					assertThat(row.active()).isTrue();
					assertThat(row.periodStatus()).isEqualTo(SemesterPeriodStatus.IN_PROGRESS);
				});
		assertThat(payload.availableSemesters().stream().filter(row -> row.code().equals("SP26")).findFirst())
				.hasValueSatisfying(row -> {
					assertThat(row.active()).isFalse();
					assertThat(row.periodStatus()).isEqualTo(SemesterPeriodStatus.COMPLETED);
				});
		assertThat(payload.availableSemesters().stream().filter(row -> row.code().equals("SP27")).findFirst())
				.hasValueSatisfying(row -> {
					assertThat(row.active()).isFalse();
					assertThat(row.periodStatus()).isEqualTo(SemesterPeriodStatus.UPCOMING);
				});
		assertThat(previous.getId()).isNotNull();
		assertThat(future.getId()).isNotNull();
	}

	@Test
	void resolveMissingActiveAndNullDates() {
		AdminDashboardService service = new AdminDashboardService(
				semesters,
				activeSettings,
				queries,
				new AdminDashboardServiceCacheTest.MemoryCache(),
				refresh -> AdminDashboardPulseQueryService.emptyRows(),
				new PlatformTransactionManagerAdapter(),
				CLOCK,
				duration -> {},
				Duration.ZERO,
				Duration.ZERO,
				() -> "t");
		assertThatThrownBy(() -> service.summary(null, false))
				.isInstanceOf(AcademicException.class)
				.satisfies(ex -> assertThat(((AcademicException) ex).getCode())
						.isEqualTo(AcademicErrorCode.SEMESTER_NOT_FOUND));
		Semester undated = persistSemester("XX", "X", null, null);
		assertThatThrownBy(() -> service.summary(undated.getId(), false))
				.isInstanceOf(AcademicException.class)
				.satisfies(ex -> {
					AcademicException academic = (AcademicException) ex;
					assertThat(academic.getCode()).isEqualTo(AcademicErrorCode.SEMESTER_DATE_RANGE_INVALID);
					assertThat(academic.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				});
	}

	private AdminDashboardCachedPayload compute(Semester semester) {
		entityManager.flush();
		return queries.compute(semester, Instant.parse("2026-09-19T04:00:00Z"));
	}

	private void setActive(Semester semester) {
		ActiveSemesterSetting setting = activeSettings.findByIdFetchSemester((byte) 1).orElseGet(() -> {
			ActiveSemesterSetting created = new ActiveSemesterSetting();
			created.setSingletonId((byte) 1);
			created.setUpdatedAt(LocalDateTime.now());
			return created;
		});
		setting.setSemester(semester);
		setting.setUpdatedAt(LocalDateTime.now());
		activeSettings.save(setting);
	}

	private Semester persistSemester(String code, String name, LocalDateTime start, LocalDateTime end) {
		Semester semester = new Semester();
		semester.setCode(code);
		semester.setName(name);
		semester.setStartDate(start);
		semester.setEndDate(end);
		return semesters.save(semester);
	}

	private AcademicClass persistClass(Semester semester) {
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + UUID.randomUUID().toString().substring(0, 6));
		academicClass.setName("Class");
		return classes.save(academicClass);
	}

	private Subject persistSubject() {
		Subject row = new Subject();
		row.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 6));
		row.setName("Software");
		row.setStatus(SubjectStatus.ACTIVE);
		return subjects.save(row);
	}

	private SubjectSyllabusVersion persistSyllabus(Subject subject) {
		SubjectSyllabusVersion syllabus = new SubjectSyllabusVersion();
		syllabus.setSubject(subject);
		syllabus.setVersionLabel("v1");
		syllabus.setStatus(SyllabusStatus.PUBLISHED);
		entityManager.persist(syllabus);
		return syllabus;
	}

	private Course persistCourse(Semester semester, String name) {
		Course course = new Course();
		course.setName(name);
		course.setCourseCode(name);
		course.setSubject(subject);
		course.setAcademicClass(persistClass(semester));
		course.setSemester(semester);
		course.setSyllabusVersion(syllabus);
		return courses.save(course);
	}

	private StudentProfile persistStudent(String email, String code, AccountStatus status) {
		UserAccount account = new UserAccount();
		account.setEmail(email);
		account.setFullName(email);
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(status);
		account = users.save(account);
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(account);
		profile.setStudentCode(code);
		profile.setVersion(0L);
		return students.save(profile);
	}

	private void enroll(StudentProfile student, Course course, EnrollmentStatus status) {
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(student);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(status);
		enrollment.setEnrolledAt(LocalDateTime.of(2026, 9, 2, 0, 0));
		enrollments.save(enrollment);
	}

	private Team persistTeam(Course course, int no, String name, Project project) {
		Team team = new Team();
		team.setCourse(course);
		team.setTeamNo(no);
		team.setName(name);
		team.setProject(project);
		return teams.save(team);
	}

	private Project persistProject(Course course, String name) {
		Project project = new Project();
		project.setCourse(course);
		project.setName(name);
		return projects.save(project);
	}

	private JiraIntegration persistJira(Project project, IntegrationStatus status) {
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setCloudId("cloud-" + UUID.randomUUID());
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setConnectionStatus(status);
		integration.setConsecutiveFailures(0);
		return jiras.save(integration);
	}

	private GitRepo persistRepo(Project project, IntegrationStatus status) {
		GitRepo repo = new GitRepo();
		repo.setProject(project);
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(System.nanoTime());
		repo.setFullName("org/" + UUID.randomUUID().toString().substring(0, 8));
		repo.setConnectionStatus(status);
		repo.setConsecutiveFailures(0);
		return repos.save(repo);
	}

	private GitCommit persistCommit(GitRepo repo, String sha, Integer parentCount) {
		GitCommit commit = new GitCommit();
		commit.setRepo(repo);
		commit.setShaHash(sha);
		commit.setMessage(sha);
		commit.setParentCount(parentCount);
		commit.setCommittedAt(LocalDateTime.of(2026, 9, 5, 0, 0));
		return commits.save(commit);
	}

	private Task persistTask(Project project, String key, LocalDateTime deletedAt) {
		JiraIntegration jira = jiras.findByProject_Id(project.getId())
				.orElseGet(() -> persistJira(project, IntegrationStatus.ACTIVE));
		Task task = new Task();
		task.setProject(project);
		task.setJiraIntegration(jira);
		task.setExternalKey(key);
		task.setExternalId(UUID.randomUUID().toString());
		task.setTitle(key);
		task.setStatus(TaskStatus.TODO);
		task.setDeletedAt(deletedAt);
		return tasks.save(task);
	}

	private void link(Task task, GitCommit commit) {
		TaskGitCommitLink row = new TaskGitCommitLink();
		row.setTask(task);
		row.setGitCommit(commit);
		row.setLinkSource(TraceLinkSource.MANUAL);
		links.save(row);
	}

	private static final class PlatformTransactionManagerAdapter
			implements org.springframework.transaction.PlatformTransactionManager {
		@Override
		public org.springframework.transaction.TransactionStatus getTransaction(
				org.springframework.transaction.TransactionDefinition definition) {
			return new org.springframework.transaction.support.SimpleTransactionStatus();
		}

		@Override
		public void commit(org.springframework.transaction.TransactionStatus status) {}

		@Override
		public void rollback(org.springframework.transaction.TransactionStatus status) {}
	}
}
