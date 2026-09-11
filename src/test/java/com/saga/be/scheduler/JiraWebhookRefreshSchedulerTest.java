package com.saga.be.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.attribution.AttributionWarningService;
import com.saga.be.service.jira.JiraDynamicWebhookService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class JiraWebhookRefreshSchedulerTest {

	@Test
	void refreshesNearExpiryWebhookViaProvider() {
		JiraIntegrationRepository integrations = Mockito.mock(JiraIntegrationRepository.class);
		SyncJobLogRepository jobs = Mockito.mock(SyncJobLogRepository.class);
		AttributionWarningService warnings = Mockito.mock(AttributionWarningService.class);
		JiraDynamicWebhookService webhooks = Mockito.mock(JiraDynamicWebhookService.class);
		PlatformTransactionManager tx = Mockito.mock(PlatformTransactionManager.class);
		IntegrationProperties properties = new IntegrationProperties();
		properties.setJiraWebhookRefreshBeforeExpiry(Duration.ofDays(7));
		JiraIntegration integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setWebhookId("100");
		integration.setWebhookExpiresAt(LocalDateTime.now().plusDays(3));
		integration.setConsecutiveFailures(0);
		when(integrations.findByConnectionStatusAndWebhookExpiresAtBefore(any(), any()))
				.thenReturn(new ArrayList<>(List.of(integration)))
				.thenReturn(new ArrayList<>());
		JiraWebhookRefreshScheduler scheduler =
				new JiraWebhookRefreshScheduler(integrations, jobs, properties, warnings, webhooks, tx);
		scheduler.refreshExpiringWebhooks();
		verify(webhooks).refreshDue(integration);
		verify(jobs).save(any());
		verify(warnings, never()).securityFailure(any(), any());
	}

	@Test
	void failedRefreshDoesNotFakeLocalSuccess() {
		JiraIntegrationRepository integrations = Mockito.mock(JiraIntegrationRepository.class);
		SyncJobLogRepository jobs = Mockito.mock(SyncJobLogRepository.class);
		AttributionWarningService warnings = Mockito.mock(AttributionWarningService.class);
		JiraDynamicWebhookService webhooks = Mockito.mock(JiraDynamicWebhookService.class);
		PlatformTransactionManager tx = Mockito.mock(PlatformTransactionManager.class);
		when(tx.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
		IntegrationProperties properties = new IntegrationProperties();
		JiraIntegration integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setWebhookId("100");
		integration.setWebhookExpiresAt(LocalDateTime.now().plusDays(1));
		integration.setConsecutiveFailures(2);
		LocalDateTime before = integration.getWebhookExpiresAt();
		when(integrations.findByConnectionStatusAndWebhookExpiresAtBefore(any(), any()))
				.thenReturn(new ArrayList<>(List.of(integration)))
				.thenReturn(new ArrayList<>());
		when(integrations.findById(integration.getId())).thenReturn(Optional.of(integration));
		when(integrations.save(any())).thenAnswer(inv -> inv.getArgument(0));
		doThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_WEBHOOK_REFRESH_FAILED, HttpStatus.BAD_GATEWAY, "fail"))
				.when(webhooks)
				.refreshDue(integration);
		JiraWebhookRefreshScheduler scheduler =
				new JiraWebhookRefreshScheduler(integrations, jobs, properties, warnings, webhooks, tx);
		scheduler.refreshExpiringWebhooks();
		assertEquals(before, integration.getWebhookExpiresAt());
		assertEquals(3, integration.getConsecutiveFailures());
		assertEquals(IntegrationErrorCode.JIRA_WEBHOOK_REFRESH_FAILED.name(), integration.getLastErrorCode());
		verify(warnings).securityFailure(any(), any());
	}
}
