package com.saga.be.service.sync;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSearchPage;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.projection.JiraTaskProjectionService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!test")
public class JiraTaskSyncService {

	private static final Logger log = LoggerFactory.getLogger(JiraTaskSyncService.class);

	private final JiraIntegrationRepository integrations;
	private final JiraOAuthClient jira;
	private final JiraTaskProjectionService projection;
	private final SyncJobLogRepository syncJobs;
	private final IntegrationProperties properties;
	private final TransactionTemplate writes;

	public JiraTaskSyncService(
			JiraIntegrationRepository integrations,
			JiraOAuthClient jira,
			JiraTaskProjectionService projection,
			SyncJobLogRepository syncJobs,
			IntegrationProperties properties,
			PlatformTransactionManager transactionManager) {
		this.integrations = integrations;
		this.jira = jira;
		this.projection = projection;
		this.syncJobs = syncJobs;
		this.properties = properties;
		this.writes = new TransactionTemplate(transactionManager);
	}

	/**
	 * Provider HTTP outside JDBC TX. Failures do not revoke the integration.
	 * {@code jiraIssueBackfillLimit} is the hard soft-cap (not overridden by page size).
	 */
	public SyncJobLog initialSync(UUID projectId, String accessToken) {
		SyncJobLog job = beginJob(projectId);
		try {
			JiraIntegration integration = integrations.findFetchedByProject_Id(projectId).orElse(null);
			if (integration == null
					|| integration.getConnectionStatus() != IntegrationStatus.ACTIVE
					|| accessToken == null
					|| accessToken.isBlank()
					|| integration.getProjectKey() == null
					|| integration.getCloudId() == null) {
				return fail(job, "JIRA_INTEGRATION_INACTIVE", "persist");
			}
			int pageSize = Math.max(1, properties.getJiraIssuePageSize());
			int limit = Math.max(1, properties.getJiraIssueBackfillLimit());
			int startAt = 0;
			int processed = 0;
			while (processed < limit) {
				int remaining = limit - processed;
				int requestSize = Math.min(pageSize, remaining);
				IssueSearchPage page = jira.searchIssues(
						accessToken, integration.getCloudId(), integration.getProjectKey(), startAt, requestSize);
				List<IssueSummary> issues = page.issues() == null ? List.of() : page.issues();
				if (issues.isEmpty()) {
					break;
				}
				int take = Math.min(remaining, issues.size());
				List<IssueSummary> batch = new ArrayList<>(issues.subList(0, take));
				int upserted = writes.execute(status ->
						projection.upsertBatch(integration.getProject(), integration.getProjectKey(), batch));
				processed += upserted;
				startAt += issues.size();
				if (processed >= limit) {
					break;
				}
				if (startAt >= page.total() || issues.size() < requestSize) {
					break;
				}
			}
			writes.executeWithoutResult(status -> {
				JiraIntegration row = integrations.findByProject_Id(projectId).orElse(null);
				if (row != null) {
					row.setLastSyncedAt(LocalDateTime.now());
					row.setLastSuccessfulSyncAt(LocalDateTime.now());
					row.setConsecutiveFailures(0);
					row.setLastErrorCode(null);
					integrations.save(row);
				}
			});
			return succeed(job, processed);
		} catch (RuntimeException ex) {
			log.warn("jira initial sync failed projectId={} type={}", projectId, ex.getClass().getSimpleName());
			writes.executeWithoutResult(status -> {
				JiraIntegration row = integrations.findByProject_Id(projectId).orElse(null);
				if (row != null) {
					row.setConsecutiveFailures(row.getConsecutiveFailures() == null ? 1 : row.getConsecutiveFailures() + 1);
					row.setLastErrorCode("JIRA_SYNC_FAILED");
					row.setLastSyncedAt(LocalDateTime.now());
					integrations.save(row);
				}
			});
			return fail(job, "JIRA_SYNC_FAILED", "provider");
		}
	}

	private SyncJobLog beginJob(UUID projectId) {
		return writes.execute(status -> {
			SyncJobLog job = new SyncJobLog();
			job.setTargetSystem("JIRA");
			job.setTargetId(projectId);
			job.setJobType(SyncJobType.INITIAL);
			job.setStatus(SyncJobStatus.RUNNING);
			job.setStartedAt(LocalDateTime.now());
			job.setItemsProcessed(0);
			job.setItemsFailed(0);
			return syncJobs.save(job);
		});
	}

	private SyncJobLog succeed(SyncJobLog job, int processed) {
		return writes.execute(status -> {
			job.setStatus(SyncJobStatus.SUCCEEDED);
			job.setItemsProcessed(processed);
			job.setCompletedAt(LocalDateTime.now());
			return syncJobs.save(job);
		});
	}

	private SyncJobLog fail(SyncJobLog job, String category, String stage) {
		return writes.execute(status -> {
			job.setStatus(SyncJobStatus.FAILED);
			job.setErrorCategory(category);
			job.setFailureStage(stage);
			job.setCompletedAt(LocalDateTime.now());
			return syncJobs.save(job);
		});
	}
}
