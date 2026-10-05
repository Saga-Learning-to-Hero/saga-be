package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.ai.AiTeamCredential;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiArtifactType;
import com.saga.be.entity.enums.AiCredentialSource;
import com.saga.be.entity.enums.AiCredentialStatus;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.AiProviderDecisionStatus;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.entity.enums.AiProviderRoute;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.traceability.TaskCommitManualLink;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** The commit review's own tables and queries against a real (H2) schema built from the entities. */
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
class CommitAiReviewQueriesTest {

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

	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private EntityManager em;
	@Autowired private TaskCommitManualLinkRepository manualLinks;
	@Autowired private TaskGitCommitLinkRepository links;
	@Autowired private AiAnalysisRunRepository runs;
	@Autowired private AiAnalysisProviderDecisionRepository decisions;
	@Autowired private AiTeamCredentialRepository teamCredentials;
	@Autowired private AiRiskAnalysisRepository riskAnalyses;

	private TransactionTemplate tx;
	private Project projectA;
	private Project projectB;
	private UserAccount user;
	private GitCommit commit1;
	private GitCommit commit2;
	private Task liveTask;
	private Task deletedTask;
	private Task taskOfB;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		tx.executeWithoutResult(status -> seed());
	}

	@Test
	void manualLinksOfThisProjectOnly_deletedTasksLeftOut_taskFetched() {
		tx.executeWithoutResult(status -> {
			manual(projectA, liveTask, commit1);
			manual(projectA, deletedTask, commit1);
			manual(projectB, taskOfB, commit1);
		});

		List<TaskCommitManualLink> found = tx.execute(status -> {
			List<TaskCommitManualLink> rows = manualLinks.findFetchedByProjectAndCommitIds(projectA.getId(), List.of(commit1.getId(), commit2.getId()));
			rows.forEach(row -> row.getTask().getExternalKey()); // fetched with the task
			return rows;
		});

		assertThat(found).singleElement().satisfies(row -> assertThat(row.getTask().getId()).isEqualTo(liveTask.getId()));
		java.util.Optional<TaskCommitManualLink> byPair = tx.execute(status -> manualLinks.findByTask_IdAndGitCommit_Id(liveTask.getId(), commit1.getId()));
		assertThat(byPair).isPresent();
	}

	@Test
	void latestTaskRiskRowsCarryTheirRun_readableOutsideATransaction() {
		// a student's progress report read run.getArtifactId() outside a transaction: LazyInitializationException (500)
		tx.executeWithoutResult(status -> {
			AiAnalysisRun run = run(projectA, liveTask.getId(), AiAnalysisType.RISK_ANALYSIS, AiAnalysisStatus.COMPLETED);
			run.setArtifactType(AiArtifactType.TASK);
			com.saga.be.entity.ai.AiRiskAnalysis risk = new com.saga.be.entity.ai.AiRiskAnalysis();
			risk.setProject(em.find(Project.class, projectA.getId()));
			risk.setAnalysisRun(run);
			risk.setRiskLevel(com.saga.be.entity.enums.AiRiskLevel.MEDIUM);
			risk.setReasonsJson("[]");
			risk.setRecommendedActionsJson("[]");
			em.persist(risk);
		});

		List<com.saga.be.entity.ai.AiRiskAnalysis> rows = riskAnalyses.findLatestByTaskIds(List.of(liveTask.getId(), UUID.randomUUID()));

		assertThat(rows).singleElement().satisfies(row -> assertThat(row.getAnalysisRun().getArtifactId()).isEqualTo(liveTask.getId()));
	}

	@Test
	void theSameTaskCannotBeAttachedTwiceByHand() {
		tx.executeWithoutResult(status -> manual(projectA, liveTask, commit1));
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			manual(projectA, liveTask, commit1);
			em.flush();
		})).isInstanceOfAny(DataIntegrityViolationException.class, org.hibernate.exception.ConstraintViolationException.class)
				.hasMessageContaining("UK_TASK_COMMIT_MANUAL_LINK");
	}

	@Test
	void liveAutomaticLinksLeaveOutDeletedTasks() {
		tx.executeWithoutResult(status -> {
			automatic(liveTask, commit2);
			automatic(deletedTask, commit2);
		});

		List<UUID> taskIds = tx.execute(status -> links.findLiveWithTaskByGitCommitIds(List.of(commit1.getId(), commit2.getId()))
				.stream().map(link -> link.getTask().getId()).toList());

		assertThat(taskIds).containsExactly(liveTask.getId());
	}

	@Test
	void commitReviewRunsNewestFirst_onlyCommitIntelligenceOfThisProject() throws Exception {
		AiAnalysisRun older = tx.execute(status -> run(projectA, commit1.getId(), AiAnalysisType.COMMIT_INTELLIGENCE, AiAnalysisStatus.FAILED));
		Thread.sleep(15);
		AiAnalysisRun newer = tx.execute(status -> run(projectA, commit1.getId(), AiAnalysisType.COMMIT_INTELLIGENCE, AiAnalysisStatus.COMPLETED));
		tx.executeWithoutResult(status -> {
			run(projectA, commit2.getId(), AiAnalysisType.ACADEMIC_CLASSIFICATION, AiAnalysisStatus.COMPLETED);
			run(projectB, commit2.getId(), AiAnalysisType.COMMIT_INTELLIGENCE, AiAnalysisStatus.COMPLETED);
		});

		List<UUID> found = tx.execute(status -> runs.findCommitReviewRuns(projectA.getId(), List.of(commit1.getId(), commit2.getId()))
				.stream().map(AiAnalysisRun::getId).toList());

		assertThat(found).containsExactly(newer.getId(), older.getId());
	}

	@Test
	void aTeamKeyIsOnePerProject_andARunRecordsWhichTeamKeyPaid() {
		AiTeamCredential key = tx.execute(status -> teamKey(projectA));
		java.util.Optional<AiTeamCredential> ofA = tx.execute(status -> teamCredentials.findByProject_Id(projectA.getId()));
		java.util.Optional<AiTeamCredential> ofB = tx.execute(status -> teamCredentials.findByProject_Id(projectB.getId()));
		assertThat(ofA).isPresent();
		assertThat(ofB).isEmpty();
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			teamKey(projectA);
			em.flush();
		})).isInstanceOfAny(DataIntegrityViolationException.class, org.hibernate.exception.ConstraintViolationException.class)
				.hasMessageContaining("UK_AI_TEAM_CREDENTIAL_PROJECT");

		AiAnalysisRun run = tx.execute(status -> run(projectA, commit1.getId(), AiAnalysisType.COMMIT_INTELLIGENCE, AiAnalysisStatus.QUEUED));
		tx.executeWithoutResult(status -> {
			AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision();
			decision.setAnalysisRun(em.find(AiAnalysisRun.class, run.getId()));
			decision.setProviderRole(AiProviderRole.PRIMARY);
			decision.setProviderKey("remote");
			decision.setProviderConfigHash("cfg");
			decision.setModelId("gemini-3.6-flash");
			decision.setRoute(AiProviderRoute.NORMAL);
			decision.setStatus(AiProviderDecisionStatus.PENDING);
			decision.setCredentialSource(AiCredentialSource.COURSE);
			decision.setTeamCredentialId(key.getId());
			decision.setAiProvider(AiProvider.GEMINI);
			em.persist(decision);
		});

		AiAnalysisProviderDecision stored = tx.execute(status -> decisions.findByAnalysisRun_Id(run.getId()).orElseThrow());
		assertThat(stored.getTeamCredentialId()).isEqualTo(key.getId());
		assertThat(stored.getCourseCredentialId()).isNull();
	}

	// ---------------- seed

	private void seed() {
		Semester semester = new Semester();
		semester.setCode("FA" + UUID.randomUUID().toString().substring(0, 8));
		semester.setName("Fall");
		em.persist(semester);
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + UUID.randomUUID().toString().substring(0, 6));
		academicClass.setName("SE");
		em.persist(academicClass);
		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 8));
		subject.setName("Software Project");
		em.persist(subject);
		Course course = new Course();
		course.setName("SWP");
		course.setSubject(subject);
		course.setAcademicClass(academicClass);
		course.setSemester(semester);
		em.persist(course);
		projectA = project(course, "A");
		projectB = project(course, "B");
		user = new UserAccount();
		user.setEmail(UUID.randomUUID() + "@fpt.edu.vn");
		user.setAccountRole(AccountRole.STUDENT);
		user.setAccountStatus(AccountStatus.ACTIVE);
		em.persist(user);
		JiraIntegration jiraA = jira(projectA);
		JiraIntegration jiraB = jira(projectB);
		GitRepo repo = new GitRepo();
		repo.setProject(projectA);
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1);
		repo.setFullName("org/a");
		repo.setName("a");
		repo.setOwnerLogin("org");
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		repo.setConsecutiveFailures(0);
		em.persist(repo);
		commit1 = commit(repo, "c1" + UUID.randomUUID().toString().substring(0, 10));
		commit2 = commit(repo, "c2" + UUID.randomUUID().toString().substring(0, 10));
		liveTask = task(projectA, jiraA, "SAGA-1", null);
		deletedTask = task(projectA, jiraA, "SAGA-2", LocalDateTime.of(2026, 9, 1, 0, 0));
		taskOfB = task(projectB, jiraB, "OTHER-1", null);
	}

	private Project project(Course course, String name) {
		Project project = new Project();
		project.setName(name);
		project.setCourse(course);
		em.persist(project);
		return project;
	}

	private JiraIntegration jira(Project project) {
		JiraIntegration jira = new JiraIntegration();
		jira.setProject(project);
		jira.setCloudId("cloud-" + UUID.randomUUID());
		jira.setJiraProjectId("10001");
		jira.setProjectKey("SAGA");
		jira.setConnectionStatus(IntegrationStatus.ACTIVE);
		jira.setConsecutiveFailures(0);
		em.persist(jira);
		return jira;
	}

	private GitCommit commit(GitRepo repo, String sha) {
		GitCommit commit = new GitCommit();
		commit.setRepo(repo);
		commit.setShaHash(sha);
		commit.setMessage("msg");
		commit.setCommittedAt(LocalDateTime.of(2026, 10, 1, 0, 0));
		em.persist(commit);
		return commit;
	}

	private Task task(Project project, JiraIntegration jira, String key, LocalDateTime deletedAt) {
		Task task = new Task();
		task.setProject(project);
		task.setJiraIntegration(jira);
		task.setExternalKey(key);
		task.setExternalId(UUID.randomUUID().toString().substring(0, 8));
		task.setTitle("Task " + key);
		task.setDeletedAt(deletedAt);
		em.persist(task);
		return task;
	}

	private void manual(Project project, Task task, GitCommit commit) {
		TaskCommitManualLink link = new TaskCommitManualLink();
		link.setProject(em.find(Project.class, project.getId()));
		link.setTask(em.find(Task.class, task.getId()));
		link.setGitCommit(em.find(GitCommit.class, commit.getId()));
		link.setCreatedBy(em.find(UserAccount.class, user.getId()));
		em.persist(link);
	}

	private void automatic(Task task, GitCommit commit) {
		TaskGitCommitLink link = new TaskGitCommitLink();
		link.setTask(em.find(Task.class, task.getId()));
		link.setGitCommit(em.find(GitCommit.class, commit.getId()));
		link.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
		em.persist(link);
	}

	private AiAnalysisRun run(Project project, UUID artifactId, AiAnalysisType type, AiAnalysisStatus status) {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setProject(em.find(Project.class, project.getId()));
		run.setArtifactType(AiArtifactType.COMMIT);
		run.setArtifactId(artifactId);
		run.setArtifactRevision("rev");
		run.setAnalysisType(type);
		run.setStatus(status);
		run.setEvidenceHash("h");
		run.setPolicyVersion("p");
		run.setPromptVersion("commit-intelligence-v2");
		run.setSchemaVersion("s");
		run.setProviderConfigHash("cfg");
		String key = UUID.randomUUID().toString().replace("-", "");
		run.setIdempotencyKey(key);
		run.setCanonicalIdentityKey(key);
		run.setRetryAttempt(0);
		em.persist(run);
		return run;
	}

	private AiTeamCredential teamKey(Project project) {
		AiTeamCredential key = new AiTeamCredential();
		key.setProject(em.find(Project.class, project.getId()));
		key.setProvider(AiProvider.GEMINI);
		key.setModelId("gemini-3.6-flash");
		key.setEncryptedSecret("cipher");
		key.setEncryptionNonce("nonce");
		key.setEncryptionKeyVersion(1);
		key.setFingerprint("f".repeat(64));
		key.setLastFour("1234");
		key.setStatus(AiCredentialStatus.UNVERIFIED);
		em.persist(key);
		return key;
	}
}
