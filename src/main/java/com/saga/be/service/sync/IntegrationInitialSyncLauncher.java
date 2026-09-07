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
	public void enqueueJiraInitialSync(UUID projectId) {
		try {
			jiraTaskSync.initialSync(projectId);
		} catch (RuntimeException ex) {
			log.warn("jira initial sync failed projectId={} type={}", projectId, ex.getClass().getSimpleName());
		}
	}

	/** Optional preferred access token used immediately after connect (still refreshes on 401). */
	@Async("integrationSyncExecutor")
	public void enqueueJiraInitialSync(UUID projectId, String preferredAccessToken) {
		try {
			jiraTaskSync.initialSync(projectId, preferredAccessToken);
		} catch (RuntimeException ex) {
			log.warn("jira initial sync failed projectId={} type={}", projectId, ex.getClass().getSimpleName());
		}
	}

	@Async("integrationSyncExecutor")
	public void enqueueGithubInitialSync(UUID projectId) {
		try {
			githubCommitSync.initialSync(projectId);
		} catch (RuntimeException ex) {
			log.warn("github initial sync failed projectId={} type={}", projectId, ex.getClass().getSimpleName());
		}
	}
}
