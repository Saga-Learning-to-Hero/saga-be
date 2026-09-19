package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardKpisResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardSelectedSemesterResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardSummaryResponse;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.ActiveSemesterSetting;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.ActiveSemesterSettingRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
			"spring.jpa.properties.hibernate.generate_statistics=true",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminDashboardQueryCountTest {

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
	private PlatformTransactionManager transactionManager;
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
	private TeamRepository teams;
	@Autowired
	private com.saga.be.repository.ProjectRepository projects;
	@Autowired
	private GitRepoRepository repos;
	@Autowired
	private GitCommitRepository commits;
	@Autowired
	private TaskRepository tasks;
	@Autowired
	private TaskGitCommitLinkRepository links;

	private TransactionTemplate tx;
	private AdminDashboardQueryService queries;
	private Semester semester;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
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
		semester = tx.execute(status -> seed());
	}

	@Test
	void coldComputeQueryCountIsIndependentOfTeamAndProjectCardinality() {
		Statistics stats = statistics();
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		AdminDashboardCachedPayload first = tx.execute(status -> queries.compute(semester, CLOCK.instant()));
		long cold = stats.getPrepareStatementCount();
		assertThat(first.kpis().totalTeams()).isEqualTo(3);
		assertThat(cold).as("cold compute grouped aggregates").isEqualTo(14L);

		tx.executeWithoutResult(status -> {
			Course course = courses.findAll().stream()
					.filter(row -> semester.getId().equals(row.getSemester().getId()))
					.findFirst()
					.orElseThrow();
			for (int i = 4; i <= 12; i++) {
				Project project = new Project();
				project.setCourse(course);
				project.setName("P" + i);
				project = projects.save(project);
				Team team = new Team();
				team.setCourse(course);
				team.setProject(project);
				team.setTeamNo(i);
				team.setName("T" + i);
				teams.save(team);
				GitRepo extra = new GitRepo();
				extra.setProject(project);
				extra.setProvider(GitProvider.GITHUB);
				extra.setRepositoryId(1000L + i);
				extra.setFullName("org/extra-" + i);
				extra.setConnectionStatus(IntegrationStatus.ACTIVE);
				extra.setConsecutiveFailures(0);
				repos.save(extra);
				GitRepo secondRepo = new GitRepo();
				secondRepo.setProject(project);
				secondRepo.setProvider(GitProvider.GITHUB);
				secondRepo.setRepositoryId(2000L + i);
				secondRepo.setFullName("org/extra2-" + i);
				secondRepo.setConnectionStatus(IntegrationStatus.ACTIVE);
				secondRepo.setConsecutiveFailures(0);
				repos.save(secondRepo);
			}
			entityManager.flush();
		});
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		AdminDashboardCachedPayload second = tx.execute(status -> queries.compute(semester, CLOCK.instant()));
		assertThat(second.kpis().totalTeams()).isEqualTo(12);
		assertThat(stats.getPrepareStatementCount())
				.as("same SQL count after more teams/projects/repos")
				.isEqualTo(cold);
	}

	@Test
	void coldComputeWithPreviousSemesterAddsOneStudentCount() {
		tx.executeWithoutResult(status -> {
			Semester previous = new Semester();
			previous.setCode("SP" + UUID.randomUUID().toString().substring(0, 8));
			previous.setName("Spring");
			previous.setStartDate(LocalDateTime.of(2026, 1, 1, 0, 0));
			previous.setEndDate(LocalDateTime.of(2026, 4, 30, 0, 0));
			semesters.save(previous);
			entityManager.flush();
		});
		Statistics stats = statistics();
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		AdminDashboardCachedPayload payload = tx.execute(status -> queries.compute(semester, CLOCK.instant()));
		assertThat(payload.kpis().comparedSemesterCode()).isNotNull();
		assertThat(stats.getPrepareStatementCount()).as("cold compute with previous semester").isEqualTo(15L);
	}

	@Test
	void oneWeekAndSixteenWeekSemestersUseTheSameColdQueryCount() {
		Semester sixteenWeek = tx.execute(status -> {
			Semester row = new Semester();
			row.setCode("SX" + UUID.randomUUID().toString().substring(0, 8));
			row.setName("Sixteen");
			row.setStartDate(LocalDateTime.of(2030, 1, 1, 0, 0));
			row.setEndDate(LocalDateTime.of(2030, 4, 22, 0, 0));
			return semesters.save(row);
		});
		Semester oneWeek = tx.execute(status -> {
			Semester row = new Semester();
			row.setCode("OW" + UUID.randomUUID().toString().substring(0, 8));
			row.setName("One");
			row.setStartDate(LocalDateTime.of(2031, 6, 1, 0, 0));
			row.setEndDate(LocalDateTime.of(2031, 6, 7, 0, 0));
			return semesters.save(row);
		});
		Statistics stats = statistics();
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		AdminDashboardCachedPayload sixteen = tx.execute(status -> queries.compute(sixteenWeek, CLOCK.instant()));
		long sixteenCount = stats.getPrepareStatementCount();
		assertThat(sixteen.selectedSemester().totalWeeks()).isEqualTo(16);
		assertThat(sixteen.weeklyTimeline()).hasSize(16);
		assertThat(sixteenCount).as("16-week far-future semester has a previous").isEqualTo(15L);

		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		AdminDashboardCachedPayload one = tx.execute(status -> queries.compute(oneWeek, CLOCK.instant()));
		assertThat(one.selectedSemester().totalWeeks()).isEqualTo(1);
		assertThat(one.weeklyTimeline()).hasSize(1);
		assertThat(stats.getPrepareStatementCount())
				.as("1-week semester uses the same SQL set as 16-week")
				.isEqualTo(sixteenCount);
	}

	@Test
	void warmCacheStillResolvesSemester() {
		AdminDashboardServiceCacheTest.MemoryCache cache = new AdminDashboardServiceCacheTest.MemoryCache();
		cache.values.put(semester.getId(), cached(semester));
		cache.ttls.put(semester.getId(), 600L);
		AdminDashboardService service = new AdminDashboardService(
				semesters,
				activeSettings,
				queries,
				cache,
				transactionManager,
				CLOCK,
				duration -> {},
				Duration.ZERO,
				Duration.ZERO,
				() -> "t");
		Statistics stats = statistics();
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		AdminDashboardSummaryResponse hit = service.summary(semester.getId(), false);
		assertThat(hit.kpis().totalTeams()).isZero();
		assertThat(stats.getPrepareStatementCount()).as("warm hit is semester resolution only").isEqualTo(1L);
	}

	private Semester seed() {
		Semester row = new Semester();
		row.setCode("FA" + UUID.randomUUID().toString().substring(0, 8));
		row.setName("Fall");
		row.setStartDate(LocalDateTime.of(2026, 9, 1, 0, 0));
		row.setEndDate(LocalDateTime.of(2026, 12, 15, 0, 0));
		row = semesters.save(row);

		ActiveSemesterSetting setting = new ActiveSemesterSetting();
		setting.setSingletonId((byte) 1);
		setting.setSemester(row);
		setting.setUpdatedAt(LocalDateTime.now());
		activeSettings.save(setting);

		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(row);
		academicClass.setClassCode("SE" + UUID.randomUUID().toString().substring(0, 6));
		academicClass.setName("SE1");
		academicClass = classes.save(academicClass);

		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 6));
		subject.setName("SWP");
		subject.setStatus(SubjectStatus.ACTIVE);
		subject = subjects.save(subject);

		SubjectSyllabusVersion syllabus = new SubjectSyllabusVersion();
		syllabus.setSubject(subject);
		syllabus.setVersionLabel("v1");
		syllabus.setStatus(SyllabusStatus.PUBLISHED);
		entityManager.persist(syllabus);

		Course course = new Course();
		course.setName("SWP");
		course.setSubject(subject);
		course.setAcademicClass(academicClass);
		course.setSemester(row);
		course.setSyllabusVersion(syllabus);
		course = courses.save(course);

		for (int i = 1; i <= 3; i++) {
			Project project = new Project();
			project.setCourse(course);
			project.setName("P" + i);
			project = projects.save(project);
			Team team = new Team();
			team.setCourse(course);
			team.setProject(project);
			team.setTeamNo(i);
			team.setName("T" + i);
			teams.save(team);
			GitRepo repo = new GitRepo();
			repo.setProject(project);
			repo.setProvider(GitProvider.GITHUB);
			repo.setRepositoryId((long) i);
			repo.setFullName("org/p" + i);
			repo.setConnectionStatus(IntegrationStatus.ACTIVE);
			repo.setConsecutiveFailures(0);
			repo = repos.save(repo);
			GitCommit commit = new GitCommit();
			commit.setRepo(repo);
			commit.setShaHash("sha" + i);
			commit.setMessage("m");
			commit.setParentCount(1);
			commits.save(commit);
			Task task = new Task();
			task.setProject(project);
			task.setExternalKey("SAGA-" + i);
			task.setExternalId(UUID.randomUUID().toString());
			task.setTitle("t");
			task.setStatus(TaskStatus.TODO);
			tasks.save(task);
		}
		entityManager.flush();
		return row;
	}

	private static AdminDashboardCachedPayload cached(Semester semester) {
		return new AdminDashboardCachedPayload(
				"warm",
				Instant.parse("2026-09-19T03:00:00Z"),
				new AdminDashboardSelectedSemesterResponse(
						semester.getId(),
						semester.getCode(),
						semester.getName(),
						LocalDate.of(2026, 9, 1),
						LocalDate.of(2026, 12, 15),
						16,
						3,
						true),
				List.of(),
				new AdminDashboardKpisResponse(0, null, null, 0, 0, 0, null, 0, 0, null),
				List.of());
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}
}
