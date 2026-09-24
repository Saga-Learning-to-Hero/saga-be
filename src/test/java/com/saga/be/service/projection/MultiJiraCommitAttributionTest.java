package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.controller.ProviderWebhookController;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.enums.WebhookReceiptStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.github.GithubProjectInstallation;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.integration.github.GitHubAppJwtService;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.integration.github.GitHubOAuthClient.CommitSummary;
import com.saga.be.integration.github.GitHubWebhookSignature;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.GithubProjectInstallationRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.WebhookReceiptRepository;
import com.saga.be.service.ai.AiAnalysisSubmissionService;
import com.saga.be.service.ai.AiCommitAutomationTrigger;
import com.saga.be.service.attribution.AttributionWarningService;
import com.saga.be.service.jira.JiraEvidenceJobExecutor;
import com.saga.be.service.jira.JiraIssueEvidenceSyncService;
import com.saga.be.service.projection.GitCommitProjectionService.CommitDraft;
import com.saga.be.service.sync.GitHubCommitSyncService;
import com.saga.be.service.sync.SyncJobClaimService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
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
 * Multi-Jira commit attribution through BOTH real entry paths -- the GitHub webhook controller and
 * the manual GitHub commit sync -- on real JPA transactions (the test itself is non-transactional).
 * Only the GitHub HTTP client, the AI submission sink and realtime publishing are mocked.
 *
 * <p>Invariant: any number of Jira sources (including REVOKED ones) never blocks commit
 * persistence; auto-linking only targets ACTIVE sources, resolves a key to exactly one source by
 * its projectKey, looks the task up inside that source, and never guesses on ambiguity.
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
			"spring.jpa.properties.hibernate.generate_statistics=true",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
	GitCommitProjectionService.class,
	CommitTaskAutoLinkService.class,
	JpaCommitMessageCandidateQuery.class,
	AiCommitAutomationTrigger.class,
	ProviderWebhookProjectionService.class,
	GitCommitBranchSnapshotService.class,
	MultiJiraCommitAttributionTest.Collaborators.class
})
class MultiJiraCommitAttributionTest {

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
	@Autowired private EntityManagerFactory emf;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private WebhookReceiptRepository receipts;
	@Autowired private GitCommitRepository commits;
	@Autowired private GitRepoRepository repos;
	@Autowired private GithubProjectInstallationRepository projectInstallations;
	@Autowired private SyncJobLogRepository syncJobs;
	@Autowired private TaskGitCommitLinkRepository links;
	@Autowired private JiraIntegrationRepository jiraIntegrations;
	@Autowired private ProviderWebhookProjectionService webhookProjection;
	@Autowired private GitCommitProjectionService commitProjection;
	@Autowired private CommitTaskAutoLinkService autoLink;
	@Autowired private GitCommitBranchSnapshotService branchSnapshots;
	@Autowired private AiAnalysisSubmissionService aiSubmissions;
	@Autowired private ProjectRealtimePublisher realtime;

	private ProviderWebhookController controller;
	private GitHubOAuthClient github;
	private GitHubCommitSyncService sync;

	@BeforeEach
	void setUp() {
		reset(aiSubmissions, realtime);
		IntegrationProperties properties = new IntegrationProperties();
		properties.getGithub().setWebhookSecret(SECRET);
		controller = new ProviderWebhookController(properties, receipts, mock(AttributionWarningService.class), webhookProjection,
				mock(JiraIssueEvidenceSyncService.class), mock(JiraEvidenceJobExecutor.class));
		github = mock(GitHubOAuthClient.class);
		GitHubAppJwtService jwt = mock(GitHubAppJwtService.class);
		when(jwt.createJwt()).thenReturn("app-jwt");
		when(github.createInstallationToken(anyString(), anyLong())).thenReturn("installation-token");
		when(github.listBranches(anyString(), anyString(), anyString())).thenReturn(List.of("main"));
		sync = new GitHubCommitSyncService(repos, projectInstallations, github, jwt, commitProjection, branchSnapshots, syncJobs,
				new SyncJobClaimService(syncJobs, properties, transactionManager), transactionManager, realtime);
	}

	// ---- A. zero Jira sources ----

	@Test
	void zeroJiraSourcesStillPersistsCommitsThroughWebhookAndSync() throws Exception {
		Seed seed = seed();
		String viaWebhook = sha();
		String viaSync = sha();

		assertThat(webhook(seed, viaWebhook, "SAGA-1 feat").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		syncOnce(seed, viaSync, "SAGA-2 feat");

		assertThat(commitIds(seed, viaWebhook, viaSync)).hasSize(2);
		assertThat(linkedKeys(seed)).isEmpty();
	}

	// ---- B. exactly one ACTIVE source: legacy behaviour unchanged ----

	@Test
	void singleActiveSourceKeepsTheExistingAutoLinkBehaviour() throws Exception {
		Seed seed = seed(source("SAGA", IntegrationStatus.ACTIVE));
		Task task = task(seed.sources().get(0), "SAGA-12", "10012");
		String sha = sha();

		webhook(seed, sha, "SAGA-12 fix login; also mentions ABC-99 noise");

		assertThat(linkedTaskIds(seed)).containsExactly(task.getId());
		assertThat(links(seed)).singleElement().satisfies(link -> {
			assertThat(link.getJiraKeySnapshot()).isEqualTo("SAGA-12");
			assertThat(link.getLinkSource()).isEqualTo(TraceLinkSource.COMMIT_MESSAGE);
		});
	}

	// ---- C. multiple ACTIVE sources ----

	@Test
	void multipleActiveSourcesLinkEachKeyToItsOwnSourceOnly() throws Exception {
		Seed seed = seed(source("SAGA", IntegrationStatus.ACTIVE), source("LMS", IntegrationStatus.ACTIVE));
		Task saga = task(seed.sources().get(0), "SAGA-1", "10001");
		Task lms = task(seed.sources().get(1), "LMS-42", "10042");
		// Same key text under the other source must never be picked for SAGA-1 / LMS-42.
		task(seed.sources().get(1), "SAGA-1", "20001");

		webhook(seed, sha(), "SAGA-1 and LMS-42 wiring");

		assertThat(linkedTaskIds(seed)).containsExactlyInAnyOrder(saga.getId(), lms.getId());
	}

	@Test
	void duplicateExternalIssueIdsAcrossSourcesStayDistinct() throws Exception {
		Seed seed = seed(source("SAGA", IntegrationStatus.ACTIVE), source("LMS", IntegrationStatus.ACTIVE));
		Task saga = task(seed.sources().get(0), "SAGA-7", "10007");
		Task lms = task(seed.sources().get(1), "LMS-7", "10007");

		syncOnce(seed, sha(), "SAGA-7 then LMS-7");

		assertThat(saga.getId()).isNotEqualTo(lms.getId());
		assertThat(linkedTaskIds(seed)).containsExactlyInAnyOrder(saga.getId(), lms.getId());
	}

	@Test
	void sameProjectKeyOnTwoActiveSourcesIsAmbiguousAndNeverLinkedButTheCommitPersists() throws Exception {
		// Allowed by the registry: projectKey alone is not unique across Jira sites.
		Seed seed = seed(source("SAGA", IntegrationStatus.ACTIVE), source("SAGA", IntegrationStatus.ACTIVE), source("LMS", IntegrationStatus.ACTIVE));
		task(seed.sources().get(0), "SAGA-3", "10003");
		task(seed.sources().get(1), "SAGA-3", "20003");
		Task lms = task(seed.sources().get(2), "LMS-3", "30003");
		String sha = sha();

		assertThat(webhook(seed, sha, "SAGA-3 LMS-3").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

		assertThat(commitIds(seed, sha)).hasSize(1);
		assertThat(linkedTaskIds(seed)).containsExactly(lms.getId()); // unambiguous key still links
	}

	// ---- D. ACTIVE + REVOKED ----

	@Test
	void revokedSourceNeitherPoisonsIngestionNorReceivesNewLinks() throws Exception {
		Seed seed = seed(source("SAGA", IntegrationStatus.ACTIVE), source("SAGA", IntegrationStatus.REVOKED), source("OLD", IntegrationStatus.REVOKED));
		Task active = task(seed.sources().get(0), "SAGA-9", "10009");
		task(seed.sources().get(1), "SAGA-9", "20009"); // same key, revoked source
		task(seed.sources().get(1), "SAGA-5", "20005"); // key only in the revoked source
		task(seed.sources().get(2), "OLD-1", "30001");
		String viaWebhook = sha();
		String viaSync = sha();

		assertThat(webhook(seed, viaWebhook, "SAGA-9 SAGA-5 OLD-1").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		syncOnce(seed, viaSync, "SAGA-9 SAGA-5 OLD-1 again");

		assertThat(commitIds(seed, viaWebhook, viaSync)).hasSize(2);
		assertThat(links(seed)).extracting(link -> link.getTask().getId()).containsOnly(active.getId()).hasSize(2);
		assertThat(receipts.findAll()).filteredOn(r -> r.getReceiptStatus() == WebhookReceiptStatus.FAILED).isEmpty();
	}

	@Test
	void historicalLinksToARevokedSourceAreKeptWhenTheCommitIsProjectedAgain() throws Exception {
		Seed seed = seed(source("SAGA", IntegrationStatus.ACTIVE), source("OLD", IntegrationStatus.ACTIVE));
		Task old = task(seed.sources().get(1), "OLD-2", "20002");
		String sha = sha();
		webhook(seed, sha, "OLD-2 legacy work");
		assertThat(linkedTaskIds(seed)).containsExactly(old.getId());

		revoke(seed.sources().get(1));
		syncOnce(seed, sha, "OLD-2 legacy work");

		assertThat(linkedTaskIds(seed)).containsExactly(old.getId());
		assertThat(commitIds(seed, sha)).hasSize(1);
	}

	@Test
	void unresolvedKeyNeverFabricatesALink() throws Exception {
		Seed seed = seed(source("SAGA", IntegrationStatus.ACTIVE), source("LMS", IntegrationStatus.REVOKED));
		String sha = sha();

		webhook(seed, sha, "SAGA-404 does not exist yet");

		assertThat(commitIds(seed, sha)).hasSize(1);
		assertThat(links(seed)).isEmpty();
	}

	@Test
	void taskArrivingLaterReconcilesOnlyWithinItsOwnActiveSource() throws Exception {
		Seed seed = seed(source("SAGA", IntegrationStatus.ACTIVE), source("SAGA", IntegrationStatus.REVOKED));
		String sha = sha();
		webhook(seed, sha, "SAGA-77 early commit");
		assertThat(links(seed)).isEmpty();

		Task revokedTwin = task(seed.sources().get(1), "SAGA-77", "20077");
		Task active = task(seed.sources().get(0), "SAGA-77", "10077");
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			autoLink.linkTasks(seed.projectId(), "SAGA", List.of(revokedTwin));
			autoLink.linkTasks(seed.projectId(), "SAGA", List.of(active));
		});

		assertThat(linkedTaskIds(seed)).containsExactly(active.getId());
	}

	// ---- idempotency across both paths ----

	@Test
	void webhookRedeliveryCreatesNoDuplicateCommitLinkOrAutomation() throws Exception {
		Seed seed = seed(source("SAGA", IntegrationStatus.ACTIVE), source("OLD", IntegrationStatus.REVOKED));
		task(seed.sources().get(0), "SAGA-1", "10001");
		String delivery = UUID.randomUUID().toString();
		String payload = push(seed.repositoryId(), sha(), "SAGA-1 feat");

		post(delivery, payload);
		post(delivery, payload);

		assertThat(receipts.findByProviderAndDeliveryId(IntegrationProvider.GITHUB, delivery).orElseThrow().getReceiptStatus()).isEqualTo(WebhookReceiptStatus.DUPLICATE);
		assertThat(commits.findAll()).filteredOn(c -> c.getRepo().getId().equals(seed.repoId())).hasSize(1);
		assertThat(links(seed)).hasSize(1);
		verify(aiSubmissions, times(1)).submitAutomatic(eq(seed.projectId()), any());
	}

	@Test
	void manualResyncCreatesNoDuplicateCommitOrLink() throws Exception {
		Seed seed = seed(source("SAGA", IntegrationStatus.ACTIVE), source("OLD", IntegrationStatus.REVOKED));
		task(seed.sources().get(0), "SAGA-1", "10001");
		String sha = sha();

		syncOnce(seed, sha, "SAGA-1 feat");
		syncOnce(seed, sha, "SAGA-1 feat");

		assertThat(commitIds(seed, sha)).hasSize(1);
		assertThat(links(seed)).hasSize(1);
	}

	@Test
	void webhookThenSyncOfTheSameShaConvergesOnOneCommitAndOneLink() throws Exception {
		Seed seed = seed(source("SAGA", IntegrationStatus.ACTIVE), source("OLD", IntegrationStatus.REVOKED));
		task(seed.sources().get(0), "SAGA-1", "10001");
		String sha = sha();

		webhook(seed, sha, "SAGA-1 feat");
		UUID commitId = commitIds(seed, sha).get(0);
		syncOnce(seed, sha, "SAGA-1 feat");

		assertThat(commitIds(seed, sha)).containsExactly(commitId);
		assertThat(links(seed)).hasSize(1);
		// Both paths hand the SAME canonical commit id to automation; de-duplication of the
		// semantic analysis itself stays with the submission service's idempotency key.
		verify(aiSubmissions, times(2)).submitAutomatic(seed.projectId(), commitId);
	}

	@Test
	void syncThenWebhookOfTheSameShaConvergesOnOneCommitAndOneLink() throws Exception {
		Seed seed = seed(source("SAGA", IntegrationStatus.ACTIVE), source("LMS", IntegrationStatus.ACTIVE));
		task(seed.sources().get(1), "LMS-8", "10008");
		String sha = sha();

		syncOnce(seed, sha, "LMS-8 feat");
		UUID commitId = commitIds(seed, sha).get(0);
		webhook(seed, sha, "LMS-8 feat");

		assertThat(commitIds(seed, sha)).containsExactly(commitId);
		assertThat(links(seed)).hasSize(1);
		verify(aiSubmissions, times(2)).submitAutomatic(seed.projectId(), commitId);
	}

	// ---- query bound ----

	@Test
	void attributionQueriesAreBoundedPerBatchNotPerCommit() {
		Seed seed = seed(source("SAGA", IntegrationStatus.ACTIVE), source("LMS", IntegrationStatus.ACTIVE), source("OLD", IntegrationStatus.REVOKED));
		for (int i = 1; i <= 40; i++) {
			task(seed.sources().get(0), "SAGA-" + i, "1" + i);
			task(seed.sources().get(1), "LMS-" + i, "2" + i);
		}
		GitRepo repo = repos.findById(seed.repoId()).orElseThrow();

		long one = queriesFor(() -> commitProjection.upsertBatchDetailed(repo, drafts(1, 1)));
		long forty = queriesFor(() -> commitProjection.upsertBatchDetailed(repo, drafts(2, 40)));

		assertThat(one).as("statistics are live").isPositive();
		assertThat(forty).isEqualTo(one);
		assertThat(links(seed)).hasSize(2 + 80);
	}

	// ---- helpers ----

	private long queriesFor(Runnable action) {
		var statistics = emf.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		action.run();
		return statistics.getQueryExecutionCount();
	}

	private static List<CommitDraft> drafts(int batch, int count) {
		List<CommitDraft> drafts = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			drafts.add(new CommitDraft("b" + batch + "c" + i + sha().substring(0, 20), "SAGA-" + i + " LMS-" + i + " batch " + batch,
					LocalDateTime.of(2026, 9, 24, 10, 0), null, "alice", "main"));
		}
		return drafts;
	}

	private ResponseEntity<Void> webhook(Seed seed, String sha, String message) throws Exception {
		ResponseEntity<Void>[] holder = new ResponseEntity[1];
		assertThatCode(() -> holder[0] = post(UUID.randomUUID().toString(), push(seed.repositoryId(), sha, message))).doesNotThrowAnyException();
		return holder[0];
	}

	private ResponseEntity<Void> post(String delivery, String payload) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhooks/github");
		request.setContent(payload.getBytes(StandardCharsets.UTF_8));
		String signature = "sha256=" + GitHubWebhookSignature.hmacSha256Hex(payload.getBytes(StandardCharsets.UTF_8), SECRET);
		return controller.github(signature, delivery, "push", request);
	}

	private void syncOnce(Seed seed, String sha, String message) {
		when(github.listCommits(anyString(), anyString(), anyString(), eq("main"), anyInt(), anyInt())).thenAnswer(call -> {
			int page = call.getArgument(4);
			return page == 1 ? List.of(new CommitSummary(sha, message, "2026-09-24T10:00:00Z", null, "alice", 1)) : List.of();
		});
		var job = sync.initialSync(seed.projectId());
		assertThat(job.getErrorCategory()).as("manual sync must succeed").isNull();
	}

	private static String push(long repositoryId, String sha, String message) {
		return """
				{"ref":"refs/heads/main","repository":{"id":%d},"commits":[{"id":"%s","message":"%s","timestamp":"2026-09-24T10:00:00Z","author":{"username":"alice"}}]}
				""".formatted(repositoryId, sha, message);
	}

	private static String sha() {
		return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 40);
	}

	private List<UUID> commitIds(Seed seed, String... shas) {
		return commits.findByRepo_IdAndShaHashIn(seed.repoId(), Set.of(shas)).stream().map(GitCommit::getId).toList();
	}

	private List<TaskGitCommitLink> links(Seed seed) {
		return links.findFetchedByProject_Id(seed.projectId());
	}

	private List<UUID> linkedTaskIds(Seed seed) {
		return links(seed).stream().map(link -> link.getTask().getId()).toList();
	}

	private Set<String> linkedKeys(Seed seed) {
		return links(seed).stream().map(TaskGitCommitLink::getJiraKeySnapshot).collect(Collectors.toSet());
	}

	private Task task(UUID sourceId, String key, String externalId) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			JiraIntegration source = em.find(JiraIntegration.class, sourceId);
			Task task = new Task();
			task.setProject(source.getProject());
			task.setJiraIntegration(source);
			task.setExternalKey(key);
			task.setExternalId(externalId);
			task.setTitle("Task " + key);
			em.persist(task);
			return task;
		});
	}

	private void revoke(UUID sourceId) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status ->
				em.find(JiraIntegration.class, sourceId).setConnectionStatus(IntegrationStatus.REVOKED));
	}

	private record SourceSpec(String projectKey, IntegrationStatus status) {}

	private static SourceSpec source(String projectKey, IntegrationStatus status) { return new SourceSpec(projectKey, status); }

	private record Seed(UUID projectId, UUID repoId, long repositoryId, List<UUID> sources) {}

	/** One course/project with an ACTIVE GitHub repo + installation and the given Jira sources. */
	private Seed seed(SourceSpec... specs) {
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
			List<UUID> sources = new ArrayList<>();
			for (int i = 0; i < specs.length; i++) {
				JiraIntegration jira = new JiraIntegration();
				jira.setProject(project);
				jira.setCloudId("cloud-" + UUID.randomUUID()); // distinct Jira sites
				jira.setJiraProjectId("1000" + i);
				jira.setProjectKey(specs[i].projectKey());
				jira.setConnectionStatus(specs[i].status());
				jira.setConsecutiveFailures(0);
				em.persist(jira);
				sources.add(jira.getId());
			}
			long repositoryId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1;
			GitRepo repo = new GitRepo();
			repo.setProject(project);
			repo.setProvider(GitProvider.GITHUB);
			repo.setRepositoryId(repositoryId);
			repo.setOwnerLogin("org");
			repo.setName("demo-" + suffix);
			repo.setFullName("org/demo-" + suffix);
			repo.setDefaultBranch("main");
			repo.setConnectionStatus(IntegrationStatus.ACTIVE);
			repo.setConsecutiveFailures(0);
			em.persist(repo);
			GithubInstallation installation = new GithubInstallation();
			installation.setInstallationId(Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L) + 1);
			installation.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
			installation.setConsecutiveFailures(0);
			em.persist(installation);
			GithubProjectInstallation membership = new GithubProjectInstallation();
			membership.setProject(project);
			membership.setInstallation(installation);
			em.persist(membership);
			return new Seed(project.getId(), repo.getId(), repositoryId, List.copyOf(sources));
		});
	}

}
