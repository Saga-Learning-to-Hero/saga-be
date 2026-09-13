package com.saga.be.scheduler;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.attribution.AttributionWarningService;
import com.saga.be.service.jira.JiraDynamicWebhookService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("!test")
public class JiraWebhookRefreshScheduler {

	private final JiraIntegrationRepository integrations;
	private final SyncJobLogRepository syncJobs;
	private final IntegrationProperties properties;
	private final AttributionWarningService warnings;
	private final JiraDynamicWebhookService webhooks;
	private final TransactionTemplate writes;

	public JiraWebhookRefreshScheduler(
			JiraIntegrationRepository integrations,
			SyncJobLogRepository syncJobs,
			IntegrationProperties properties,
			AttributionWarningService warnings,
			JiraDynamicWebhookService webhooks,
			org.springframework.transaction.PlatformTransactionManager transactionManager) {
		this.integrations = integrations;
		this.syncJobs = syncJobs;
		this.properties = properties;
		this.warnings = warnings;
		this.webhooks = webhooks;
		this.writes = new TransactionTemplate(transactionManager);
	}

	@Scheduled(cron = "0 0 3 * * *")
	public void refreshExpiringWebhooks() {
		LocalDateTime threshold = LocalDateTime.now().plus(properties.getJiraWebhookRefreshBeforeExpiry());
		List<JiraIntegration> due = new ArrayList<>(integrations.findByConnectionStatusAndWebhookExpiresAtBefore(
				IntegrationStatus.ACTIVE, threshold));
		due.addAll(integrations.findByConnectionStatusAndWebhookExpiresAtBefore(IntegrationStatus.CONNECTED, threshold));
		// Repair candidates: ACTIVE integrations that never completed registration at all
		// (webhook_id/webhook_expires_at still NULL) -- distinct from the expiry-based set above,
		// since a NULL column never satisfies "< threshold" and would otherwise never be selected
		// for repair. REVOKED integrations are excluded by construction (query is scoped to ACTIVE).
		List<JiraIntegration> missing = integrations.findByConnectionStatusAndWebhookMissing(IntegrationStatus.ACTIVE);
		for (JiraIntegration candidate : missing) {
			boolean alreadyQueued = due.stream().anyMatch(existing -> existing.getId().equals(candidate.getId()));
			if (!alreadyQueued) {
				due.add(candidate);
			}
		}
		for (JiraIntegration integration : due) {
			SyncJobLog job = new SyncJobLog();
			job.setTargetSystem("JIRA");
			job.setTargetId(integration.getId());
			job.setJobType(SyncJobType.WEBHOOK_REFRESH);
			job.setStartedAt(LocalDateTime.now());
			boolean missingWebhookId = integration.getWebhookId() == null || integration.getWebhookId().isBlank();
			try {
				if (missingWebhookId) {
					// ensureRegistered handles both "register fresh" and "reuse an existing matching
					// webhook" without a second, duplicate provider call path -- but it swallows its
					// own failures internally (persists lastErrorCode, never rethrows: see its
					// javadoc/contract), so success here must be verified from persisted state
					// afterward rather than assumed from "no exception was thrown".
					webhooks.ensureRegistered(integration.getProject().getId(), null);
					JiraIntegration after = integrations.findById(integration.getId()).orElse(integration);
					if (after.getWebhookId() == null || after.getWebhookId().isBlank()) {
						throw new IntegrationException(
								IntegrationErrorCode.JIRA_WEBHOOK_REGISTER_FAILED,
								org.springframework.http.HttpStatus.BAD_GATEWAY,
								"Jira webhook registration did not produce a webhook id.");
					}
				} else {
					// Provider HTTP outside JDBC; persist only after provider returns expirationDate.
					webhooks.refreshDue(integration);
				}
				job.setStatus(SyncJobStatus.SUCCEEDED);
				job.setCompletedAt(LocalDateTime.now());
				job.setItemsProcessed(1);
			} catch (RuntimeException ex) {
				IntegrationErrorCode failureCode = missingWebhookId
						? IntegrationErrorCode.JIRA_WEBHOOK_REGISTER_FAILED
						: IntegrationErrorCode.JIRA_WEBHOOK_REFRESH_FAILED;
				writes.executeWithoutResult(status -> {
					JiraIntegration row = integrations.findById(integration.getId()).orElse(integration);
					row.setConsecutiveFailures(row.getConsecutiveFailures() == null ? 1 : row.getConsecutiveFailures() + 1);
					row.setLastErrorCode(failureCode.name());
					integrations.save(row);
					if (row.getConsecutiveFailures() >= 3) {
						warnings.securityFailure(
								"jira-webhook-refresh:" + row.getId(),
								"Jira webhook refresh failed repeatedly.");
					}
				});
				job.setStatus(SyncJobStatus.FAILED);
				job.setErrorCategory(
						ex instanceof IntegrationException ie ? ie.getCode().name() : "WEBHOOK_REFRESH");
				job.setCompletedAt(LocalDateTime.now());
			}
			syncJobs.save(job);
		}
	}
}
