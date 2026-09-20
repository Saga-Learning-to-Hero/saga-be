package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.dto.project.ProjectCommitPageResponse;
import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.dto.project.TaskEvidencePageResponse;
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
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
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
import com.saga.be.entity.project.TeamMember;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GET /tasks/{taskId}/commits two-step paging: query count is independent of page cardinality,
 * order is coalesce(committedAt, createdAt) DESC, id DESC, and known merges are excluded.
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
class TaskCommitListQueryCountTest {

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
	private UserAccountRepository users;
	@Autowired
	private StudentProfileRepository students;
	@Autowired
	private SemesterRepository semesters;
	@Autowired
	private AcademicClassRepository classes;
	@Autowired
	private SubjectRepository subjects;
	@Autowired
	private CourseRepository courses;
	@Autowired
	private CourseEnrollmentRepository enrollments;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private TeamMemberRepository members;
	@Autowired
	private GitRepoRepository repos;
	@Autowired
	private GitCommitRepository commits;
	@Autowired
	private TaskRepository tasks;
	@Autowired
	private JiraIntegrationRepository jiraIntegrations;
	@Autowired
	private TaskGitCommitLinkRepository links;
	@Autowired
	private TaskFileRepository files;
	@Autowired
	private TaskWebLinkRepository webLinks;

	private TransactionTemplate tx;
	private UserAccount student;
	private Project project;
	private GitRepo repo;
	private StudentProfile author;
	private JiraIntegration jiraIntegration;
	private Task task;
	private ProjectProjectionReadService readService;
	private ProjectTaskEvidenceReadService evidenceService;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		readService = new ProjectProjectionReadService(
				tasks, commits, links, sprintRepository(), authorization,
				new TaskHierarchyService(projects, tasks, transactionManager),
				org.mockito.Mockito.mock(com.saga.be.repository.JiraTaskFailoverItemRepository.class));
		evidenceService = new ProjectTaskEvidenceReadService(authorization, tasks, links, commits, files, webLinks);
		tx.executeWithoutResult(status -> seedGraph());
	}

	private com.saga.be.repository.SprintRepository sprintRepository() {
		return org.mockito.Mockito.mock(com.saga.be.repository.SprintRepository.class);
	}

	@Test
	void listTaskCommits_queryCountStableForEmptyOneFiftyAndHundred() {
		Measured empty = measure(0, 50);
		assertThat(empty.page.items()).isEmpty();
		assertThat(empty.page.total()).isZero();
		assertThat(empty.queries)
				.as("empty page: auth + task lookup + id page, count skipped, fetch skipped")
				.isEqualTo(4L);

		seedLinkedCommits(1);
		Measured one = measure(0, 50);
		assertThat(one.page.items()).hasSize(1);
		assertThat(one.page.total()).isEqualTo(1);
		assertThat(one.queries).as("1-row short page: auth + task + id page + fetch, count skipped").isEqualTo(5L);
		one.page.items().forEach(row -> {
			assertThat(row.repoId()).isEqualTo(repo.getId());
			assertThat(row.repositoryFullName()).isEqualTo("org/demo");
			assertThat(row.authorStudentId()).isEqualTo(author.getId());
		});

		seedLinkedCommits(49);
		Measured fiftyFull = measure(0, 50);
		assertThat(fiftyFull.page.items()).hasSize(50);
		assertThat(fiftyFull.page.total()).isEqualTo(50);
		assertThat(fiftyFull.queries)
				.as("full page of 50: auth + task + id page + count + fetch")
				.isEqualTo(6L);
		fiftyFull.page.items().forEach(row -> {
			assertThat(row.repoId()).isEqualTo(repo.getId());
			assertThat(row.repositoryFullName()).isEqualTo("org/demo");
			assertThat(row.authorStudentId()).isEqualTo(author.getId());
		});

		Measured fiftyShort = measure(0, 200);
		assertThat(fiftyShort.page.items()).hasSize(50);
		assertThat(fiftyShort.page.size()).isEqualTo(200);
		assertThat(fiftyShort.queries).as("50-row short page matches 1-row short page").isEqualTo(one.queries);

		seedLinkedCommits(50);
		Measured hundredShort = measure(0, 200);
		assertThat(hundredShort.page.items()).hasSize(100);
		assertThat(hundredShort.queries).as("100-row short page does not add per-row queries").isEqualTo(one.queries);
	}

	@Test
	void listTaskCommits_pagesEdgesAndDefaults() {
		seedOrderedLinkedHistory();
		ProjectCommitPageResponse first = list(0, 50);
		assertThat(first.items()).extracting(ProjectCommitResponse::sha).containsExactly("D", "C", "B", "A", "E");
		assertThat(first.page()).isZero();
		assertThat(first.size()).isEqualTo(50);
		assertThat(first.total()).isEqualTo(5);

		ProjectCommitPageResponse defaults = list(null, null);
		assertThat(defaults.page()).isZero();
		assertThat(defaults.size()).isEqualTo(50);
		assertThat(defaults.items()).extracting(ProjectCommitResponse::sha).containsExactly("D", "C", "B", "A", "E");

		ProjectCommitPageResponse second = list(1, 2);
		assertThat(second.items()).extracting(ProjectCommitResponse::sha).containsExactly("B", "A");
		assertThat(second.page()).isEqualTo(1);
		assertThat(second.size()).isEqualTo(2);
		assertThat(second.total()).isEqualTo(5);

		ProjectCommitPageResponse lastPartial = list(2, 2);
		assertThat(lastPartial.items()).extracting(ProjectCommitResponse::sha).containsExactly("E");
		assertThat(lastPartial.page()).isEqualTo(2);
		assertThat(lastPartial.size()).isEqualTo(2);
		assertThat(lastPartial.total()).isEqualTo(5);

		ProjectCommitPageResponse beyond = list(9, 50);
		assertThat(beyond.items()).isEmpty();
		assertThat(beyond.page()).isEqualTo(9);
		assertThat(beyond.size()).isEqualTo(50);
		assertThat(beyond.total()).isEqualTo(5);

		ProjectCommitPageResponse sizeOne = list(0, 1);
		assertThat(sizeOne.items()).extracting(ProjectCommitResponse::sha).containsExactly("D");
		assertThat(sizeOne.size()).isEqualTo(1);

		ProjectCommitPageResponse size200 = list(0, 200);
		assertThat(size200.items()).hasSize(5);
		assertThat(size200.size()).isEqualTo(200);
	}

	@Test
	void listTaskCommits_orderIsCoalesceThenIdDesc_andInFetchCannotReorder() {
		seedOrderedLinkedHistory();
		Page<UUID> idPage = tx.execute(
				status -> links.findPageIdsByProjectAndTask(project.getId(), task.getId(), PageRequest.of(0, 50)));
		assertThat(idPage.getContent())
				.extracting(this::shaOf)
				.containsExactly("D", "C", "B", "A", "E");

		List<GitCommit> fetched = tx.execute(status -> commits.findFetchedByIdIn(idPage.getContent()));
		List<String> fetchShas = fetched.stream().map(GitCommit::getShaHash).toList();
		assertThat(fetchShas).containsExactlyInAnyOrder("D", "C", "B", "A", "E");

		ProjectCommitPageResponse page = list(0, 50);
		assertThat(page.items()).extracting(ProjectCommitResponse::sha).containsExactly("D", "C", "B", "A", "E");
		assertThat(page.items())
				.extracting(ProjectCommitResponse::id)
				.containsExactlyElementsOf(idPage.getContent());
	}

	@Test
	void listTaskCommits_excludesKnownMergesKeepsUnknownRootNormalAndIgnoresMessage() {
		tx.executeWithoutResult(status -> {
			persistLinked("A", null, LocalDateTime.of(2026, 6, 5, 0, 0), null, "init");
			persistLinked("B", 0, LocalDateTime.of(2026, 6, 4, 0, 0), null, "root");
			persistLinked("C", 1, LocalDateTime.of(2026, 6, 3, 0, 0), null, "Merge branch main");
			persistLinked("D", 2, LocalDateTime.of(2026, 6, 2, 0, 0), null, "custom message");
			persistLinked("E", 3, LocalDateTime.of(2026, 6, 1, 0, 0), null, "octopus");
			GitCommit unlinked = persistCommit("U", 1, LocalDateTime.of(2026, 6, 6, 0, 0), null, "unlinked");
			assertThat(unlinked.getId()).isNotNull();
			Task other = new Task();
			other.setProject(project);
			other.setJiraIntegration(jiraIntegration);
			other.setTitle("Other");
			other.setStatus(TaskStatus.TODO);
			other.setExternalKey("SAGA-OTHER");
			other = tasks.save(other);
			GitCommit foreign = persistCommit("F", 1, LocalDateTime.of(2026, 6, 7, 0, 0), null, "other-task");
			link(other, foreign);
		});
		ProjectCommitPageResponse page = list(0, 50);
		assertThat(page.items()).extracting(ProjectCommitResponse::sha).containsExactly("A", "B", "C");
		assertThat(page.items()).extracting(ProjectCommitResponse::parentCount).containsExactly(null, 0, 1);
		assertThat(page.items()).extracting(ProjectCommitResponse::isMerge).containsExactly(null, false, false);
		assertThat(page.total()).isEqualTo(3);
		assertThat(page.items()).extracting(ProjectCommitResponse::message)
				.containsExactly("init", "root", "Merge branch main");
		assertThat(page.items()).extracting(ProjectCommitResponse::sha).doesNotContain("D", "E", "U", "F");
	}

	@Test
	void listTaskCommits_evidenceCommitTotalMatchesUnderSameV23Predicate() {
		tx.executeWithoutResult(status -> {
			persistLinked("A", null, LocalDateTime.of(2026, 6, 5, 0, 0), null, "init");
			persistLinked("B", 0, LocalDateTime.of(2026, 6, 4, 0, 0), null, "root");
			persistLinked("C", 1, LocalDateTime.of(2026, 6, 3, 0, 0), null, "Merge branch main");
			persistLinked("D", 2, LocalDateTime.of(2026, 6, 2, 0, 0), null, "custom message");
			persistLinked("E", 3, LocalDateTime.of(2026, 6, 1, 0, 0), null, "octopus");
		});
		ProjectCommitPageResponse page = list(0, 50);
		TaskEvidencePageResponse evidence = tx.execute(
				status -> (TaskEvidencePageResponse)
						evidenceService.list(student.getId(), project.getId(), task.getId(), "COMMIT", 0, 20));
		Page<UUID> evidenceIds = tx.execute(
				status -> links.findLinkedCommitIdsByTaskId(task.getId(), PageRequest.of(0, 20)));
		assertThat(page.total()).isEqualTo(3);
		assertThat(evidence.total()).isEqualTo(page.total());
		assertThat(evidenceIds.getTotalElements()).isEqualTo(page.total());
		assertThat(links.countByTask_Id(task.getId())).isEqualTo(5);
	}

	@Test
	void listTaskCommits_rejectsInvalidPaging() {
		assertThatThrownBy(() -> list(-1, 50))
				.isInstanceOf(AcademicException.class)
				.satisfies(ex -> assertInvalid((AcademicException) ex));
		assertThatThrownBy(() -> list(0, 0))
				.isInstanceOf(AcademicException.class)
				.satisfies(ex -> assertInvalid((AcademicException) ex));
		assertThatThrownBy(() -> list(0, 201))
				.isInstanceOf(AcademicException.class)
				.satisfies(ex -> assertInvalid((AcademicException) ex));
	}

	private static void assertInvalid(AcademicException ex) {
		assertThat(ex.getCode()).isEqualTo(AcademicErrorCode.REQUEST_INVALID);
		assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	private Measured measure(Integer page, Integer size) {
		tx.executeWithoutResult(status -> entityManager.clear());
		Statistics stats = statistics();
		stats.clear();
		ProjectCommitPageResponse response = list(page, size);
		return new Measured(response, stats.getPrepareStatementCount());
	}

	private ProjectCommitPageResponse list(Integer page, Integer size) {
		return tx.execute(
				status -> readService.listTaskCommits(student.getId(), project.getId(), task.getId(), page, size));
	}

	private String shaOf(UUID id) {
		return tx.execute(status -> commits.findById(id).orElseThrow().getShaHash());
	}

	private void seedLinkedCommits(int count) {
		tx.executeWithoutResult(status -> {
			long existing = links.countByTask_Id(task.getId());
			Task managedTask = tasks.findById(task.getId()).orElseThrow();
			GitRepo managedRepo = repos.findById(repo.getId()).orElseThrow();
			StudentProfile managedAuthor = students.findById(author.getId()).orElseThrow();
			for (int i = 0; i < count; i++) {
				GitCommit commit = new GitCommit();
				commit.setRepo(managedRepo);
				commit.setAuthorStudent(managedAuthor);
				commit.setShaHash(String.format("%040d", existing + i + 1));
				commit.setMessage("SAGA-" + (existing + i + 1) + " work");
				commit.setAuthorExternalId("gh-" + (existing + i));
				commit.setCommittedAt(LocalDateTime.of(2026, 5, 1, 0, 0).minusMinutes(existing + i));
				commit.setParentCount(1);
				commit = commits.save(commit);
				link(managedTask, commit);
			}
			entityManager.flush();
		});
	}

	private void seedOrderedLinkedHistory() {
		tx.executeWithoutResult(status -> {
			UUID idD = UUID.fromString("00000000-0000-4000-8000-00000000000d");
			UUID idC = UUID.fromString("00000000-0000-4000-8000-00000000000c");
			UUID idB = UUID.fromString("00000000-0000-4000-8000-00000000000b");
			UUID idA = UUID.fromString("00000000-0000-4000-8000-00000000000a");
			UUID idE = UUID.fromString("00000000-0000-4000-8000-00000000000e");
			persistLinked(idD, "D", 1, null, LocalDateTime.of(2026, 6, 4, 12, 0), "D");
			persistLinked(idC, "C", 1, LocalDateTime.of(2026, 6, 3, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0), "C");
			persistLinked(idB, "B", 1, LocalDateTime.of(2026, 6, 2, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0), "B");
			persistLinked(idA, "A", 1, LocalDateTime.of(2026, 6, 2, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0), "A");
			persistLinked(idE, "E", 1, null, LocalDateTime.of(2026, 6, 1, 0, 0), "E");
		});
	}

	private GitCommit persistLinked(
			String sha, Integer parentCount, LocalDateTime committedAt, LocalDateTime createdAt, String message) {
		return persistLinked(UUID.randomUUID(), sha, parentCount, committedAt, createdAt, message);
	}

	private GitCommit persistLinked(
			UUID id,
			String sha,
			Integer parentCount,
			LocalDateTime committedAt,
			LocalDateTime createdAt,
			String message) {
		GitCommit commit = persistCommit(id, sha, parentCount, committedAt, createdAt, message);
		link(tasks.findById(task.getId()).orElseThrow(), commit);
		return commit;
	}

	private GitCommit persistCommit(
			String sha, Integer parentCount, LocalDateTime committedAt, LocalDateTime createdAt, String message) {
		return persistCommit(UUID.randomUUID(), sha, parentCount, committedAt, createdAt, message);
	}

	private GitCommit persistCommit(
			UUID id,
			String sha,
			Integer parentCount,
			LocalDateTime committedAt,
			LocalDateTime createdAt,
			String message) {
		GitCommit commit = new GitCommit();
		commit.setId(id);
		commit.setRepo(repos.findById(repo.getId()).orElseThrow());
		commit.setAuthorStudent(students.findById(author.getId()).orElseThrow());
		commit.setShaHash(sha);
		commit.setMessage(message);
		commit.setParentCount(parentCount);
		commit.setCommittedAt(committedAt);
		commit = commits.save(commit);
		entityManager.flush();
		if (createdAt != null) {
			entityManager
					.createNativeQuery("update git_commit set created_at = :ts where id = :id")
					.setParameter("ts", createdAt)
					.setParameter("id", commit.getId().toString())
					.executeUpdate();
			entityManager.refresh(commit);
		}
		return commit;
	}

	private void link(Task owner, GitCommit commit) {
		TaskGitCommitLink row = new TaskGitCommitLink();
		row.setTask(owner);
		row.setGitCommit(commit);
		row.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
		links.save(row);
	}

	private void seedGraph() {
		student = new UserAccount();
		student.setEmail("student-" + UUID.randomUUID() + "@fe.edu.vn");
		student.setFullName("Student");
		student.setAccountRole(AccountRole.STUDENT);
		student.setAccountStatus(AccountStatus.ACTIVE);
		student = users.save(student);

		author = new StudentProfile();
		author.setUserAccount(student);
		author.setStudentCode("SE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
		author.setVersion(0L);
		author = students.save(author);

		String suffix = UUID.randomUUID().toString().substring(0, 8);
		Semester semester = new Semester();
		semester.setCode("FA" + suffix);
		semester.setName("Fall");
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
		course.setName("SWP");
		course.setSubject(subject);
		course.setAcademicClass(academicClass);
		course.setSemester(semester);
		course.setSyllabusVersion(syllabus);
		course = courses.save(course);

		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(author);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		enrollment.setEnrolledAt(LocalDateTime.now());
		enrollment = enrollments.save(enrollment);

		project = new Project();
		project.setName("Saga");
		project.setCourse(course);
		project.setCreatedBy(student);
		project = projects.save(project);

		jiraIntegration = jiraIntegrations.save(jiraFor(project));

		Team team = new Team();
		team.setCourse(course);
		team.setProject(project);
		team.setTeamNo(1);
		team.setName("Alpha");
		team = teams.save(team);

		TeamMember member = new TeamMember();
		member.setTeam(team);
		member.setCourse(course);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(RoleInTeam.MEMBER);
		members.save(member);

		repo = new GitRepo();
		repo.setProject(project);
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(System.nanoTime());
		repo.setOwnerLogin("org");
		repo.setName("demo");
		repo.setFullName("org/demo");
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		repo.setConsecutiveFailures(0);
		repo = repos.save(repo);

		task = new Task();
		task.setProject(project);
		task.setJiraIntegration(jiraIntegration);
		task.setTitle("Login");
		task.setStatus(TaskStatus.TODO);
		task.setExternalKey("SAGA-1");
		task = tasks.save(task);
	}

	private static JiraIntegration jiraFor(Project project) {
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setCloudId("cloud-" + UUID.randomUUID());
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setConsecutiveFailures(0);
		return integration;
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}

	private record Measured(ProjectCommitPageResponse page, long queries) {}
}
