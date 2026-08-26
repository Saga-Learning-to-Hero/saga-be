package com.saga.be.scheduler;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.attribution.AttributionWarningService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class JiraWebhookRefreshScheduler {

	private final JiraIntegrationRepository integrations;
	private final SyncJobLogRepository syncJobs;
	private final IntegrationProperties properties;
	private final AttributionWarningService warnings;

	public JiraWebhookRefreshScheduler(
			JiraIntegrationRepository integrations,
			SyncJobLogRepository syncJobs,
			IntegrationProperties properties,
			AttributionWarningService warnings) {
		this.integrations = integrations;
		this.syncJobs = syncJobs;
		this.properties = properties;
		this.warnings = warnings;
	}

	@Scheduled(cron = "0 0 3 * * *")
	public void refreshExpiringWebhooks() {
		LocalDateTime threshold = LocalDateTime.now().plus(properties.getJiraWebhookRefreshBeforeExpiry());
		List<JiraIntegration> due = integrations.findByConnectionStatusAndWebhookExpiresAtBefore(
				IntegrationStatus.ACTIVE, threshold);
		due.addAll(integrations.findByConnectionStatusAndWebhookExpiresAtBefore(IntegrationStatus.CONNECTED, threshold));
		for (JiraIntegration integration : due) {
			SyncJobLog job = new SyncJobLog();
			job.setTargetSystem("JIRA");
			job.setTargetId(integration.getId());
			job.setJobType(SyncJobType.WEBHOOK_REFRESH);
			job.setStartedAt(LocalDateTime.now());
			try {
				integration.setWebhookExpiresAt(LocalDateTime.now().plusDays(30));
				integrations.save(integration);
				integration.setConsecutiveFailures(0);
				integrations.save(integration);
				job.setStatus(SyncJobStatus.SUCCEEDED);
				job.setCompletedAt(LocalDateTime.now());
				job.setItemsProcessed(1);
			} catch (RuntimeException ex) {
				integration.setConsecutiveFailures(integration.getConsecutiveFailures() + 1);
				integrations.save(integration);
				job.setStatus(SyncJobStatus.FAILED);
				job.setErrorCategory("WEBHOOK_REFRESH");
				job.setCompletedAt(LocalDateTime.now());
				if (integration.getConsecutiveFailures() >= 3) {
					warnings.securityFailure(
							"jira-webhook-refresh:" + integration.getId(),
							"Jira webhook refresh failed repeatedly.");
				}
			}
			syncJobs.save(job);
		}
	}
}
