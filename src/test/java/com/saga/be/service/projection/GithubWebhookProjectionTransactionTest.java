package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.controller.ProviderWebhookController;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.WebhookReceiptStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.integration.WebhookReceipt;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.github.GitHubWebhookSignature;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.WebhookReceiptRepository;
import com.saga.be.service.ai.AiAnalysisSubmissionService;
import com.saga.be.service.ai.AiCommitAutomationTrigger;
import com.saga.be.service.attribution.AttributionWarningService;
import com.saga.be.service.jira.JiraEvidenceJobExecutor;
import com.saga.be.service.jira.JiraIssueEvidenceSyncService;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Production regression: a GitHub push for a project with more than one Jira source made the
 * legacy singular Jira lookup inside commit projection throw IntegrationException. The nested
 * {@code @Transactional} call marked the shared transaction rollback-only, the catch swallowed the
 * exception, and the commit then threw UnexpectedRollbackException (HTTP 500, FAILED state lost).
 * Commit projection no longer uses that lookup (source-aware attribution, see
 * {@link MultiJiraCommitAttributionTest}), so a genuine fatal projection error is now simulated by
 * a real database constraint violation: a branch ref longer than {@code git_commit.head_ref}.
 *
 * <p>Runs the real controller and the real Spring-proxied projection services on real JPA
 * transactions (the test itself is non-transactional), so commit/rollback behave as in production.
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
@Import({
	GitCommitProjectionService.class,
	CommitTaskAutoLinkService.class,
	JpaCommitMessageCandidateQuery.class,
	AiCommitAutomationTrigger.class,
	ProviderWebhookProjectionService.class,
	GithubWebhookProjectionTransactionTest.Collaborators.class
})
class GithubWebhookProjectionTransactionTest {

	private static final String SECRET = "github-webhook-test-secret";

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
				"org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration",
				"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"
			})
	@EntityScan(basePackages = "com.saga.be.entity")
	@EnableJpaRepositories(basePackages = "com.saga.be.repository")
	static class TxSlice {}

	@TestConfiguration
	static class Collaborators {
		@Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
		@Bean AiAnalysisSubmissionService aiAnalysisSubmissionService() { return mock(AiAnalysisSubmissionService.class); }
		@Bean ProjectRealtimePublisher projectRealtimePublisher() { return mock(ProjectRealtimePublisher.class); }
		@Bean JiraTaskProjectionService jiraTaskProjectionService() { return mock(JiraTaskProjectionService.class); }
		@Bean JiraIssueWriteClient jiraIssueWriteClient() { return mock(JiraIssueWriteClient.class); }
		@Bean JiraTeamTokenService jiraTeamTokenService() { return mock(JiraTeamTokenService.class); }
	}

	@Autowired private EntityManager em;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private WebhookReceiptRepository receipts;
	@Autowired private GitCommitRepository commits;
	@Autowired private com.saga.be.repository.JiraIntegrationRepository jiraIntegrations;
	@Autowired private ProviderWebhookProjectionService projection;
	@Autowired private AiAnalysisSubmissionService aiSubmissions;
	@Autowired private ProjectRealtimePublisher realtime;

	private ProviderWebhookController controller;

	@BeforeEach
	void setUp() {
		reset(aiSubmissions, realtime);
		IntegrationProperties properties = new IntegrationProperties();
		properties.getGithub().setWebhookSecret(SECRET);
		controller = new ProviderWebhookController(
				properties,
				receipts,
				mock(AttributionWarningService.class),
				projection,
				mock(JiraIssueEvidenceSyncService.class),
				mock(JiraEvidenceJobExecutor.class));
	}

	@Test
	void projectionFailureIsRecordedAsFailedWithoutUnexpectedRollbackOrPartialCommits() throws Exception {
		Seed seed = seed(1);
		String delivery = UUID.randomUUID().toString();
		String sha = sha();

		ResponseEntity<Void> response = assertDoesNotThrow(() -> post(delivery, fatalPush(seed.repositoryId(), sha)));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		WebhookReceipt receipt = receipt(delivery);
		assertThat(receipt.getReceiptStatus()).isEqualTo(WebhookReceiptStatus.FAILED);
		assertThat(receipt.getErrorCategory()).isEqualTo("GITHUB_PROJECTION_FAILED");
		assertThat(commitCount(seed.repoId(), sha)).as("projection is atomic: no half-applied commits").isZero();
		verify(aiSubmissions, never()).submitAutomatic(any(), any());
		verify(realtime, never()).publish(any(), any());
	}

	@Test
	void multiSourceProjectThatUsedToFailIsNowProjectedNormally() throws Exception {
		Seed multiSource = seed(2);
		String delivery = UUID.randomUUID().toString();
		String sha = sha();

		// The legacy singular lookup still fails closed for such a project (unchanged repository
		// semantics) -- commit projection simply no longer depends on it.
		assertThatThrownBy(() -> jiraIntegrations.findByProject_Id(multiSource.projectId()))
				.isInstanceOfSatisfying(IntegrationException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo(com.saga.be.integration.IntegrationErrorCode.INTEGRATION_UNAVAILABLE);
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
				})
				.hasMessageContaining("multiple Jira sources");

		ResponseEntity<Void> response = assertDoesNotThrow(() -> post(delivery, push(multiSource.repositoryId(), sha)));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(receipt(delivery).getReceiptStatus()).isEqualTo(WebhookReceiptStatus.PROCESSED);
		assertThat(commitCount(multiSource.repoId(), sha)).isEqualTo(1);
		verify(aiSubmissions, times(1)).submitAutomatic(eq(multiSource.projectId()), any());
	}

	@Test
	void transactionStateIsCleanAfterAHandledFailure() throws Exception {
		Seed failing = seed(1);
		String failedDelivery = UUID.randomUUID().toString();
		post(failedDelivery, fatalPush(failing.repositoryId(), sha()));
		assertThat(receipt(failedDelivery).getReceiptStatus()).isEqualTo(WebhookReceiptStatus.FAILED);

		Seed healthy = seed(1);
		String delivery = UUID.randomUUID().toString();
		String sha = sha();
		ResponseEntity<Void> response = post(delivery, push(healthy.repositoryId(), sha));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(receipt(delivery).getReceiptStatus()).isEqualTo(WebhookReceiptStatus.PROCESSED);
		assertThat(commitCount(healthy.repoId(), sha)).isEqualTo(1);
	}

	@Test
	void normalWebhookProjectsOnceThenTriggersAutomationAndRealtimeAfterCommit() throws Exception {
		Seed seed = seed(1);
		String delivery = UUID.randomUUID().toString();
		String sha = sha();

		ResponseEntity<Void> response = post(delivery, push(seed.repositoryId(), sha));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		WebhookReceipt receipt = receipt(delivery);
		assertThat(receipt.getReceiptStatus()).isEqualTo(WebhookReceiptStatus.PROCESSED);
		assertThat(receipt.getProcessedAt()).isNotNull();
		assertThat(commitCount(seed.repoId(), sha)).isEqualTo(1);
		verify(aiSubmissions, times(1)).submitAutomatic(eq(seed.projectId()), any());
		verify(realtime).publish(ProjectRealtimeEventType.COMMITS_CHANGED, seed.projectId());
	}

	@Test
	void duplicateRedeliveryIsIdempotentWithoutSecondSideEffects() throws Exception {
		Seed seed = seed(1);
		String delivery = UUID.randomUUID().toString();
		String sha = sha();
		String payload = push(seed.repositoryId(), sha);

		post(delivery, payload);
		ResponseEntity<Void> redelivered = post(delivery, payload);

		assertThat(redelivered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(receipt(delivery).getReceiptStatus()).isEqualTo(WebhookReceiptStatus.DUPLICATE);
		assertThat(commitCount(seed.repoId(), sha)).isEqualTo(1);
		verify(aiSubmissions, times(1)).submitAutomatic(eq(seed.projectId()), any());
		verify(realtime, times(1)).publish(ProjectRealtimeEventType.COMMITS_CHANGED, seed.projectId());
	}

	@Test
	void redeliveryOfAFailedDeliveryFollowsTheDocumentedNoReprocessRule() throws Exception {
		Seed seed = seed(1);
		String delivery = UUID.randomUUID().toString();
		String sha = sha();
		String payload = fatalPush(seed.repositoryId(), sha);

		post(delivery, payload);
		ResponseEntity<Void> redelivered = assertDoesNotThrow(() -> post(delivery, payload));

		assertThat(redelivered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(receipt(delivery).getReceiptStatus()).isEqualTo(WebhookReceiptStatus.DUPLICATE);
		assertThat(commitCount(seed.repoId(), sha)).isZero();
		verify(aiSubmissions, never()).submitAutomatic(any(), any());
	}

	@Test
	void invalidSignatureIsRejectedBeforeAnyReceiptIsWritten() {
		Seed seed = seed(1);
		String delivery = UUID.randomUUID().toString();
		MockHttpServletRequest request = request(push(seed.repositoryId(), sha()));

		assertThatThrownBy(() -> controller.github("sha256=" + "0".repeat(64), delivery, "push", request))
				.isInstanceOfSatisfying(IntegrationException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
		assertThat(receipts.findByProviderAndDeliveryId(IntegrationProvider.GITHUB, delivery)).isEmpty();
	}

	// ---- helpers ----

	private ResponseEntity<Void> post(String delivery, String payload) throws Exception {
		MockHttpServletRequest request = request(payload);
		String signature = "sha256=" + GitHubWebhookSignature.hmacSha256Hex(payload.getBytes(StandardCharsets.UTF_8), SECRET);
		return controller.github(signature, delivery, "push", request);
	}

	private static MockHttpServletRequest request(String payload) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhooks/github");
		request.setContent(payload.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	private static ResponseEntity<Void> assertDoesNotThrow(ThrowingSupplier supplier) throws Exception {
		ResponseEntity<Void>[] holder = new ResponseEntity[1];
		assertThatCode(() -> holder[0] = supplier.get()).doesNotThrowAnyException();
		return holder[0];
	}

	@FunctionalInterface
	private interface ThrowingSupplier {
		ResponseEntity<Void> get() throws Exception;
	}

	private static String push(long repositoryId, String sha) {
		return """
				{"ref":"refs/heads/main","repository":{"id":%d},"commits":[{"id":"%s","message":"feat: webhook","timestamp":"2026-09-24T10:00:00Z","author":{"username":"alice"}}]}
				""".formatted(repositoryId, sha);
	}

	/** A push whose branch ref cannot be stored ({@code git_commit.head_ref} is 255 chars): a
	 * genuine, non-mocked fatal projection error that must roll the whole projection back. */
	private static String fatalPush(long repositoryId, String sha) {
		return """
				{"ref":"refs/heads/%s","repository":{"id":%d},"commits":[{"id":"%s","message":"feat: webhook","timestamp":"2026-09-24T10:00:00Z","author":{"username":"alice"}}]}
				""".formatted("x".repeat(300), repositoryId, sha);
	}

	private static String sha() {
		return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 40);
	}

	private WebhookReceipt receipt(String delivery) {
		return receipts.findByProviderAndDeliveryId(IntegrationProvider.GITHUB, delivery).orElseThrow();
	}

	private long commitCount(UUID repoId, String sha) {
		return commits.findByRepo_IdAndShaHashIn(repoId, Set.of(sha)).size();
	}

	private record Seed(UUID projectId, UUID repoId, long repositoryId) {}

	/** One course/project with an ACTIVE GitHub repo and {@code jiraSources} Jira source rows. */
	private Seed seed(int jiraSources) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			String suffix = UUID.randomUUID().toString().substring(0, 8);
			Semester semester = new Semester();
			semester.setCode("FA" + suffix);
			semester.setName("Fall");
			em.persist(semester);
			AcademicClass academicClass = new AcademicClass();
			academicClass.setSemester(semester);
			academicClass.setClassCode("SE" + suffix);
			academicClass.setName("SE" + suffix);
			em.persist(academicClass);
			Subject subject = new Subject();
			subject.setSubjectCode("SWP" + suffix);
			subject.setName("Software Project");
			subject.setStatus(SubjectStatus.ACTIVE);
			em.persist(subject);
			Course course = new Course();
			course.setName("SWP " + suffix);
			course.setSubject(subject);
			course.setAcademicClass(academicClass);
			course.setSemester(semester);
			em.persist(course);
			Project project = new Project();
			project.setName("Project " + suffix);
			project.setCourse(course);
			em.persist(project);
			for (int i = 0; i < jiraSources; i++) {
				JiraIntegration jira = new JiraIntegration();
				jira.setProject(project);
				jira.setCloudId("cloud-" + UUID.randomUUID());
				jira.setJiraProjectId("1000" + i);
				jira.setProjectKey("SAGA");
				// A migrated project keeps its old source (REVOKED) next to the new ACTIVE one.
				jira.setConnectionStatus(i == 0 ? IntegrationStatus.ACTIVE : IntegrationStatus.REVOKED);
				jira.setConsecutiveFailures(0);
				em.persist(jira);
			}
			long repositoryId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1;
			GitRepo repo = new GitRepo();
			repo.setProject(project);
			repo.setProvider(GitProvider.GITHUB);
			repo.setRepositoryId(repositoryId);
			repo.setOwnerLogin("org");
			repo.setName("demo");
			repo.setFullName("org/demo-" + suffix);
			repo.setConnectionStatus(IntegrationStatus.ACTIVE);
			repo.setConsecutiveFailures(0);
			em.persist(repo);
			return new Seed(project.getId(), repo.getId(), repositoryId);
		});
	}
}
