package com.saga.be.service.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
class IntegrationInitialSyncLauncherTest {

	@Mock
	private JiraTaskSyncService jiraTaskSync;
	@Mock
	private GitHubCommitSyncService githubCommitSync;

	@InjectMocks
	private IntegrationInitialSyncLauncher launcher;

	@Test
	void enqueueMethodsInvokeSyncServices() {
		UUID integrationId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		launcher.enqueueGithubInitialSync(projectId);
		launcher.enqueueJiraInitialSync(integrationId, "token");
		launcher.enqueueJiraInitialSync(integrationId);
		verify(githubCommitSync).sync(projectId, GitHubCommitSyncService.Mode.FULL);
		verify(jiraTaskSync).initialSync(integrationId, "token");
		verify(jiraTaskSync).initialSync(integrationId);
	}

	/**
	 * Without Spring AOP in this unit test, @Async is not active — but ProjectIntegrationService
	 * only calls enqueue* and never initialSync directly (proven by ProjectIntegrationServiceTest).
	 * This documents the launcher boundary: HTTP layer must not call sync services inline.
	 */
	@Test
	void launcherIsTheOnlySyncEntryFromIntegrationLayer() {
		verifyNoInteractions(githubCommitSync, jiraTaskSync);
		launcher.enqueueGithubInitialSync(UUID.randomUUID());
		verify(githubCommitSync).sync(any(), eq(GitHubCommitSyncService.Mode.FULL));
		verify(jiraTaskSync, never()).initialSync(any(), any());
	}

	@Test
	void manualAndReconcileGithubSyncPassTheirModeThrough() {
		UUID projectId = UUID.randomUUID();
		launcher.enqueueGithubSync(projectId, GitHubCommitSyncService.Mode.INCREMENTAL);
		verify(githubCommitSync).sync(projectId, GitHubCommitSyncService.Mode.INCREMENTAL);
	}

	/** GitHub runs on its own thread so a long backfill never holds a Jira sync behind it. */
	@Test
	void githubAndJiraRunOnSeparateSyncThreads() throws Exception {
		Class<IntegrationInitialSyncLauncher> type = IntegrationInitialSyncLauncher.class;
		org.assertj.core.api.Assertions.assertThat(type.getMethod("enqueueGithubInitialSync", UUID.class)
				.getAnnotation(org.springframework.scheduling.annotation.Async.class).value()).isEqualTo("githubSyncExecutor");
		org.assertj.core.api.Assertions.assertThat(type.getMethod("enqueueGithubSync", UUID.class, GitHubCommitSyncService.Mode.class)
				.getAnnotation(org.springframework.scheduling.annotation.Async.class).value()).isEqualTo("githubSyncExecutor");
		org.assertj.core.api.Assertions.assertThat(type.getMethod("enqueueJiraInitialSync", UUID.class)
				.getAnnotation(org.springframework.scheduling.annotation.Async.class).value()).isEqualTo("integrationSyncExecutor");
		org.assertj.core.api.Assertions.assertThat(type.getMethod("enqueueJiraInitialSync", UUID.class, String.class)
				.getAnnotation(org.springframework.scheduling.annotation.Async.class).value()).isEqualTo("integrationSyncExecutor");
	}

	@Test
	void enqueueDoesNotPropagateSyncFailure() throws Exception {
		UUID projectId = UUID.randomUUID();
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean finished = new AtomicBoolean(false);
		org.mockito.Mockito.doAnswer((Answer<Object>) invocation -> {
					started.countDown();
					throw new RuntimeException("provider down");
				})
				.when(githubCommitSync)
				.sync(eq(projectId), eq(GitHubCommitSyncService.Mode.FULL));
		launcher.enqueueGithubInitialSync(projectId);
		finished.set(true);
		assert started.await(1, TimeUnit.SECONDS);
		assert finished.get();
	}
}
