package com.saga.be.service.sync;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
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
	private final JiraIntegrationCredentialService credentials;
	private final SyncJobClaimService claims;
	private final TransactionTemplate writes;

	public JiraTaskSyncService(
			JiraIntegrationRepository integrations,
			JiraOAuthClient jira,
			JiraTaskProjectionService projection,
			SyncJobLogRepository syncJobs,
			IntegrationProperties properties,
			JiraIntegrationCredentialService credentials,
			SyncJobClaimService claims,
			PlatformTransactionManager transactionManager) {
		this.integrations = integrations;
		this.jira = jira;
		this.projection = projection;
		this.syncJobs = syncJobs;
		this.properties = properties;
		this.credentials = credentials;
		this.claims = claims;
		this.writes = new TransactionTemplate(transactionManager);
	}

	/** Resolve credentials from DB (refresh if needed). */
	public SyncJobLog initialSync(UUID projectId) {
		return initialSync(projectId, null);
	}

	/**
	 * Provider HTTP outside JDBC TX. Optional preferredAccess avoids refresh right after connect.
	 * On 401: one credential refresh + one provider retry maximum.
	 */
	public SyncJobLog initialSync(UUID projectId, String preferredAccess) {
		SyncJobLog job = beginJob(projectId);
		if (job == null) {
			return alreadyRunning(projectId);
		}
		boolean finalized = false;
		try {
			JiraIntegration integration = integrations.findFetchedByProject_Id(projectId).orElse(null);
			if (integration == null
					|| integration.getConnectionStatus() != IntegrationStatus.ACTIVE
					|| integration.getProjectKey() == null
					|| integration.getCloudId() == null) {
				finalized = true;
				return claims.markFailed(job, "JIRA_INTEGRATION_INACTIVE", "persist");
			}
			String accessToken = preferredAccess != null && !preferredAccess.isBlank()
					? preferredAccess
					: credentials.resolveAccessToken(projectId);
			int pageSize = Math.max(1, properties.getJiraIssuePageSize());
			int limit = Math.max(1, properties.getJiraIssueBackfillLimit());
			int startAt = 0;
			int processed = 0;
			boolean refreshedForUnauthorized = false;
			while (processed < limit) {
				int remaining = limit - processed;
				int requestSize = Math.min(pageSize, remaining);
				IssueSearchPage page;
				try {
					page = jira.searchIssues(
							accessToken, integration.getCloudId(), integration.getProjectKey(), startAt, requestSize);
				} catch (IntegrationException ex) {
					if (ex.getCode() == IntegrationErrorCode.JIRA_UNAUTHORIZED && !refreshedForUnauthorized) {
						String rejected = accessToken;
						accessToken = credentials.forceRefresh(projectId, rejected);
						refreshedForUnauthorized = true;
						page = jira.searchIssues(
								accessToken, integration.getCloudId(), integration.getProjectKey(), startAt, requestSize);
					} else {
						throw ex;
					}
				}
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
			finalized = true;
			return claims.markSucceeded(job, processed);
		} catch (IntegrationException ex) {
			log.warn("jira initial sync failed projectId={} code={}", projectId, ex.getCode());
			markIntegrationFailure(projectId, ex.getCode().name());
			finalized = true;
			return claims.markFailed(job, ex.getCode().name(), "provider");
		} catch (RuntimeException ex) {
			log.warn("jira initial sync failed projectId={} type={}", projectId, ex.getClass().getSimpleName());
			markIntegrationFailure(projectId, "JIRA_SYNC_FAILED");
			finalized = true;
			return claims.markFailed(job, "JIRA_SYNC_FAILED", "provider");
		} finally {
			if (!finalized) {
				claims.markFailed(job, "SYNC_JOB_ABORTED", "finalize");
			}
		}
	}

	private void markIntegrationFailure(UUID projectId, String code) {
		writes.executeWithoutResult(status -> {
			JiraIntegration row = integrations.findByProject_Id(projectId).orElse(null);
			if (row != null) {
				row.setConsecutiveFailures(row.getConsecutiveFailures() == null ? 1 : row.getConsecutiveFailures() + 1);
				row.setLastErrorCode(code);
				row.setLastSyncedAt(LocalDateTime.now());
				integrations.save(row);
			}
		});
	}

	private SyncJobLog beginJob(UUID projectId) {
		return claims.tryClaim("JIRA", projectId, SyncJobType.INITIAL).orElse(null);
	}

	private SyncJobLog alreadyRunning(UUID projectId) {
		SyncJobLog job = new SyncJobLog();
		job.setTargetSystem("JIRA");
		job.setTargetId(projectId);
		job.setJobType(SyncJobType.INITIAL);
		job.setStatus(SyncJobStatus.FAILED);
		job.setErrorCategory("JIRA_SYNC_ALREADY_RUNNING");
		job.setFailureStage("claim");
		job.setStartedAt(LocalDateTime.now());
		job.setCompletedAt(LocalDateTime.now());
		job.setItemsProcessed(0);
		job.setItemsFailed(0);
		return writes.execute(status -> syncJobs.save(job));
	}
}
