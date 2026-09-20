package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.project.ProjectTaskCommitLinksResponse;
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
import com.saga.be.entity.github.GitCommitBranch;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.service.projection.ProjectDataAuthorization;
import com.saga.be.service.projection.ProjectTaskCommitLinkReadService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves GET /task-commit-links query count does not grow with link cardinality (no per-task or
 * per-commit extra queries for membership).
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
class ProjectTaskCommitLinkQueryCountTest {

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
	private GitCommitBranchRepository branches;
	@Autowired
	private TaskRepository tasks;
	@Autowired
	private JiraIntegrationRepository jiraIntegrations;
	@Autowired
	private TaskGitCommitLinkRepository links;

	private TransactionTemplate tx;
	private UserAccount student;
	private Project project;
	private JiraIntegration jiraIntegration;
	private GitRepo repo;
	private ProjectTaskCommitLinkReadService readService;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		readService = new ProjectTaskCommitLinkReadService(
				new ProjectDataAuthorization(users, members, projects), repos, links, branches);
		tx.executeWithoutResult(status -> seedGraph());
	}

	@Test
	void listTaskCommitLinks_queryCountStableFor10And100() {
		seedLinks(10);
		long queriesFor10 = measure(10);
		seedLinks(90);
		long queriesFor100 = measure(100);
		assertThat(queriesFor10).as("exact query count for 10 links").isEqualTo(queriesFor100);
		assertThat(queriesFor10).as("no N+1 against link cardinality").isLessThanOrEqualTo(8L);
	}

	@Test
	void partialSyncCommit_unfilteredIncludesEmptyBranchNames_branchFilterExcludesAndKeepsResolvedAt() {
		LocalDateTime t1 = LocalDateTime.of(2026, 9, 1, 10, 0);
		String shaC = "c".repeat(40);
		UUID linkId = tx.execute(status -> {
			GitRepo managedRepo = repos.findById(repo.getId()).orElseThrow();
			managedRepo.setBranchMembershipSyncedAt(t1);
			repos.save(managedRepo);
			Task task = new Task();
			task.setProject(project);
			task.setJiraIntegration(jiraIntegration);
			task.setExternalKey("SAGA-PARTIAL");
			task.setExternalId("ext-partial");
			task.setTitle("Partial sync commit");
			task.setStatus(TaskStatus.TODO);
			task = tasks.save(task);
			GitCommit commitC = new GitCommit();
			commitC.setRepo(managedRepo);
			commitC.setShaHash(shaC);
			commitC.setMessage("feat: SAGA-PARTIAL");
			commitC.setHeadRef("develop");
			commitC.setCommittedAt(LocalDateTime.of(2026, 9, 15, 8, 0));
			commitC = commits.save(commitC);
			TaskGitCommitLink link = new TaskGitCommitLink();
			link.setTask(task);
			link.setGitCommit(commitC);
			link.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
			link = links.save(link);
			entityManager.flush();
			return link.getId();
		});

		ProjectTaskCommitLinksResponse unfiltered =
				tx.execute(status -> readService.list(student.getId(), project.getId(), null, null, 0, 200));
		assertThat(unfiltered.links())
				.anySatisfy(row -> {
					assertThat(row.sha()).isEqualTo(shaC);
					assertThat(row.headRef()).isEqualTo("develop");
					assertThat(row.branchNames()).isEmpty();
				});
		assertThat(unfiltered.filter().resolvedAt()).isNull();

		Page<UUID> branchPage = tx.execute(status -> links.findPageIdsByProjectAndRepoAndBranch(
				project.getId(), repo.getId(), "develop", PageRequest.of(0, 200)));
		assertThat(branchPage.getContent()).doesNotContain(linkId);

		ProjectTaskCommitLinksResponse filtered = tx.execute(status ->
				readService.list(student.getId(), project.getId(), repo.getId(), "develop", 0, 200));
		assertThat(filtered.links()).noneMatch(row -> shaC.equals(row.sha()));
		assertThat(filtered.filter().resolvedAt()).isEqualTo(t1);
	}

	private long measure(int expectedSize) {
		tx.executeWithoutResult(status -> entityManager.clear());
		Statistics stats = statistics();
		stats.clear();
		ProjectTaskCommitLinksResponse response =
				tx.execute(status -> readService.list(student.getId(), project.getId(), null, null, 0, 200));
		assertThat(response.links()).hasSize(expectedSize);
		response.links().forEach(row -> {
			assertThat(row.repoId()).isEqualTo(repo.getId());
			assertThat(row.branchNames()).isNotEmpty();
			assertThat(row.linkSource()).isNotBlank();
		});
		return stats.getPrepareStatementCount();
	}

	private void seedLinks(int count) {
		tx.executeWithoutResult(status -> {
			long existing = links.countDistinctLinkedCommitsByProject_Id(project.getId());
			GitRepo managedRepo = repos.findById(repo.getId()).orElseThrow();
			for (int i = 0; i < count; i++) {
				long n = existing + i + 1;
				Task task = new Task();
				task.setProject(project);
				task.setJiraIntegration(jiraIntegration);
				task.setExternalKey("SAGA-" + n);
				task.setExternalId("ext-" + n);
				task.setTitle("T" + n);
				task.setStatus(TaskStatus.TODO);
				task = tasks.save(task);
				GitCommit commit = new GitCommit();
				commit.setRepo(managedRepo);
				commit.setShaHash(String.format("%040d", n));
				commit.setMessage("work " + n);
				commit.setHeadRef("main");
				commit.setCommittedAt(LocalDateTime.now().minusMinutes(n));
				commit = commits.save(commit);
				GitCommitBranch membership = new GitCommitBranch();
				membership.setCommit(commit);
				membership.setBranchName("develop");
				branches.save(membership);
				TaskGitCommitLink link = new TaskGitCommitLink();
				link.setTask(task);
				link.setGitCommit(commit);
				link.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
				links.save(link);
			}
			entityManager.flush();
		});
	}

	private void seedGraph() {
		student = new UserAccount();
		student.setEmail("student-" + UUID.randomUUID() + "@fe.edu.vn");
		student.setFullName("Student");
		student.setAccountRole(AccountRole.STUDENT);
		student.setAccountStatus(AccountStatus.ACTIVE);
		student = users.save(student);

		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(student);
		profile.setStudentCode("SE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
		profile.setVersion(0L);
		profile = students.save(profile);

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
		enrollment.setStudentProfile(profile);
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
}
