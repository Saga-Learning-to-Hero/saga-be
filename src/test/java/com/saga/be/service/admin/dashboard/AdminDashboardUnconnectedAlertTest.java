package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardMissingService;
import com.saga.be.dto.admin.dashboard.AdminDashboardUnconnectedTeamResponse;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.ActiveSemesterSetting;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.ActiveSemesterSettingRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
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
			"spring.jpa.properties.hibernate.generate_statistics=true",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional
class AdminDashboardUnconnectedAlertTest {

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
	private LecturerProfileRepository lecturers;
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
				semesters, activeSettings, courses, enrollments, teams, commits, tasks, links, CLOCK);
		subject = persistSubject();
		syllabus = persistSyllabus(subject);
	}

	@Test
	void mixedFixtureIsExactKpiComplementAndClassifiesMissingService() {
		Semester semester = persistSemester(
				LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 12, 15, 0, 0));
		Course course = persistCourse(semester, "SWP391", persistLecturer("Lan", "lan@fe.edu.vn"));
		Team noProject = persistTeam(course, 1, "No project", null);
		Project connected = persistProject(course, "B");
		Team connectedTeam = persistTeam(course, 2, "Connected", connected);
		persistJira(connected, IntegrationStatus.ACTIVE);
		persistRepo(connected, IntegrationStatus.ACTIVE);
		Project jiraMissing = persistProject(course, "C");
		Team jiraAlert = persistTeam(course, 3, "Need Jira", jiraMissing);
		persistRepo(jiraMissing, IntegrationStatus.ACTIVE);
		Project gitMissing = persistProject(course, "D");
		Team gitAlert = persistTeam(course, 4, "Need Git", gitMissing);
		persistJira(gitMissing, IntegrationStatus.ACTIVE);
		Project neither = persistProject(course, "E");
		Team bothAlert = persistTeam(course, 5, "Need both", neither);
		persistJira(neither, IntegrationStatus.CONNECTED);
		persistRepo(neither, IntegrationStatus.CONNECTED);
		Project multi = persistProject(course, "F");
		Team multiTeam = persistTeam(course, 6, "Multi repo", multi);
		persistJira(multi, IntegrationStatus.ACTIVE);
		persistRepo(multi, IntegrationStatus.REVOKED);
		persistRepo(multi, IntegrationStatus.ACTIVE);
		persistRepo(multi, IntegrationStatus.ACTIVE);

		AdminDashboardCachedPayload payload = compute(semester, CLOCK);
		assertThat(payload.kpis().totalTeams()).isEqualTo(6);
		assertThat(payload.kpis().connectedTeamsCount()).isEqualTo(2);
		assertThat(payload.unconnectedTeamsAlert()).hasSize(4);
		assertThat(payload.kpis().connectedTeamsCount() + payload.unconnectedTeamsAlert().size())
				.isEqualTo(payload.kpis().totalTeams());
		assertThat(payload.unconnectedTeamsAlert())
				.extracting(AdminDashboardUnconnectedTeamResponse::teamName)
				.containsExactly("No project", "Need Jira", "Need Git", "Need both");
		assertThat(alert(payload, noProject.getId()).missingService())
				.isEqualTo(AdminDashboardMissingService.PROJECT);
		assertThat(alert(payload, jiraAlert.getId()).missingService())
				.isEqualTo(AdminDashboardMissingService.JIRA);
		assertThat(alert(payload, gitAlert.getId()).missingService())
				.isEqualTo(AdminDashboardMissingService.GITHUB);
		assertThat(alert(payload, bothAlert.getId()).missingService())
				.isEqualTo(AdminDashboardMissingService.BOTH);
		assertThat(alert(payload, noProject.getId()).courseCode()).isEqualTo("SWP391");
		assertThat(alert(payload, noProject.getId()).lecturerName()).isEqualTo("Lan");
		assertThat(alert(payload, noProject.getId()).lecturerEmail()).isEqualTo("lan@fe.edu.vn");
		assertThat(payload.unconnectedTeamsAlert())
				.extracting(AdminDashboardUnconnectedTeamResponse::teamId)
				.doesNotContain(connectedTeam.getId(), multiTeam.getId());
	}

	@Test
	void excludesOtherSemesterAndSoftDeletedCourse_andIncludesTeamWithoutProject() {
		Semester selected = persistSemester(
				LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 12, 15, 0, 0));
		Semester other = persistSemester(
				LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 4, 30, 0, 0));
		Course live = persistCourse(selected, "LIVE", null);
		Team orphan = persistTeam(live, 1, "Orphan", null);
		Course deleted = persistCourse(selected, "DEL", null);
		persistTeam(deleted, 2, "Deleted course team", null);
		deleted.setDeletedAt(LocalDateTime.of(2026, 9, 2, 0, 0));
		courses.save(deleted);
		Course foreign = persistCourse(other, "OTH", null);
		persistTeam(foreign, 1, "Other semester", null);
		AdminDashboardCachedPayload payload = compute(selected, CLOCK);
		assertThat(payload.unconnectedTeamsAlert()).extracting(AdminDashboardUnconnectedTeamResponse::teamId)
				.containsExactly(orphan.getId());
		assertThat(payload.unconnectedTeamsAlert().getFirst().lecturerName()).isNull();
		assertThat(payload.unconnectedTeamsAlert().getFirst().lecturerEmail()).isNull();
	}

	@Test
	void daysSinceCreatedFollowsDashboardClock() {
		Semester semester = persistSemester(
				LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 12, 15, 0, 0));
		Course course = persistCourse(semester, "SWP", null);
		Team team = persistTeam(course, 1, "Aged", null);
		stampCreatedAt(team.getId(), LocalDateTime.of(2026, 9, 1, 8, 0));
		assertThat(compute(semester, Clock.fixed(Instant.parse("2026-09-01T04:00:00Z"), ZoneOffset.UTC))
						.unconnectedTeamsAlert()
						.getFirst()
						.daysSinceCreated())
				.isZero();
		assertThat(compute(semester, Clock.fixed(Instant.parse("2026-09-08T04:00:00Z"), ZoneOffset.UTC))
						.unconnectedTeamsAlert()
						.getFirst()
						.daysSinceCreated())
				.isEqualTo(7);
		assertThat(compute(semester, Clock.fixed(Instant.parse("2026-09-09T04:00:00Z"), ZoneOffset.UTC))
						.unconnectedTeamsAlert()
						.getFirst()
						.daysSinceCreated())
				.isEqualTo(8);
	}

	@Test
	void alertQueryCountDoesNotGrowWithDisconnectedTeamsOrRepos() {
		Semester semester = persistSemester(
				LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 12, 15, 0, 0));
		Course course = persistCourse(semester, "SWP", persistLecturer("A", "a@fe.edu.vn"));
		persistTeam(course, 1, "T1", null);
		Statistics stats = statistics();
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		compute(semester, CLOCK);
		long first = stats.getPrepareStatementCount();
		for (int i = 2; i <= 10; i++) {
			Project project = persistProject(course, "P" + i);
			persistTeam(course, i, "T" + i, project);
			persistRepo(project, IntegrationStatus.REVOKED);
			persistRepo(project, IntegrationStatus.ACTIVE);
			persistRepo(project, IntegrationStatus.ACTIVE);
		}
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		AdminDashboardCachedPayload many = compute(semester, CLOCK);
		assertThat(many.unconnectedTeamsAlert()).hasSize(10);
		assertThat(stats.getPrepareStatementCount()).isEqualTo(first);
	}

	private static AdminDashboardUnconnectedTeamResponse alert(
			AdminDashboardCachedPayload payload, UUID teamId) {
		return payload.unconnectedTeamsAlert().stream()
				.filter(row -> teamId.equals(row.teamId()))
				.findFirst()
				.orElseThrow();
	}

	private AdminDashboardCachedPayload compute(Semester semester, Clock clock) {
		entityManager.flush();
		return new AdminDashboardQueryService(
						semesters, activeSettings, courses, enrollments, teams, commits, tasks, links, clock)
				.compute(semester, clock.instant());
	}

	private Semester persistSemester(LocalDateTime start, LocalDateTime end) {
		Semester semester = new Semester();
		semester.setCode("U" + UUID.randomUUID().toString().substring(0, 8));
		semester.setName("Alert");
		semester.setStartDate(start);
		semester.setEndDate(end);
		semester = semesters.save(semester);
		ActiveSemesterSetting setting = activeSettings.findByIdFetchSemester((byte) 1).orElseGet(() -> {
			ActiveSemesterSetting created = new ActiveSemesterSetting();
			created.setSingletonId((byte) 1);
			created.setUpdatedAt(LocalDateTime.now());
			return created;
		});
		setting.setSemester(semester);
		setting.setUpdatedAt(LocalDateTime.now());
		activeSettings.save(setting);
		return semester;
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
		SubjectSyllabusVersion version = new SubjectSyllabusVersion();
		version.setSubject(subject);
		version.setVersionLabel("v1");
		version.setStatus(SyllabusStatus.PUBLISHED);
		entityManager.persist(version);
		return version;
	}

	private Course persistCourse(Semester semester, String code, LecturerProfile instructor) {
		Course course = new Course();
		course.setName(code);
		course.setCourseCode(code);
		course.setSubject(subject);
		course.setAcademicClass(persistClass(semester));
		course.setSemester(semester);
		course.setSyllabusVersion(syllabus);
		course.setInstructor(instructor);
		return courses.save(course);
	}

	private LecturerProfile persistLecturer(String name, String email) {
		UserAccount account = new UserAccount();
		account.setEmail(email);
		account.setFullName(name);
		account.setAccountRole(AccountRole.LECTURER);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account = users.save(account);
		LecturerProfile profile = new LecturerProfile();
		profile.setUserAccount(account);
		return lecturers.save(profile);
	}

	private Project persistProject(Course course, String name) {
		Project project = new Project();
		project.setCourse(course);
		project.setName(name);
		return projects.save(project);
	}

	private Team persistTeam(Course course, int no, String name, Project project) {
		Team team = new Team();
		team.setCourse(course);
		team.setTeamNo(no);
		team.setName(name);
		team.setProject(project);
		return teams.save(team);
	}

	private JiraIntegration persistJira(Project project, IntegrationStatus status) {
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setCloudId("cloud-" + UUID.randomUUID());
		integration.setJiraProjectId("10000");
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

	private void stampCreatedAt(UUID teamId, LocalDateTime createdAt) {
		entityManager.flush();
		entityManager
				.createNativeQuery("update team set created_at = :ts where id = :id")
				.setParameter("ts", createdAt)
				.setParameter("id", teamId.toString())
				.executeUpdate();
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}
}
