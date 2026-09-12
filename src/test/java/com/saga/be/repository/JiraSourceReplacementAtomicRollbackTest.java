package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.WorkSessionStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.service.identity.JiraTaskProjectionHardReset;
import java.time.LocalDateTime;
import java.util.UUID;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real H2 + real {@link PlatformTransactionManager} + the real (unmocked) production {@link
 * JiraTaskProjectionHardReset}. Proves the exact sequence {@code
 * ProjectIntegrationService.persistJiraIntegration} runs during a Jira source replacement --
 * hard-delete Task rows for the project, then hard-delete Sprint rows for the reused {@code
 * jira_integration} row, both inside one transaction -- is atomic and ordered safely:
 *
 * <ul>
 *   <li>Task is deleted before Sprint, so a live {@code task.sprint_id -> sprint.id} FK never
 *       blocks the Sprint delete (see {@code fk_task_sprint}, which is RESTRICT, not CASCADE).
 *   <li>When a protected-evidence row ({@code task_work_session}, no cascade on {@code task_id})
 *       still references a Task, the Task delete itself fails with {@code
 *       DataIntegrityViolationException} and the WHOLE transaction rolls back -- the Task, its
 *       Sprint, and the work session all survive unchanged, exactly as {@code
 *       JIRA_SOURCE_REPLACE_BLOCKED_BY_EVIDENCE} promises the caller.
 * </ul>
 *
 * <p>Deliberately placed in {@code com.saga.be.repository}, matching {@code
 * PasswordResetConcurrencyTest}, to avoid the nested {@code @SpringBootConfiguration TxSlice}
 * being auto-detected by a sibling plain {@code @SpringBootTest}, and disabling the test's own
 * default transaction wrapping ({@code Propagation.NOT_SUPPORTED}) so the {@link
 * TransactionTemplate} calls below use real, independently-committed-or-rolled-back transactions.
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
class JiraSourceReplacementAtomicRollbackTest {

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
	private JiraIntegrationRepository jiraIntegrations;
	@Autowired
	private TaskRepository tasks;
	@Autowired
	private SprintRepository sprints;
	@Autowired
	private TaskWorkSessionRepository workSessions;
	@Autowired
	private ContributionConfirmationRepository confirmations;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private JiraTaskProjectionHardReset reset;
	private Project projectB;
	private JiraIntegration integrationSourceX;
	private Sprint sprintUnderSourceX;
	private Task taskUnderSourceX;
	private TaskWorkSession protectedEvidence;

	@BeforeEach
	void setUp() {
		reset = new JiraTaskProjectionHardReset(tasks, workSessions, confirmations, sprints);

		Semester semester = semesters.save(semester());
		AcademicClass academicClass = academicClasses.save(academicClass(semester));
		Subject subject = subjects.save(subject());
		Course course = courses.save(course(academicClass, subject, semester));
		projectB = projects.save(project(course));

		UserAccount account = users.save(userAccount());

		integrationSourceX = jiraIntegrations.save(integration(projectB, "cloud-x", "old-source-x"));
		sprintUnderSourceX = sprints.save(sprint(integrationSourceX, "1", "Sprint under source X"));
		taskUnderSourceX = tasks.save(task(projectB, sprintUnderSourceX));
		protectedEvidence = workSessions.save(workSession(taskUnderSourceX, account, projectB));
	}

	@Test
	void protectedEvidenceBlocksTaskDeleteAndRollsBackTheWholeSourceReplacementTransaction() {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
					// Exact order persistJiraIntegration runs during a source replacement.
					reset.hardDeleteAllTasksForProject(projectB.getId());
					reset.hardDeleteAllSprintsForIntegration(integrationSourceX.getId());
				}))
				.isInstanceOf(DataIntegrityViolationException.class);

		// Nothing was partially applied: Task, its Sprint, and the protected evidence itself all
		// survive exactly as they were -- matching what JIRA_SOURCE_REPLACE_BLOCKED_BY_EVIDENCE
		// promises the caller (Tasks remain, Sprints remain, JiraIntegration source unchanged).
		assertThat(tasks.findById(taskUnderSourceX.getId())).isPresent();
		assertThat(sprints.findById(sprintUnderSourceX.getId())).isPresent();
		assertThat(workSessions.findById(protectedEvidence.getId())).isPresent();
		JiraIntegration reloaded =
				jiraIntegrations.findById(integrationSourceX.getId()).orElseThrow();
		assertThat(reloaded.getCloudId()).isEqualTo("cloud-x");
		assertThat(reloaded.getJiraProjectId()).isEqualTo("old-source-x");
	}

	@Test
	void withNoProtectedEvidenceTaskThenSprintDeleteSucceedsInDeleteOrder() {
		workSessions.delete(protectedEvidence);

		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		tx.executeWithoutResult(status -> {
			reset.hardDeleteAllTasksForProject(projectB.getId());
			reset.hardDeleteAllSprintsForIntegration(integrationSourceX.getId());
		});

		assertThat(tasks.findById(taskUnderSourceX.getId())).isEmpty();
		assertThat(sprints.findById(sprintUnderSourceX.getId())).isEmpty();
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

	private static Task task(Project project, Sprint sprint) {
		Task task = new Task();
		task.setProject(project);
		task.setSprint(sprint);
		task.setExternalKey("SAGA-" + UUID.randomUUID().toString().substring(0, 6));
		task.setExternalId(UUID.randomUUID().toString());
		task.setTitle("Task under source X");
		task.setStatus(TaskStatus.IN_PROGRESS);
		return task;
	}

	private static TaskWorkSession workSession(Task task, UserAccount user, Project project) {
		TaskWorkSession session = new TaskWorkSession();
		session.setTask(task);
		session.setUser(user);
		session.setProject(project);
		session.setStartedAt(LocalDateTime.now());
		session.setStatus(WorkSessionStatus.OPEN);
		return session;
	}

	private static UserAccount userAccount() {
		UserAccount account = new UserAccount();
		account.setEmail("worker-" + UUID.randomUUID() + "@fpt.edu.vn");
		account.setFullName("Worker");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
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

	private static Project project(Course course) {
		Project project = new Project();
		project.setName("Project B");
		project.setCourse(course);
		return project;
	}
}
