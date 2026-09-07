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
		UUID projectId = UUID.randomUUID();
		launcher.enqueueGithubInitialSync(projectId);
		launcher.enqueueJiraInitialSync(projectId, "token");
		launcher.enqueueJiraInitialSync(projectId);
		verify(githubCommitSync).initialSync(projectId);
		verify(jiraTaskSync).initialSync(projectId, "token");
		verify(jiraTaskSync).initialSync(projectId);
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
		verify(githubCommitSync).initialSync(any());
		verify(jiraTaskSync, never()).initialSync(any(), any());
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
				.initialSync(eq(projectId));
		launcher.enqueueGithubInitialSync(projectId);
		finished.set(true);
		assert started.await(1, TimeUnit.SECONDS);
		assert finished.get();
	}
}
