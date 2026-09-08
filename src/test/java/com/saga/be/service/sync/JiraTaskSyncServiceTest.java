package com.saga.be.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

	@BeforeEach
	void setUp() {
		properties = new IntegrationProperties();
		when(transactionManager.getTransaction(any(TransactionDefinition.class)))
				.thenAnswer(inv -> new SimpleTransactionStatus());
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
	}

	@Test
	void backfillLimitBelowPageSize_requestsAndProjectsAtMostLimit() {
		properties.setJiraIssuePageSize(50);
		properties.setJiraIssueBackfillLimit(10);
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(0), eq(10)))
				.thenReturn(new IssueSearchPage(issues(10, 0), 200, 0, 10));
		when(projection.upsertBatch(eq(project), eq("SAGA"), any()))
				.thenAnswer(inv -> ((List<?>) inv.getArgument(2)).size());

		SyncJobLog job = service.initialSync(projectId, "token");

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(10);
		verify(jira, times(1)).searchIssues(anyString(), anyString(), anyString(), anyInt(), anyInt());
		verify(credentials, never()).forceRefresh(any(), any());
		verify(credentials, never()).resolveAccessToken(any());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<IssueSummary>> batch = ArgumentCaptor.forClass(List.class);
		verify(projection).upsertBatch(eq(project), eq("SAGA"), batch.capture());
		assertThat(batch.getValue()).hasSize(10);
	}

	@Test
	void validPreferredToken_doesNotRefresh() {
		properties.setJiraIssuePageSize(50);
		properties.setJiraIssueBackfillLimit(5);
		when(jira.searchIssues(eq("good"), eq("cloud"), eq("SAGA"), eq(0), eq(5)))
				.thenReturn(new IssueSearchPage(issues(5, 0), 5, 0, 5));
		when(projection.upsertBatch(eq(project), eq("SAGA"), any()))
				.thenAnswer(inv -> ((List<?>) inv.getArgument(2)).size());

		SyncJobLog job = service.initialSync(projectId, "good");

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		verify(credentials, never()).forceRefresh(any(), any());
		verify(jira, times(1)).searchIssues(eq("good"), anyString(), anyString(), anyInt(), anyInt());
	}

	@Test
	void unauthorized_refreshesOnce_thenRetrySucceeds() {
		properties.setJiraIssuePageSize(50);
		properties.setJiraIssueBackfillLimit(5);
		when(jira.searchIssues(eq("stale"), eq("cloud"), eq("SAGA"), eq(0), eq(5)))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "rejected"));
		when(credentials.forceRefresh(projectId, "stale")).thenReturn("fresh");
		when(jira.searchIssues(eq("fresh"), eq("cloud"), eq("SAGA"), eq(0), eq(5)))
				.thenReturn(new IssueSearchPage(issues(5, 0), 5, 0, 5));
		when(projection.upsertBatch(eq(project), eq("SAGA"), any()))
				.thenAnswer(inv -> ((List<?>) inv.getArgument(2)).size());

		SyncJobLog job = service.initialSync(projectId, "stale");

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(5);
		verify(credentials, times(1)).forceRefresh(projectId, "stale");
		verify(jira, times(1)).searchIssues(eq("stale"), anyString(), anyString(), anyInt(), anyInt());
		verify(jira, times(1)).searchIssues(eq("fresh"), anyString(), anyString(), anyInt(), anyInt());
	}

	@Test
	void refreshRejected_failsSafelyWithoutLoop() {
		properties.setJiraIssuePageSize(50);
		properties.setJiraIssueBackfillLimit(5);
		when(jira.searchIssues(eq("stale"), eq("cloud"), eq("SAGA"), eq(0), eq(5)))
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
	void limit120_paginates50_50_20() {
		properties.setJiraIssuePageSize(50);
		properties.setJiraIssueBackfillLimit(120);
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(0), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 0), 500, 0, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(50), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 50), 500, 50, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(100), eq(20)))
				.thenReturn(new IssueSearchPage(issues(20, 100), 500, 100, 20));
		when(projection.upsertBatch(eq(project), eq("SAGA"), any()))
				.thenAnswer(inv -> ((List<?>) inv.getArgument(2)).size());

		SyncJobLog job = service.initialSync(projectId, "token");

		assertThat(job.getItemsProcessed()).isEqualTo(120);
		verify(jira).searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(0), eq(50));
		verify(jira).searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(50), eq(50));
		verify(jira).searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(100), eq(20));
		verify(jira, times(3)).searchIssues(anyString(), anyString(), anyString(), anyInt(), anyInt());
	}

	@Test
	void fewerIssuesThanLimit_stopsWithoutExtraRequests() {
		properties.setJiraIssuePageSize(50);
		properties.setJiraIssueBackfillLimit(500);
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(0), eq(50)))
				.thenReturn(new IssueSearchPage(issues(50, 0), 63, 0, 50));
		when(jira.searchIssues(eq("token"), eq("cloud"), eq("SAGA"), eq(50), eq(50)))
				.thenReturn(new IssueSearchPage(issues(13, 50), 63, 50, 50));
		when(projection.upsertBatch(eq(project), eq("SAGA"), any()))
				.thenAnswer(inv -> ((List<?>) inv.getArgument(2)).size());

		SyncJobLog job = service.initialSync(projectId, "token");

		assertThat(job.getItemsProcessed()).isEqualTo(63);
		verify(jira, times(2)).searchIssues(anyString(), anyString(), anyString(), anyInt(), anyInt());
		verify(jira, never()).searchIssues(anyString(), anyString(), anyString(), eq(100), anyInt());
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
