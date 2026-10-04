package com.saga.be.service.sync;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Enqueues provider initial backfills off the HTTP request thread after integration ACTIVE is persisted.
 */
@Service
@Profile("!test")
public class IntegrationInitialSyncLauncher {

	private static final Logger log = LoggerFactory.getLogger(IntegrationInitialSyncLauncher.class);

	private final JiraTaskSyncService jiraTaskSync;
	private final GitHubCommitSyncService githubCommitSync;

	public IntegrationInitialSyncLauncher(JiraTaskSyncService jiraTaskSync, GitHubCommitSyncService githubCommitSync) {
		this.jiraTaskSync = jiraTaskSync;
		this.githubCommitSync = githubCommitSync;
	}

	@Async("integrationSyncExecutor")
	public void enqueueJiraInitialSync(UUID jiraIntegrationId) {
		try {
			jiraTaskSync.initialSync(jiraIntegrationId);
		} catch (RuntimeException ex) {
			log.warn(
					"jira initial sync failed integrationId={} type={}",
					jiraIntegrationId,
					ex.getClass().getSimpleName());
		}
	}

	/** Optional preferred access token used immediately after connect (still refreshes on 401). */
	@Async("integrationSyncExecutor")
	public void enqueueJiraInitialSync(UUID jiraIntegrationId, String preferredAccessToken) {
		try {
			jiraTaskSync.initialSync(jiraIntegrationId, preferredAccessToken);
		} catch (RuntimeException ex) {
			log.warn(
					"jira initial sync failed integrationId={} type={}",
					jiraIntegrationId,
					ex.getClass().getSimpleName());
		}
	}

	/** Full walk of every branch (first connect, nightly reconcile). */
	@Async("githubSyncExecutor")
	public void enqueueGithubInitialSync(UUID projectId) {
		enqueueGithubSync(projectId, GitHubCommitSyncService.Mode.FULL);
	}

	@Async("githubSyncExecutor")
	public void enqueueGithubSync(UUID projectId, GitHubCommitSyncService.Mode mode) {
		try {
			githubCommitSync.sync(projectId, mode);
		} catch (RuntimeException ex) {
			log.warn("github sync failed projectId={} mode={} type={}", projectId, mode, ex.getClass().getSimpleName());
		}
	}
}
