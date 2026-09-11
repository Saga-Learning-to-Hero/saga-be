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
import com.saga.be.integration.jira.JiraOAuthClient.JiraProjectResponse;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.projection.JiraTaskProjectionService;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Complete Jira issue reconciliation for the selected ACTIVE project.
 * Pages via enhanced {@code /rest/api/3/search/jql} until {@code isLast}/empty token.
 * No product backfill cap — SUCCESS only after complete traversal.
 */
@Service
@Profile("!test")
public class JiraTaskSyncService {

	/**
	 * Defensive only against malformed infinite pagination — not a product data ceiling.
	 * Default page size 50 → 10_000 pages ≈ 500_000 issues before FAIL.
	 */
	static final int MAX_ISSUE_PAGES = 10_000;

	/** Overridable in tests only; production always uses {@link #MAX_ISSUE_PAGES}. */
	static int maxIssuePages = MAX_ISSUE_PAGES;

	private static final Logger log = LoggerFactory.getLogger(JiraTaskSyncService.class);

	private final JiraIntegrationRepository integrations;
	private final JiraOAuthClient jira;
	private final JiraTaskProjectionService projection;
	private final SyncJobLogRepository syncJobs;
	private final IntegrationProperties properties;
	private final JiraIntegrationCredentialService credentials;
	private final SyncJobClaimService claims;
	private final TransactionTemplate writes;
	private final ProjectRealtimePublisher realtime;

	public JiraTaskSyncService(
			JiraIntegrationRepository integrations,
			JiraOAuthClient jira,
			JiraTaskProjectionService projection,
			SyncJobLogRepository syncJobs,
			IntegrationProperties properties,
			JiraIntegrationCredentialService credentials,
			SyncJobClaimService claims,
			PlatformTransactionManager transactionManager,
			ProjectRealtimePublisher realtime) {
		this.integrations = integrations;
		this.jira = jira;
		this.projection = projection;
		this.syncJobs = syncJobs;
		this.properties = properties;
		this.credentials = credentials;
		this.claims = claims;
		this.writes = new TransactionTemplate(transactionManager);
		this.realtime = realtime;
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
			probeProjectAccess(accessToken, integration);
			int pageSize = Math.max(1, Math.min(properties.getJiraIssuePageSize(), 100));
			String nextPageToken = null;
			int processed = 0;
			boolean refreshedForUnauthorized = false;
			int pagesFetched = 0;
			boolean exhausted = false;
			while (pagesFetched < maxIssuePages) {
				IssueSearchPage page;
				try {
					page = jira.searchIssues(
							accessToken,
							integration.getCloudId(),
							integration.getProjectKey(),
							nextPageToken,
							pageSize);
				} catch (IntegrationException ex) {
					if (ex.getCode() == IntegrationErrorCode.JIRA_UNAUTHORIZED && !refreshedForUnauthorized) {
						String rejected = accessToken;
						accessToken = credentials.forceRefresh(projectId, rejected);
						refreshedForUnauthorized = true;
						page = jira.searchIssues(
								accessToken,
								integration.getCloudId(),
								integration.getProjectKey(),
								nextPageToken,
								pageSize);
					} else {
						throw ex;
					}
				}
				pagesFetched++;
				List<IssueSummary> issues = page.issues() == null ? List.of() : page.issues();
				if (issues.isEmpty()) {
					exhausted = true;
					break;
				}
				Integer upserted = writes.execute(status ->
						projection.upsertBatch(integration.getProject(), integration.getProjectKey(), issues));
				processed += upserted == null ? 0 : upserted;
				if (page.last() || page.nextPageToken() == null || page.nextPageToken().isBlank()) {
					exhausted = true;
					break;
				}
				nextPageToken = page.nextPageToken();
			}
			if (!exhausted) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_SYNC_INCOMPLETE,
						HttpStatus.BAD_GATEWAY,
						"Jira issue pagination exceeded defensive guard.");
			}
			final int processedCount = processed;
			writes.executeWithoutResult(status -> {
				JiraIntegration row = integrations.findByProject_Id(projectId).orElse(null);
				if (row != null) {
					row.setLastSyncedAt(LocalDateTime.now());
					row.setLastSuccessfulSyncAt(LocalDateTime.now());
					row.setConsecutiveFailures(0);
					row.setLastErrorCode(null);
					integrations.save(row);
				}
				realtime.publish(ProjectRealtimeEventType.SYNC_STATUS_CHANGED, projectId);
				if (processedCount > 0) {
					realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId);
					realtime.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId);
					realtime.publish(ProjectRealtimeEventType.TASK_LINKS_CHANGED, projectId);
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

	/**
	 * Diagnostic-only: distinguishes project permission vs search-API failures in logs.
	 * Does not abort sync — issue search remains the authoritative sync path.
	 */
	private void probeProjectAccess(String accessToken, JiraIntegration integration) {
		String keyOrId = integration.getJiraProjectId() != null && !integration.getJiraProjectId().isBlank()
				? integration.getJiraProjectId()
				: integration.getProjectKey();
		boolean jiraProjectIdPresent =
				integration.getJiraProjectId() != null && !integration.getJiraProjectId().isBlank();
		try {
			JiraProjectResponse project = jira.getProject(accessToken, integration.getCloudId(), keyOrId);
			if (project == null || project.id() == null) {
				log.warn(
						"jira operation=getProjectProbeFailed errorCode=JIRA_PROJECT_NOT_ACCESSIBLE cloudIdPresent=true jiraProjectIdPresent={} projectKey={} cause=empty_body",
						jiraProjectIdPresent,
						integration.getProjectKey());
				return;
			}
			log.info(
					"jira operation=getProject httpStatus=200 errorCode=none cloudIdPresent=true jiraProjectIdPresent={} projectKey={}",
					jiraProjectIdPresent,
					project.key() != null ? project.key() : integration.getProjectKey());
		} catch (IntegrationException ex) {
			log.warn(
					"jira operation=getProjectProbeFailed errorCode={} cloudIdPresent=true jiraProjectIdPresent={} projectKey={}",
					ex.getCode(),
					jiraProjectIdPresent,
					integration.getProjectKey());
		} catch (RuntimeException ex) {
			log.warn(
					"jira operation=getProjectProbeFailed errorCode=UNEXPECTED type={} cloudIdPresent=true jiraProjectIdPresent={} projectKey={}",
					ex.getClass().getSimpleName(),
					jiraProjectIdPresent,
					integration.getProjectKey());
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
