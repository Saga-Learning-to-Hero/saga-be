package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.service.projection.JiraTaskProjectionService;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Real H2 + create-drop proving V24 multi-Jira Task provenance: source-scoped uniqueness, revoke
 * retention, fail-closed singular project lookup, and project progress counts that ignore ACTIVE.
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
class MultiJiraTaskProvenancePersistTest {

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
	private JiraIntegrationRepository jiraIntegrations;
	@Autowired
	private TaskRepository tasks;
	@Autowired
	private SprintRepository sprints;
	@Autowired
	private GitRepoRepository repos;
	@Autowired
	private GitCommitRepository commits;
	@Autowired
	private TaskGitCommitLinkRepository links;
	@Autowired
	private EntityManager entityManager;

	private Project project;
	private Project otherProject;
	private JiraIntegration sourceA;
	private JiraIntegration sourceB;

	@BeforeEach
	void setUp() {
		Semester semester = semesters.save(semester());
		AcademicClass academicClass = academicClasses.save(academicClass(semester));
		Subject subject = subjects.save(subject());
		Course course = courses.save(course(academicClass, subject, semester));
		project = projects.save(project(course, "Multi-Jira Project"));
		otherProject = projects.save(project(course, "Other Project"));

		sourceA = jiraIntegrations.save(integration(project, "cloud-a", "10001"));
		sourceB = jiraIntegrations.save(integration(project, "cloud-b", "10002"));
	}

	@Test
	void sameExternalIdFromTwoSourcesPersistsAsDistinctTaskUuids() {
		Task fromA = tasks.save(task(project, sourceA, "10099", "SAGA-A-1"));
		Task fromB = tasks.save(task(project, sourceB, "10099", "SAGA-B-1"));

		assertThat(fromA.getId()).isNotEqualTo(fromB.getId());
		assertThat(tasks.findByJiraIntegration_IdAndExternalId(sourceA.getId(), "10099"))
				.map(Task::getId)
				.contains(fromA.getId());
		assertThat(tasks.findByJiraIntegration_IdAndExternalId(sourceB.getId(), "10099"))
				.map(Task::getId)
				.contains(fromB.getId());
	}

	@Test
	void sameSourceAndExternalIdIsIdempotentIdentity() {
		Task first = tasks.save(task(project, sourceA, "20001", "SAGA-1"));

		Task duplicate = task(project, sourceA, "20001", "SAGA-1-DUP");
		assertThatThrownBy(() -> {
					tasks.saveAndFlush(duplicate);
				})
				.isInstanceOf(DataIntegrityViolationException.class);

		entityManager.clear();
		Optional<Task> found = tasks.findByJiraIntegration_IdAndExternalId(sourceA.getId(), "20001");
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(first.getId());
		assertThat(found.get().getExternalKey()).isEqualTo("SAGA-1");
	}

	@Test
	void softDeletedTaskStillRetainsJiraIntegration() {
		Task task = tasks.save(task(project, sourceA, "30001", "SAGA-SOFT"));
		task.setDeletedAt(LocalDateTime.of(2026, 9, 20, 12, 0));
		tasks.saveAndFlush(task);
		entityManager.clear();

		Task reloaded = tasks.findById(task.getId()).orElseThrow();
		assertThat(reloaded.getDeletedAt()).isNotNull();
		assertThat(reloaded.getJiraIntegration().getId()).isEqualTo(sourceA.getId());
	}

	@Test
	void backlogTaskWithNullSprintRetainsSource() {
		Task backlog = task(project, sourceA, "40001", "SAGA-BACKLOG");
		backlog.setSprint(null);
		backlog = tasks.saveAndFlush(backlog);
		entityManager.clear();

		Task reloaded = tasks.findById(backlog.getId()).orElseThrow();
		assertThat(reloaded.getSprint()).isNull();
		assertThat(reloaded.getJiraIntegration().getId()).isEqualTo(sourceA.getId());
		JiraTaskProjectionService.assertTaskProvenance(reloaded, sourceA);
	}

	@Test
	void taskFromSourceAWithSprintFromSourceBRejectedByAssertTaskProvenance() {
		Sprint sprintB = sprints.save(sprint(sourceB, "81", "Sprint B"));
		Task mismatched = task(project, sourceA, "50001", "SAGA-MISMATCH");
		mismatched.setSprint(sprintB);

		assertThatThrownBy(() -> JiraTaskProjectionService.assertTaskProvenance(mismatched, sourceA))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> {
					IntegrationException ie = (IntegrationException) ex;
					assertThat(ie.getCode()).isEqualTo(IntegrationErrorCode.JIRA_SPRINT_INVALID);
					assertThat(ie.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				});
	}

	@Test
	void taskSourceFromAnotherProjectsIntegrationRejectedByAssertTaskProvenance() {
		JiraIntegration foreign = jiraIntegrations.save(integration(otherProject, "cloud-x", "90001"));
		Task crossProject = task(project, foreign, "60001", "SAGA-X");

		assertThatThrownBy(() -> JiraTaskProjectionService.assertTaskProvenance(crossProject, foreign))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> {
					IntegrationException ie = (IntegrationException) ex;
					assertThat(ie.getCode()).isEqualTo(IntegrationErrorCode.INTEGRATION_UNAVAILABLE);
					assertThat(ie.getMessage()).contains("Task project must match its Jira integration project");
				});
	}

	@Test
	void revokeLeavesTaskAndTaskGitCommitLinkIntact() {
		Task task = tasks.save(task(project, sourceA, "70001", "SAGA-KEEP"));
		GitRepo repo = repos.save(gitRepo(project));
		GitCommit commit = commits.save(gitCommit(repo));
		TaskGitCommitLink link = links.save(taskGitCommitLink(task, commit));

		sourceA.setConnectionStatus(IntegrationStatus.REVOKED);
		jiraIntegrations.saveAndFlush(sourceA);
		entityManager.clear();

		assertThat(jiraIntegrations.findById(sourceA.getId()).orElseThrow().getConnectionStatus())
				.isEqualTo(IntegrationStatus.REVOKED);
		assertThat(tasks.findById(task.getId())).isPresent();
		assertThat(links.findById(link.getId())).isPresent();
		assertThat(tasks.findById(task.getId()).orElseThrow().getJiraIntegration().getId())
				.isEqualTo(sourceA.getId());
	}

	@Test
	void projectProgressCountIncludesTasksFromRevokedSource() {
		tasks.save(task(project, sourceA, "80001", "SAGA-ACTIVE-SRC"));
		Task fromRevoked = tasks.save(task(project, sourceB, "80002", "SAGA-REVOKED-SRC"));
		sourceB.setConnectionStatus(IntegrationStatus.REVOKED);
		jiraIntegrations.saveAndFlush(sourceB);

		assertThat(tasks.countByProject_IdAndDeletedAtIsNull(project.getId())).isEqualTo(2L);
		assertThat(tasks.findById(fromRevoked.getId())).isPresent();
	}

	@Test
	void findByProjectIdFailsClosedWhenTwoIntegrationsExist() {
		assertThatThrownBy(() -> jiraIntegrations.findByProject_Id(project.getId()))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> {
					IntegrationException ie = (IntegrationException) ex;
					assertThat(ie.getCode()).isEqualTo(IntegrationErrorCode.INTEGRATION_UNAVAILABLE);
					assertThat(ie.getStatus()).isEqualTo(HttpStatus.CONFLICT);
				});
	}

	@Test
	void findAllByProjectIdReturnsBothSources() {
		assertThat(jiraIntegrations.findAllByProject_Id(project.getId()))
				.extracting(JiraIntegration::getId)
				.containsExactlyInAnyOrder(sourceA.getId(), sourceB.getId());
	}

	@Test
	void findByJiraIntegrationIdAndExternalIdInIsOneQuery() {
		List<String> externalIds = IntStream.rangeClosed(1, 5)
				.mapToObj(i -> "batch-" + i)
				.toList();
		for (String externalId : externalIds) {
			tasks.save(task(project, sourceA, externalId, "SAGA-" + externalId));
		}
		entityManager.flush();
		entityManager.clear();

		Statistics stats = statistics();
		stats.clear();
		List<Task> found = tasks.findByJiraIntegration_IdAndExternalIdIn(sourceA.getId(), externalIds);
		assertThat(found).hasSize(5);
		assertThat(stats.getPrepareStatementCount())
				.as("batch external-id lookup must be a single IN query")
				.isEqualTo(1L);
	}

	private Statistics statistics() {
		return entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
	}

	private static JiraIntegration integration(Project project, String cloudId, String jiraProjectId) {
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setCloudId(cloudId);
		integration.setJiraProjectId(jiraProjectId);
		integration.setProjectKey("KEY-" + UUID.randomUUID().toString().substring(0, 6));
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setConsecutiveFailures(0);
		return integration;
	}

	private static Sprint sprint(JiraIntegration integration, String externalSprintId, String name) {
		Sprint sprint = new Sprint();
		sprint.setJiraIntegration(integration);
		sprint.setExternalSprintId(externalSprintId);
		sprint.setName(name);
		sprint.setState("active");
		return sprint;
	}

	private static Task task(Project project, JiraIntegration integration, String externalId, String key) {
		Task task = new Task();
		task.setProject(project);
		task.setJiraIntegration(integration);
		task.setExternalId(externalId);
		task.setExternalKey(key);
		task.setTitle(key);
		task.setStatus(TaskStatus.TODO);
		return task;
	}

	private static GitRepo gitRepo(Project project) {
		GitRepo repo = new GitRepo();
		repo.setProject(project);
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(42L);
		repo.setName("saga");
		repo.setFullName("org/saga");
		repo.setOwnerLogin("org");
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		repo.setConsecutiveFailures(0);
		return repo;
	}

	private static GitCommit gitCommit(GitRepo repo) {
		GitCommit commit = new GitCommit();
		commit.setRepo(repo);
		commit.setShaHash("a".repeat(40));
		commit.setMessage("feat: keep link after revoke");
		commit.setCommittedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
		return commit;
	}

	private static TaskGitCommitLink taskGitCommitLink(Task task, GitCommit commit) {
		TaskGitCommitLink link = new TaskGitCommitLink();
		link.setTask(task);
		link.setGitCommit(commit);
		link.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
		return link;
	}

	private static Semester semester() {
		Semester semester = new Semester();
		semester.setCode("SEM-" + UUID.randomUUID());
		semester.setName("Test Semester");
		return semester;
	}

	private static AcademicClass academicClass(Semester semester) {
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + (int) (Math.random() * 100000));
		academicClass.setName("Test Class");
		return academicClass;
	}

	private static Subject subject() {
		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 8));
		subject.setName("Software Project");
		return subject;
	}

	private static Course course(AcademicClass academicClass, Subject subject, Semester semester) {
		Course course = new Course();
		course.setName("Course");
		course.setAcademicClass(academicClass);
		course.setSubject(subject);
		course.setSemester(semester);
		return course;
	}

	private static Project project(Course course, String name) {
		Project project = new Project();
		project.setName(name);
		project.setCourse(course);
		return project;
	}
}
