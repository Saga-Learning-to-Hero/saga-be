package com.saga.be.service.sync;

import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSearchPage;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.service.projection.JiraTaskProjectionService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-reads one sprint's issues from Jira and upserts them. Used after a sprint starts or closes:
 * completing a sprint moves its unfinished issues to the next sprint without any issue_updated
 * webhook, so without this SAGA would keep them on the closed sprint until someone edits them.
 * Provider HTTP runs outside any transaction; each page is upserted in its own short one.
 */
@Service
@Profile("!test")
public class JiraSprintIssueRefresher {

	static final int PAGE_SIZE = 100;
	static final int MAX_PAGES = 10;
	private static final Logger log = LoggerFactory.getLogger(JiraSprintIssueRefresher.class);

	private final JiraIntegrationRepository integrations;
	private final JiraTeamTokenService tokens;
	private final JiraIssueWriteClient jiraFields;
	private final JiraOAuthClient jira;
	private final JiraTaskProjectionService projection;
	private final ProjectRealtimePublisher realtime;
	private final TransactionTemplate writes;

	public JiraSprintIssueRefresher(
			JiraIntegrationRepository integrations,
			JiraTeamTokenService tokens,
			JiraIssueWriteClient jiraFields,
			JiraOAuthClient jira,
			JiraTaskProjectionService projection,
			ProjectRealtimePublisher realtime,
			PlatformTransactionManager transactionManager) {
		this.integrations = integrations;
		this.tokens = tokens;
		this.jiraFields = jiraFields;
		this.jira = jira;
		this.projection = projection;
		this.realtime = realtime;
		this.writes = new TransactionTemplate(transactionManager);
	}

	@Async("jiraSprintRefreshExecutor")
	public void refreshAsync(UUID jiraIntegrationId, String sprintExternalId) {
		try {
			int refreshed = refresh(jiraIntegrationId, sprintExternalId);
			log.info("jira sprint refresh done integrationId={} sprintId={} issues={}", jiraIntegrationId, sprintExternalId, refreshed);
		} catch (RuntimeException ex) {
			log.warn("jira sprint refresh failed integrationId={} sprintId={} type={}", jiraIntegrationId, sprintExternalId,
					ex.getClass().getSimpleName());
		}
	}

	/** Upserts every issue that is or was in the sprint; returns how many were upserted. */
	public int refresh(UUID jiraIntegrationId, String sprintExternalId) {
		if (sprintExternalId == null || !sprintExternalId.trim().matches("\\d{1,18}")) {
			return 0;
		}
		JiraIntegration integration = integrations.findFetchedById(jiraIntegrationId).orElse(null);
		if (integration == null
				|| integration.getConnectionStatus() != IntegrationStatus.ACTIVE
				|| integration.getProjectKey() == null
				|| integration.getCloudId() == null) {
			return 0;
		}
		String access = tokens.accessToken(integration);
		String storyField = jiraFields.resolveStoryPointsFieldId(access, integration.getCloudId());
		String sprintField = jiraFields.resolveSprintFieldId(access, integration.getCloudId());
		String startField = jiraFields.resolveStartDateFieldId(access, integration.getCloudId());
		int upserted = 0;
		String nextPageToken = null;
		for (int page = 0; page < MAX_PAGES; page++) {
			IssueSearchPage result = jira.searchIssues(access, integration.getCloudId(), integration.getProjectKey(),
					nextPageToken, PAGE_SIZE, storyField, sprintField, startField, sprintExternalId.trim());
			List<IssueSummary> issues = result.issues() == null ? List.of() : result.issues();
			if (!issues.isEmpty()) {
				Integer applied = writes.execute(status -> projection.upsertBatch(integration, integration.getProjectKey(), issues));
				upserted += applied == null ? 0 : applied;
			}
			if (result.last() || result.nextPageToken() == null || result.nextPageToken().isBlank() || issues.isEmpty()) {
				break;
			}
			nextPageToken = result.nextPageToken();
		}
		UUID projectId = integration.getProject() == null ? null : integration.getProject().getId();
		if (projectId != null && upserted > 0) {
			writes.executeWithoutResult(status -> {
				realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId);
				realtime.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId);
			});
		}
		return upserted;
	}
}
