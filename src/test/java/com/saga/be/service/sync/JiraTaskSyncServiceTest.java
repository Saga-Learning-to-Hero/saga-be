package com.saga.be.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSearchPage;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.projection.JiraTaskProjectionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class JiraTaskSyncServiceTest {

	@Mock
	private JiraIntegrationRepository integrations;
	@Mock
	private JiraOAuthClient jira;
	@Mock
	private JiraTaskProjectionService projection;
	@Mock
	private SyncJobLogRepository syncJobs;
	@Mock
	private JiraIntegrationCredentialService credentials;
	@Mock
	private SyncJobClaimService claims;
	@Mock
	private PlatformTransactionManager transactionManager;

	private IntegrationProperties properties;
	private JiraTaskSyncService service;
	private UUID projectId;
	private Project project;
	private JiraIntegration integration;
	private final AtomicInteger openTx = new AtomicInteger();

	@BeforeEach
	void setUp() {
		properties = new IntegrationProperties();
		properties.setJiraIssuePageSize(50);
		when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(inv -> {
			openTx.incrementAndGet();
			return new SimpleTransactionStatus();
		});
		org.mockito.Mockito.lenient().doAnswer(inv -> {
			openTx.decrementAndGet();
			return null;
		}).when(transactionManager).commit(any());
		org.mockito.Mockito.lenient().doAnswer(inv -> {
			openTx.decrementAndGet();
			return null;
		}).when(transactionManager).rollback(any());
		service = new JiraTaskSyncService(
				integrations, jira, projection, syncJobs, properties, credentials, claims, transactionManager);
		projectId = UUID.randomUUID();
		project = new Project();
		project.setId(projectId);
		integration = new JiraIntegration();
		integration.setProject(project);
		integration.setCloudId("cloud");
		integration.setProjectKey("SAGA");
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setConsecutiveFailures(0);
		when(claims.tryClaim("JIRA", projectId, SyncJobType.INITIAL))
				.thenAnswer(inv -> Optional.of(runningJob()));
		org.mockito.Mockito.lenient()
				.when(claims.markSucceeded(any(SyncJobLog.class), anyInt()))
				.thenAnswer(inv -> {
					SyncJobLog job = inv.getArgument(0);
					job.setStatus(SyncJobStatus.SUCCEEDED);
					job.setItemsProcessed(inv.getArgument(1));
					job.setCompletedAt(java.time.LocalDateTime.now());
					return job;
				});
		org.mockito.Mockito.lenient()
				.when(claims.markFailed(any(SyncJobLog.class), anyString(), anyString()))
				.thenAnswer(inv -> {
					SyncJobLog job = inv.getArgument(0);
					job.setStatus(SyncJobStatus.FAILED);
					job.setErrorCategory(inv.getArgument(1));
					job.setFailureStage(inv.getArgument(2));
					job.setCompletedAt(java.time.LocalDateTime.now());
					return job;
				});
		org.mockito.Mockito.lenient()
				.when(syncJobs.save(any(SyncJobLog.class)))
				.thenAnswer(inv -> inv.getArgument(0));
		when(integrations.findFetchedByProject_Id(projectId)).thenReturn(Optional.of(integration));
		org.mockito.Mockito.lenient()
				.when(integrations.findByProject_Id(projectId))
				.thenReturn(Optional.of(integration));
		org.mockito.Mockito.lenient().when(integrations.save(any())).thenAnswer(inv -> inv.getArgument(0));
		org.mockito.Mockito.lenient()
				.when(projection.upsertBatch(eq(project), eq("SAGA"), any()))
				.thenAnswer(inv -> {
					assertThat(openTx.get()).as("upsert inside short JDBC TX").isPositive();
					return ((List<?>) inv.getArgument(2)).size();
				});
	}

	@Test
	void moreThanFiveHundredIssues_allImported() {
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(0), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 0), 520, 0, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(50), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 50), 520, 50, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(100), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 100), 520, 100, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(150), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 150), 520, 150, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(200), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 200), 520, 200, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(250), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 250), 520, 250, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(300), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 300), 520, 300, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(350), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 350), 520, 350, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(400), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 400), 520, 400, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(450), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 450), 520, 450, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(500), eq(50)))
				.thenReturn(new IssueSearchPage(issues(20, 500), 520, 500, 50));

		SyncJobLog job = service.initialSync(projectId, "token");

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(520);
		verify(jira, times(11)).searchIssues(anyString(), anyString(), anyString(), anyInt(), anyInt());
		verify(projection, times(11)).upsertBatch(eq(project), eq("SAGA"), any());
	}

	@Test
	void multiPageTraversal_continuesUntilExhausted() {
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(0), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 0), 120, 0, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(50), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 50), 120, 50, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(100), eq(50)))
				.thenReturn(new IssueSearchPage(issues(20, 100), 120, 100, 50));

		SyncJobLog job = service.initialSync(projectId, "token");

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(120);
		verify(jira, times(3)).searchIssues(anyString(), anyString(), anyString(), anyInt(), anyInt());
	}

	@Test
	void rerunFullSync_idempotentUpserts() {
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(0), eq(50)))
				.thenReturn(new IssueSearchPage(issues(3, 0), 3, 0, 50));

		assertThat(service.initialSync(projectId, "token").getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(service.initialSync(projectId, "token").getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		verify(projection, times(2)).upsertBatch(eq(project), eq("SAGA"), any());
	}

	@Test
	void providerFailureMidRun_failsPreservingPriorPages() {
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(0), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 0), 200, 0, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(50), eq(50)))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE, HttpStatus.BAD_GATEWAY, "down"));

		SyncJobLog job = service.initialSync(projectId, "token");

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.FAILED);
		assertThat(job.getErrorCategory()).isEqualTo("JIRA_PROJECT_NOT_ACCESSIBLE");
		verify(projection, times(1)).upsertBatch(eq(project), eq("SAGA"), any());
	}

	@Test
	void upsertBatchOncePerPage_notPerIssue() {
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(0), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 0), 50, 0, 50));

		service.initialSync(projectId, "token");

		verify(projection, times(1)).upsertBatch(eq(project), eq("SAGA"), any());
	}

	@Test
	void providerHttpOutsideJdbcTx() {
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(0), eq(50))).thenAnswer(inv -> {
			assertThat(openTx.get()).as("HTTP outside JDBC TX").isZero();
			return new IssueSearchPage(issues(2, 0), 2, 0, 50);
		});

		assertThat(service.initialSync(projectId, "token").getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		verify(projection, atLeastOnce()).upsertBatch(eq(project), eq("SAGA"), any());
	}

	@Test
	void validPreferredToken_doesNotRefresh() {
		when(jira.searchIssues(eq("good"), eq("cloud"), eq("SAGA"), eq(0), eq(50)))
				.thenReturn(new IssueSearchPage(issues(5, 0), 5, 0, 50));

		SyncJobLog job = service.initialSync(projectId, "good");

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		verify(credentials, never()).forceRefresh(any(), any());
		verify(jira, times(1)).searchIssues(eq("good"), anyString(), anyString(), anyInt(), anyInt());
	}

	@Test
	void unauthorized_refreshesOnce_thenRetrySucceeds() {
		when(jira.searchIssues(eq("stale"), eq("cloud"), eq("SAGA"), eq(0), eq(50)))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "rejected"));
		when(credentials.forceRefresh(projectId, "stale")).thenReturn("fresh");
		when(jira.searchIssues(eq("fresh"), eq("cloud"), eq("SAGA"), eq(0), eq(50)))
				.thenReturn(new IssueSearchPage(issues(5, 0), 5, 0, 50));

		SyncJobLog job = service.initialSync(projectId, "stale");

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(5);
		verify(credentials, times(1)).forceRefresh(projectId, "stale");
	}

	@Test
	void refreshRejected_failsSafelyWithoutLoop() {
		when(jira.searchIssues(eq("stale"), eq("cloud"), eq("SAGA"), eq(0), eq(50)))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "rejected"));
		when(credentials.forceRefresh(projectId, "stale"))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_TOKEN_REFRESH_FAILED, HttpStatus.BAD_GATEWAY, "refresh denied"));

		SyncJobLog job = service.initialSync(projectId, "stale");

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.FAILED);
		assertThat(job.getErrorCategory()).isEqualTo("JIRA_TOKEN_REFRESH_FAILED");
		verify(credentials, times(1)).forceRefresh(projectId, "stale");
		verify(jira, times(1)).searchIssues(anyString(), anyString(), anyString(), anyInt(), anyInt());
	}

	@Test
	void paginationGuard_failsNotFakeSuccess() {
		int previous = JiraTaskSyncService.maxIssuePages;
		JiraTaskSyncService.maxIssuePages = 3;
		try {
			properties.setJiraIssuePageSize(50);
			when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), anyInt(), eq(50))).thenAnswer(inv -> {
				int start = inv.getArgument(3);
				return new IssueSearchPage(issues(50, start), 10_000, start, 50);
			});

			SyncJobLog job = service.initialSync(projectId, "token");

			assertThat(job.getStatus()).isEqualTo(SyncJobStatus.FAILED);
			assertThat(job.getErrorCategory()).isEqualTo("JIRA_SYNC_INCOMPLETE");
			verify(projection, times(3)).upsertBatch(eq(project), eq("SAGA"), any());
		} finally {
			JiraTaskSyncService.maxIssuePages = previous;
		}
	}

	private SyncJobLog runningJob() {
		SyncJobLog job = new SyncJobLog();
		job.setTargetSystem("JIRA");
		job.setTargetId(projectId);
		job.setJobType(SyncJobType.INITIAL);
		job.setStatus(SyncJobStatus.RUNNING);
		job.setItemsProcessed(0);
		job.setItemsFailed(0);
		return job;
	}

	private static List<IssueSummary> issues(int count, int startId) {
		List<IssueSummary> list = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int n = startId + i;
			list.add(new IssueSummary(
					String.valueOf(n), "SAGA-" + n, "T" + n, "1", "To Do", "new", "Task", null, null, null));
		}
		return list;
	}
}
