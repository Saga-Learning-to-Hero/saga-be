package com.saga.be.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.project.ProjectSyncEnqueueResponse;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.sync.GitHubCommitSyncService.Mode;
import com.saga.be.service.sync.ProjectManualSyncService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class IntegrationReconcileSchedulerTest {

	private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
	/** 14:00 in Vietnam: outside the night window. */
	private static final Instant AFTERNOON = Instant.parse("2026-10-04T07:00:00Z");
	/** 02:00 in Vietnam: inside the night window. */
	private static final Instant NIGHT = Instant.parse("2026-10-04T19:00:00Z");

	private JiraIntegrationRepository jiraIntegrations;
	private GitRepoRepository repos;
	private SyncJobLogRepository syncJobs;
	private ProjectManualSyncService sync;
	private IntegrationProperties properties;
	private Executor idle;

	@BeforeEach
	void setUp() {
		jiraIntegrations = mock(JiraIntegrationRepository.class);
		repos = mock(GitRepoRepository.class);
		syncJobs = mock(SyncJobLogRepository.class);
		sync = mock(ProjectManualSyncService.class);
		properties = new IntegrationProperties();
		properties.getJira().setEnabled(true);
		properties.getGithub().setEnabled(true);
		idle = Runnable::run;
		when(sync.enqueueJiraIntegration(any())).thenReturn(ProjectSyncEnqueueResponse.QUEUED);
		when(sync.enqueueGithubProject(any(), any())).thenReturn(ProjectSyncEnqueueResponse.QUEUED);
		when(syncJobs.findFirstByTargetSystemAndTargetIdAndJobTypeOrderByStartedAtDesc(any(), any(), any()))
				.thenReturn(Optional.empty());
	}

	// ---------- when is a target due ----------

	@Test
	void neverSynced_orOlderThanTheInterval_isDue_aRecentSyncAfterStartIsNot() {
		IntegrationReconcileScheduler scheduler = scheduler(AFTERNOON, true, 2);
		LocalDateTime now = LocalDateTime.ofInstant(AFTERNOON, ZoneId.systemDefault());

		assertThat(scheduler.isDue(null)).isTrue();
		assertThat(scheduler.isDue(now.minusHours(5))).isTrue();
		assertThat(scheduler.isDue(now.plusMinutes(1))).isFalse();
	}

	@Test
	void afterADeploy_everyTargetSyncedBeforeTheAppStartedIsDueOnce() {
		LocalDateTime now = LocalDateTime.ofInstant(AFTERNOON, ZoneId.systemDefault());
		IntegrationReconcileScheduler withResync = scheduler(AFTERNOON, true, 2);
		IntegrationReconcileScheduler withoutResync = scheduler(AFTERNOON, false, 2);

		// synced 10 minutes before this start: only the startup resync makes it due
		assertThat(withResync.isDue(now.minusMinutes(10))).isTrue();
		assertThat(withoutResync.isDue(now.minusMinutes(10))).isFalse();
		// synced after this start: not again until the interval passes
		assertThat(withResync.isDue(now.plusMinutes(5))).isFalse();
	}

	// ---------- Jira ----------

	@Test
	void jira_queuesTheOldestDueSitesFirst_atMostTheBatchPerTick() {
		LocalDateTime now = LocalDateTime.ofInstant(AFTERNOON, ZoneId.systemDefault());
		JiraIntegration fresh = jira();
		JiraIntegration old = jira();
		JiraIntegration older = jira();
		JiraIntegration never = jira();
		when(jiraIntegrations.findByConnectionStatus(IntegrationStatus.ACTIVE)).thenReturn(List.of(fresh, old, older, never));
		lastJob("JIRA", fresh.getId(), now.plusMinutes(1));
		lastJob("JIRA", old.getId(), now.minusHours(5));
		lastJob("JIRA", older.getId(), now.minusHours(9));

		scheduler(AFTERNOON, true, 2).reconcileJira();

		InOrder order = Mockito.inOrder(sync);
		order.verify(sync).enqueueJiraIntegration(never.getId());
		order.verify(sync).enqueueJiraIntegration(older.getId());
		verify(sync, never()).enqueueJiraIntegration(old.getId());
		verify(sync, never()).enqueueJiraIntegration(fresh.getId());
	}

	@Test
	void jira_aSkippedSiteDoesNotUseUpTheBatch() {
		JiraIntegration noCredential = jira();
		JiraIntegration a = jira();
		JiraIntegration b = jira();
		when(jiraIntegrations.findByConnectionStatus(IntegrationStatus.ACTIVE)).thenReturn(List.of(noCredential, a, b));
		when(sync.enqueueJiraIntegration(noCredential.getId())).thenReturn(ProjectSyncEnqueueResponse.SKIPPED_NO_CREDENTIAL);

		scheduler(AFTERNOON, true, 2).reconcileJira();

		verify(sync).enqueueJiraIntegration(a.getId());
		verify(sync).enqueueJiraIntegration(b.getId());
	}

	@Test
	void jira_onlyADataSyncCounts_notThe3amWebhookRefresh() {
		JiraIntegration source = jira();
		when(jiraIntegrations.findByConnectionStatus(IntegrationStatus.ACTIVE)).thenReturn(List.of(source));

		scheduler(AFTERNOON, true, 2).reconcileJira();

		verify(syncJobs).findFirstByTargetSystemAndTargetIdAndJobTypeOrderByStartedAtDesc("JIRA", source.getId(), SyncJobType.INITIAL);
	}

	@Test
	void jira_waitsWhileItsQueueHasWork_soAPersonPressingSyncIsNotStuckBehindIt() {
		ThreadPoolTaskExecutor busy = mock(ThreadPoolTaskExecutor.class);
		when(busy.getQueueSize()).thenReturn(1);
		new IntegrationReconcileScheduler(jiraIntegrations, repos, syncJobs, sync, properties, busy, idle,
				clock(AFTERNOON), settings(true, 2)).reconcileJira();

		verifyNoInteractions(jiraIntegrations);
		verify(sync, never()).enqueueJiraIntegration(any());
	}

	@Test
	void jira_aFullQueueStopsTheTick() {
		JiraIntegration a = jira();
		JiraIntegration b = jira();
		when(jiraIntegrations.findByConnectionStatus(IntegrationStatus.ACTIVE)).thenReturn(List.of(a, b));
		when(sync.enqueueJiraIntegration(a.getId())).thenThrow(new RejectedExecutionException("full"));

		scheduler(AFTERNOON, true, 2).reconcileJira();

		verify(sync, never()).enqueueJiraIntegration(b.getId());
	}

	@Test
	void disabledProviderOrDisabledReconcileDoesNothing() {
		properties.getJira().setEnabled(false);
		properties.getGithub().setEnabled(false);
		scheduler(AFTERNOON, true, 2).tick();
		verifyNoInteractions(jiraIntegrations, repos, sync);

		properties.getJira().setEnabled(true);
		properties.getGithub().setEnabled(true);
		new IntegrationReconcileScheduler(jiraIntegrations, repos, syncJobs, sync, properties, idle, idle,
				clock(AFTERNOON), new IntegrationReconcileScheduler.Settings(false, Duration.ofHours(4), 2, true,
						Duration.ofHours(24), 1, 5)).tick();
		verifyNoInteractions(jiraIntegrations, repos, sync);
	}

	// ---------- GitHub ----------

	@Test
	void github_duringTheDayIsIncremental() {
		UUID projectId = UUID.randomUUID();
		when(repos.findProjectIdsByConnectionStatus(IntegrationStatus.ACTIVE)).thenReturn(List.of(projectId));

		scheduler(AFTERNOON, true, 2).reconcileGithub();

		verify(sync).enqueueGithubProject(projectId, Mode.INCREMENTAL);
		verify(repos, never()).findByProject_IdAndConnectionStatus(any(), any());
	}

	@Test
	void github_atNightAFullWalkRunsWhenTheLastOneIsOlderThanADay() {
		UUID stale = UUID.randomUUID();
		UUID recent = UUID.randomUUID();
		LocalDateTime now = LocalDateTime.ofInstant(NIGHT, ZoneId.systemDefault());
		when(repos.findProjectIdsByConnectionStatus(IntegrationStatus.ACTIVE)).thenReturn(List.of(stale, recent));
		when(repos.findByProject_IdAndConnectionStatus(stale, IntegrationStatus.ACTIVE))
				.thenReturn(List.of(repo(now.minusHours(30))));
		when(repos.findByProject_IdAndConnectionStatus(recent, IntegrationStatus.ACTIVE))
				.thenReturn(List.of(repo(now.minusHours(3))));

		scheduler(NIGHT, true, 2).reconcileGithub();

		verify(sync).enqueueGithubProject(stale, Mode.FULL);
		verify(sync).enqueueGithubProject(recent, Mode.INCREMENTAL);
	}

	@Test
	void github_aRepoThatNeverFinishedAFullWalkGetsOneAtNight() {
		UUID projectId = UUID.randomUUID();
		when(repos.findByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE)).thenReturn(List.of(repo(null)));

		assertThat(scheduler(NIGHT, true, 2).githubMode(projectId)).isEqualTo(Mode.FULL);
		assertThat(scheduler(AFTERNOON, true, 2).githubMode(projectId)).isEqualTo(Mode.INCREMENTAL);
	}

	@Test
	void github_respectsTheBatchAndItsOwnQueue() {
		List<UUID> projects = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			projects.add(UUID.randomUUID());
		}
		when(repos.findProjectIdsByConnectionStatus(IntegrationStatus.ACTIVE)).thenReturn(projects);

		scheduler(AFTERNOON, true, 3).reconcileGithub();

		verify(sync, Mockito.times(3)).enqueueGithubProject(any(), any());
	}

	// ---------- helpers ----------

	private IntegrationReconcileScheduler scheduler(Instant now, boolean resyncOnStartup, int batch) {
		return new IntegrationReconcileScheduler(jiraIntegrations, repos, syncJobs, sync, properties, idle, idle,
				clock(now), settings(resyncOnStartup, batch));
	}

	private static IntegrationReconcileScheduler.Settings settings(boolean resyncOnStartup, int batch) {
		return new IntegrationReconcileScheduler.Settings(true, Duration.ofHours(4), batch, resyncOnStartup,
				Duration.ofHours(24), 1, 5);
	}

	private static Clock clock(Instant now) {
		return Clock.fixed(now, ZoneId.systemDefault());
	}

	private void lastJob(String system, UUID targetId, LocalDateTime startedAt) {
		SyncJobLog job = new SyncJobLog();
		job.setStartedAt(startedAt);
		when(syncJobs.findFirstByTargetSystemAndTargetIdAndJobTypeOrderByStartedAtDesc(system, targetId, SyncJobType.INITIAL))
				.thenReturn(Optional.of(job));
	}

	private static JiraIntegration jira() {
		JiraIntegration integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		return integration;
	}

	private static GitRepo repo(LocalDateTime lastFullWalk) {
		GitRepo repo = new GitRepo();
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		repo.setBranchMembershipSyncedAt(lastFullWalk);
		return repo;
	}
}
