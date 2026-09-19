package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardWeeklyPointResponse;
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
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.traceability.TaskGitCommitLink;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
class AdminDashboardWeeklyTest {

	private static final Clock MID_SEMESTER = Clock.fixed(Instant.parse("2026-09-19T04:00:00Z"), ZoneOffset.UTC);
	private static final Clock AT_START = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
	private static final Clock AT_END_EXCLUSIVE = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
	private static final Clock PAST = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
	private static final Clock FUTURE = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);

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

	private Subject subject;
	private SubjectSyllabusVersion syllabus;

	@BeforeEach
	void setUp() {
		subject = persistSubject();
		syllabus = persistSyllabus(subject);
	}

	@Test
	void v23ParentCountsAndMultiLinkAndFallbackTimestamp() {
		Semester semester = persistTwoWeekSemester();
		Course course = persistCourse(semester, "SWP");
		Project project = persistProject(course, "P");
		GitRepo repo = persistRepo(project);
		persistCommit(repo, "null-parent", null, LocalDateTime.of(2026, 9, 2, 10, 0), null);
		persistCommit(repo, "root", 0, LocalDateTime.of(2026, 9, 2, 11, 0), null);
		GitCommit normal = persistCommit(repo, "normal", 1, LocalDateTime.of(2026, 9, 2, 12, 0), null);
		persistCommit(repo, "merge", 2, LocalDateTime.of(2026, 9, 2, 13, 0), null);
		persistCommit(repo, "outside", 1, LocalDateTime.of(2026, 8, 31, 23, 59), null);
		GitCommit createdOnly = persistCommit(repo, "created-only", 1, null, LocalDateTime.of(2026, 9, 3, 8, 0));
		GitCommit multi = persistCommit(repo, "multi-link", 1, LocalDateTime.of(2026, 9, 9, 0, 0), null);
		GitCommit unlinked = persistCommit(repo, "unlinked-w2", 1, LocalDateTime.of(2026, 9, 10, 0, 0), null);
		Task a = persistTask(project, "SAGA-1", TaskStatus.DONE, LocalDateTime.of(2026, 9, 2, 0, 0), null, null);
		Task b = persistTask(project, "SAGA-2", TaskStatus.TODO, null, null, null);
		link(a, multi);
		link(b, multi);
		link(a, normal);

		AdminDashboardCachedPayload payload = compute(semester, MID_SEMESTER);
		List<AdminDashboardWeeklyPointResponse> weeks = payload.weeklyTimeline();
		assertThat(weeks).hasSize(2);
		assertThat(weeks.get(0).commits()).isEqualTo(4);
		assertThat(weeks.get(0).traceabilityRate()).isEqualTo(AdminDashboardQueryService.ratioPercent(1, 4));
		assertThat(weeks.get(1).commits()).isEqualTo(2);
		assertThat(weeks.get(1).traceabilityRate()).isEqualTo(AdminDashboardQueryService.ratioPercent(1, 2));
		assertThat(payload.kpis().totalCommitsSynced()).isEqualTo(8);
		assertThat(payload.kpis().totalCommitsSynced())
				.isGreaterThan(weeks.get(0).commits() + weeks.get(1).commits());
		assertThat(createdOnly.getCommittedAt()).isNull();
		assertThat(unlinked.getId()).isNotNull();
	}

	@Test
	void knownMergeEvenIfLinkedIsExcludedFromWeeklyNumeratorAndDenominator() {
		Semester semester = persistTwoWeekSemester();
		Course course = persistCourse(semester, "SWP");
		Project project = persistProject(course, "P");
		GitRepo repo = persistRepo(project);
		GitCommit merge = persistCommit(repo, "linked-merge", 2, LocalDateTime.of(2026, 9, 2, 0, 0), null);
		link(persistTask(project, "SAGA-1", TaskStatus.DONE, LocalDateTime.of(2026, 9, 2, 0, 0), null, null), merge);
		AdminDashboardWeeklyPointResponse week1 = compute(semester, MID_SEMESTER).weeklyTimeline().getFirst();
		assertThat(week1.commits()).isZero();
		assertThat(week1.traceabilityRate()).isNull();
	}

	@Test
	void weekWithZeroActivityCommitsHasNullTraceability() {
		Semester semester = persistTwoWeekSemester();
		persistCourse(semester, "SWP");
		AdminDashboardCachedPayload payload = compute(semester, MID_SEMESTER);
		assertThat(payload.weeklyTimeline()).allSatisfy(point -> {
			assertThat(point.commits()).isZero();
			assertThat(point.traceabilityRate()).isNull();
		});
	}

	@Test
	void doneCompletionFallbacks_andNonDoneOrDeletedOrOutsideAreExcluded() {
		Semester semester = persistTwoWeekSemester();
		Course course = persistCourse(semester, "SWP");
		Project project = persistProject(course, "P");
		persistTask(project, "DONE-C", TaskStatus.DONE, LocalDateTime.of(2026, 9, 2, 12, 0), null, null);
		persistTask(
				project,
				"DONE-R",
				TaskStatus.DONE,
				null,
				LocalDateTime.of(2026, 9, 3, 12, 0),
				null);
		Task createdOnly = persistTask(project, "DONE-CR", TaskStatus.DONE, null, null, null);
		stampCreatedAt("task", createdOnly.getId(), LocalDateTime.of(2026, 9, 4, 12, 0));
		persistTask(
				project,
				"INPROG",
				TaskStatus.IN_PROGRESS,
				LocalDateTime.of(2026, 9, 2, 12, 0),
				LocalDateTime.of(2026, 9, 2, 12, 0),
				null);
		persistTask(
				project,
				"DEL",
				TaskStatus.DONE,
				LocalDateTime.of(2026, 9, 2, 12, 0),
				null,
				LocalDateTime.of(2026, 9, 5, 0, 0));
		persistTask(project, "OUT", TaskStatus.DONE, LocalDateTime.of(2026, 8, 20, 0, 0), null, null);
		persistTask(project, "REOPEN", TaskStatus.TODO, LocalDateTime.of(2026, 9, 2, 0, 0), null, null);

		List<AdminDashboardWeeklyPointResponse> weeks = compute(semester, MID_SEMESTER).weeklyTimeline();
		assertThat(weeks.get(0).tasksCompleted()).isEqualTo(3);
		assertThat(weeks.get(1).tasksCompleted()).isZero();
	}

	@Test
	void pastAndFutureSemestersHaveNoCurrentWeek_boundariesAgree() {
		Semester semester = persistTwoWeekSemester();
		assertNoCurrent(compute(semester, PAST));
		assertNoCurrent(compute(semester, FUTURE));
		AdminDashboardCachedPayload atStart = compute(semester, AT_START);
		assertThat(atStart.selectedSemester().currentWeekIndex()).isEqualTo(1);
		assertThat(atStart.weeklyTimeline().getFirst().isCurrentWeek()).isTrue();
		assertThat(atStart.weeklyTimeline().get(1).isCurrentWeek()).isFalse();
		AdminDashboardCachedPayload atEnd = compute(semester, AT_END_EXCLUSIVE);
		assertNoCurrent(atEnd);
	}

	@Test
	void oneWeekSemester() {
		Semester semester = persistSemester(
				LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 7, 0, 0));
		AdminDashboardCachedPayload payload = compute(semester, AT_START);
		assertThat(payload.weeklyTimeline()).hasSize(1);
		assertThat(payload.weeklyTimeline().getFirst().weekLabel()).isEqualTo("Tuần 01");
		assertThat(payload.weeklyTimeline().getFirst().startDate()).isEqualTo(LocalDate.of(2026, 9, 1));
		assertThat(payload.weeklyTimeline().getFirst().endDate()).isEqualTo(LocalDate.of(2026, 9, 7));
		assertThat(payload.weeklyTimeline().getFirst().isCurrentWeek()).isTrue();
		assertThat(payload.selectedSemester().currentWeekIndex()).isEqualTo(1);
	}

	@Test
	void eightDaySemesterTruncatesSecondWeek() {
		Semester semester = persistSemester(
				LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 8, 0, 0));
		AdminDashboardCachedPayload payload = compute(semester, MID_SEMESTER);
		assertThat(payload.weeklyTimeline()).hasSize(2);
		assertThat(payload.weeklyTimeline().get(0).startDate()).isEqualTo(LocalDate.of(2026, 9, 1));
		assertThat(payload.weeklyTimeline().get(0).endDate()).isEqualTo(LocalDate.of(2026, 9, 7));
		assertThat(payload.weeklyTimeline().get(1).startDate()).isEqualTo(LocalDate.of(2026, 9, 8));
		assertThat(payload.weeklyTimeline().get(1).endDate()).isEqualTo(LocalDate.of(2026, 9, 8));
		assertNoCurrent(payload);
	}

	@Test
	void moreThanFifteenWeeksAreAllReturned() {
		Semester semester = persistSemester(
				LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2027, 1, 18, 0, 0));
		AdminDashboardCachedPayload payload = compute(semester, MID_SEMESTER);
		assertThat(payload.selectedSemester().totalWeeks()).isEqualTo(20);
		assertThat(payload.weeklyTimeline()).hasSize(20);
		assertThat(payload.weeklyTimeline().getLast().weekLabel()).isEqualTo("Tuần 20");
	}

	private static void assertNoCurrent(AdminDashboardCachedPayload payload) {
		assertThat(payload.selectedSemester().currentWeekIndex()).isNull();
		assertThat(payload.weeklyTimeline()).allSatisfy(point -> assertThat(point.isCurrentWeek()).isFalse());
	}

	private AdminDashboardCachedPayload compute(Semester semester, Clock clock) {
		entityManager.flush();
		return new AdminDashboardQueryService(
						semesters,
						activeSettings,
						courses,
						enrollments,
						teams,
						commits,
						tasks,
						links,
						clock)
				.compute(semester, clock.instant());
	}

	private Semester persistTwoWeekSemester() {
		return persistSemester(LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 14, 0, 0));
	}

	private Semester persistSemester(LocalDateTime start, LocalDateTime end) {
		Semester semester = new Semester();
		semester.setCode("W" + UUID.randomUUID().toString().substring(0, 8));
		semester.setName("Weekly");
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

	private Project persistProject(Course course, String name) {
		Project project = new Project();
		project.setCourse(course);
		project.setName(name);
		return projects.save(project);
	}

	private GitRepo persistRepo(Project project) {
		GitRepo repo = new GitRepo();
		repo.setProject(project);
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(System.nanoTime());
		repo.setFullName("org/" + UUID.randomUUID().toString().substring(0, 8));
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		repo.setConsecutiveFailures(0);
		return repos.save(repo);
	}

	private GitCommit persistCommit(
			GitRepo repo, String sha, Integer parentCount, LocalDateTime committedAt, LocalDateTime createdAt) {
		GitCommit commit = new GitCommit();
		commit.setRepo(repo);
		commit.setShaHash(sha);
		commit.setMessage(sha);
		commit.setParentCount(parentCount);
		commit.setCommittedAt(committedAt);
		commit = commits.save(commit);
		entityManager.flush();
		if (createdAt != null) {
			stampCreatedAt("git_commit", commit.getId(), createdAt);
			entityManager.refresh(commit);
		}
		return commit;
	}

	private Task persistTask(
			Project project,
			String key,
			TaskStatus status,
			LocalDateTime completedAt,
			LocalDateTime resolvedAt,
			LocalDateTime deletedAt) {
		Task task = new Task();
		task.setProject(project);
		task.setExternalKey(key);
		task.setExternalId(UUID.randomUUID().toString());
		task.setTitle(key);
		task.setStatus(status);
		task.setCompletedAt(completedAt);
		task.setResolvedAt(resolvedAt);
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

	private void stampCreatedAt(String table, UUID id, LocalDateTime createdAt) {
		entityManager
				.createNativeQuery("update " + table + " set created_at = :ts where id = :id")
				.setParameter("ts", createdAt)
				.setParameter("id", id.toString())
				.executeUpdate();
	}
}
