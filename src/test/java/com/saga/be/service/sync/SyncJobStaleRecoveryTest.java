package com.saga.be.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.project.ProjectSyncEnqueueResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.projection.JiraTaskProjectionService;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
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
class SyncJobStaleRecoveryTest {

	@Mock
	private SyncJobLogRepository syncJobs;
	@Mock
	private PlatformTransactionManager transactionManager;
	@Mock
	private UserAccountRepository users;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private ProjectRepository projects;
	@Mock
	private JiraIntegrationRepository jiraIntegrations;
	@Mock
	private GitRepoRepository repos;
	@Mock
	private IntegrationInitialSyncLauncher launcher;
	@Mock
	private JiraIntegrationCredentialService credentials;
	@Mock
	private JiraOAuthClient jira;
	@Mock
	private JiraTaskProjectionService projection;

	private IntegrationProperties properties;
	private SyncJobClaimService claims;
	private UUID projectId;

	@BeforeEach
	void setUp() {
		properties = new IntegrationProperties();
		properties.setSyncJobStaleAfter(Duration.ofMinutes(30));
		when(transactionManager.getTransaction(any(TransactionDefinition.class)))
				.thenAnswer(inv -> new SimpleTransactionStatus());
		claims = new SyncJobClaimService(syncJobs, properties, transactionManager);
		projectId = UUID.randomUUID();
	}

	@Test
	void recentRunning_blocksReserveAndClaim() {
		SyncJobLog running = runningJob(LocalDateTime.now().minusMinutes(5));
		when(syncJobs.findByTargetSystemAndTargetIdAndStatus("JIRA", projectId, SyncJobStatus.RUNNING))
				.thenReturn(List.of(running));
		when(syncJobs.existsByTargetSystemAndTargetIdAndStatus("JIRA", projectId, SyncJobStatus.RUNNING))
				.thenReturn(true);

		assertThat(claims.tryReserveEnqueue("JIRA", projectId)).isFalse();
		assertThat(claims.tryClaim("JIRA", projectId, SyncJobType.INITIAL)).isEmpty();
		verify(syncJobs, never()).save(any());
	}

	@Test
	void staleRunning_isFailedAndNoLongerBlocks() {
		SyncJobLog stale = runningJob(LocalDateTime.now().minusHours(2));
		when(syncJobs.findByTargetSystemAndTargetIdAndStatus("JIRA", projectId, SyncJobStatus.RUNNING))
				.thenReturn(List.of(stale))
				.thenReturn(List.of());
		when(syncJobs.existsByTargetSystemAndTargetIdAndStatus("JIRA", projectId, SyncJobStatus.RUNNING))
				.thenReturn(false);
		when(syncJobs.save(any(SyncJobLog.class))).thenAnswer(inv -> inv.getArgument(0));

		assertThat(claims.tryReserveEnqueue("JIRA", projectId)).isTrue();
		assertThat(stale.getStatus()).isEqualTo(SyncJobStatus.FAILED);
		assertThat(stale.getErrorCategory()).isEqualTo(SyncJobClaimService.STALE_ERROR_CATEGORY);
		assertThat(stale.getFailureStage()).isEqualTo(SyncJobClaimService.STALE_FAILURE_STAGE);
		assertThat(stale.getCompletedAt()).isNotNull();
	}

	@Test
	void staleRunning_allowsNewClaim() {
		SyncJobLog stale = runningJob(LocalDateTime.now().minusDays(4));
		when(syncJobs.findByTargetSystemAndTargetIdAndStatus("GITHUB", projectId, SyncJobStatus.RUNNING))
				.thenReturn(List.of(stale));
		when(syncJobs.existsByTargetSystemAndTargetIdAndStatus("GITHUB", projectId, SyncJobStatus.RUNNING))
				.thenReturn(false);
		when(syncJobs.save(any(SyncJobLog.class))).thenAnswer(inv -> {
			SyncJobLog job = inv.getArgument(0);
			if (job.getId() == null) {
				job.setId(UUID.randomUUID());
			}
			return job;
		});

		Optional<SyncJobLog> claimed = claims.tryClaim("GITHUB", projectId, SyncJobType.INITIAL);
		assertThat(claimed).isPresent();
		assertThat(claimed.get().getStatus()).isEqualTo(SyncJobStatus.RUNNING);
		assertThat(stale.getStatus()).isEqualTo(SyncJobStatus.FAILED);
		assertThat(stale.getErrorCategory()).isEqualTo(SyncJobClaimService.STALE_ERROR_CATEGORY);
	}

	@Test
	void markSucceeded_finalizesRunningJob() {
		SyncJobLog running = runningJob(LocalDateTime.now());
		running.setId(UUID.randomUUID());
		when(syncJobs.findById(running.getId())).thenReturn(Optional.of(running));
		when(syncJobs.save(any(SyncJobLog.class))).thenAnswer(inv -> inv.getArgument(0));

		SyncJobLog done = claims.markSucceeded(running, 12);
		assertThat(done.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(done.getItemsProcessed()).isEqualTo(12);
		assertThat(done.getCompletedAt()).isNotNull();
	}

	@Test
	void workerException_marksFailedViaServiceFinally() {
		SyncJobClaimService mockClaims = org.mockito.Mockito.mock(SyncJobClaimService.class);
		JiraTaskSyncService service = new JiraTaskSyncService(
				jiraIntegrations, jira, projection, syncJobs, properties, credentials, mockClaims, transactionManager);
		SyncJobLog running = runningJob(LocalDateTime.now());
		running.setId(UUID.randomUUID());
		when(mockClaims.tryClaim("JIRA", projectId, SyncJobType.INITIAL)).thenReturn(Optional.of(running));
		Project project = new Project();
		project.setId(projectId);
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setCloudId("cloud");
		integration.setProjectKey("SAGA");
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		when(jiraIntegrations.findFetchedByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(credentials.resolveAccessToken(projectId)).thenReturn("token");
		when(jira.searchIssues(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE, HttpStatus.BAD_GATEWAY, "down"));
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(jiraIntegrations.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(mockClaims.markFailed(eq(running), eq("JIRA_PROJECT_NOT_ACCESSIBLE"), eq("provider")))
				.thenAnswer(inv -> {
					running.setStatus(SyncJobStatus.FAILED);
					running.setErrorCategory("JIRA_PROJECT_NOT_ACCESSIBLE");
					running.setFailureStage("provider");
					running.setCompletedAt(LocalDateTime.now());
					return running;
				});

		SyncJobLog result = service.initialSync(projectId);
		assertThat(result.getStatus()).isEqualTo(SyncJobStatus.FAILED);
		assertThat(result.getErrorCategory()).isEqualTo("JIRA_PROJECT_NOT_ACCESSIBLE");
		verify(mockClaims, never()).markSucceeded(any(), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	void executorRejection_releasesReservationWithoutLeavingRunning() {
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		ProjectManualSyncService manual = new ProjectManualSyncService(
				authorization, jiraIntegrations, repos, syncJobs, launcher, credentials, claims);
		UUID userId = UUID.randomUUID();
		UserAccount leader = new UserAccount();
		leader.setId(userId);
		leader.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(leader));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.LEADER));
		JiraIntegration jiraRow = new JiraIntegration();
		jiraRow.setConnectionStatus(IntegrationStatus.ACTIVE);
		when(jiraIntegrations.findFetchedByProject_Id(projectId)).thenReturn(Optional.of(jiraRow));
		when(credentials.hasRefreshOrAccessCredential(projectId)).thenReturn(true);
		org.mockito.Mockito.lenient()
				.when(syncJobs.findByTargetSystemAndTargetIdAndStatus(any(), any(), eq(SyncJobStatus.RUNNING)))
				.thenReturn(List.of());
		org.mockito.Mockito.lenient()
				.when(syncJobs.existsByTargetSystemAndTargetIdAndStatus(any(), any(), eq(SyncJobStatus.RUNNING)))
				.thenReturn(false);
		org.mockito.Mockito.lenient()
				.when(repos.findByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE))
				.thenReturn(List.of());
		org.mockito.Mockito.lenient().when(repos.findByProject_Id(projectId)).thenReturn(List.of());
		doThrow(new RejectedExecutionException("queue full")).when(launcher).enqueueJiraInitialSync(projectId);

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> manual.enqueue(userId, projectId))
				.isInstanceOf(RejectedExecutionException.class);
		assertThat(claims.tryReserveEnqueue("JIRA", projectId)).isTrue();
		verify(syncJobs, never()).save(any());
	}

	@Test
	void restartStyleStale_manualSyncCanQueueAgain() {
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		ProjectManualSyncService manual = new ProjectManualSyncService(
				authorization, jiraIntegrations, repos, syncJobs, launcher, credentials, claims);
		UUID userId = UUID.randomUUID();
		UserAccount leader = new UserAccount();
		leader.setId(userId);
		leader.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(leader));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.LEADER));
		JiraIntegration jiraRow = new JiraIntegration();
		jiraRow.setConnectionStatus(IntegrationStatus.ACTIVE);
		when(jiraIntegrations.findFetchedByProject_Id(projectId)).thenReturn(Optional.of(jiraRow));
		when(credentials.hasRefreshOrAccessCredential(projectId)).thenReturn(true);
		SyncJobLog stale = runningJob(LocalDateTime.now().minusDays(5));
		when(syncJobs.findByTargetSystemAndTargetIdAndStatus("JIRA", projectId, SyncJobStatus.RUNNING))
				.thenReturn(List.of(stale))
				.thenReturn(List.of());
		when(syncJobs.existsByTargetSystemAndTargetIdAndStatus("JIRA", projectId, SyncJobStatus.RUNNING))
				.thenReturn(false);
		when(syncJobs.save(any(SyncJobLog.class))).thenAnswer(inv -> inv.getArgument(0));
		when(repos.findByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE))
				.thenReturn(List.of(new GitRepo()));
		when(syncJobs.findByTargetSystemAndTargetIdAndStatus("GITHUB", projectId, SyncJobStatus.RUNNING))
				.thenReturn(List.of());
		when(syncJobs.existsByTargetSystemAndTargetIdAndStatus("GITHUB", projectId, SyncJobStatus.RUNNING))
				.thenReturn(false);

		ProjectSyncEnqueueResponse response = manual.enqueue(userId, projectId);
		assertThat(response.jira()).isEqualTo(ProjectSyncEnqueueResponse.QUEUED);
		assertThat(response.github()).isEqualTo(ProjectSyncEnqueueResponse.QUEUED);
		assertThat(stale.getStatus()).isEqualTo(SyncJobStatus.FAILED);
		verify(launcher).enqueueJiraInitialSync(projectId);
		verify(launcher).enqueueGithubInitialSync(projectId);
	}

	private SyncJobLog runningJob(LocalDateTime startedAt) {
		SyncJobLog job = new SyncJobLog();
		job.setId(UUID.randomUUID());
		job.setTargetSystem("JIRA");
		job.setTargetId(projectId);
		job.setJobType(SyncJobType.INITIAL);
		job.setStatus(SyncJobStatus.RUNNING);
		job.setStartedAt(startedAt);
		job.setItemsProcessed(0);
		job.setItemsFailed(0);
		return job;
	}
}
