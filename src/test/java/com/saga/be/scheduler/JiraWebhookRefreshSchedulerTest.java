package com.saga.be.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.attribution.AttributionWarningService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JiraWebhookRefreshSchedulerTest {

	@Test
	void refreshesNearExpiryWebhook() {
		JiraIntegrationRepository integrations = Mockito.mock(JiraIntegrationRepository.class);
		SyncJobLogRepository jobs = Mockito.mock(SyncJobLogRepository.class);
		AttributionWarningService warnings = Mockito.mock(AttributionWarningService.class);
		IntegrationProperties properties = new IntegrationProperties();
		properties.setJiraWebhookRefreshBeforeExpiry(Duration.ofDays(7));
		JiraIntegration integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setWebhookExpiresAt(LocalDateTime.now().plusDays(3));
		integration.setConsecutiveFailures(0);
		when(integrations.findByConnectionStatusAndWebhookExpiresAtBefore(any(), any()))
				.thenReturn(new ArrayList<>(List.of(integration)))
				.thenReturn(new ArrayList<>());
		when(integrations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		JiraWebhookRefreshScheduler scheduler =
				new JiraWebhookRefreshScheduler(integrations, jobs, properties, warnings);
		scheduler.refreshExpiringWebhooks();
		verify(jobs).save(any());
		verify(warnings, never()).securityFailure(any(), any());
	}

	@Test
	void failedRefreshCreatesWarningAfterRetries() {
		JiraIntegrationRepository integrations = Mockito.mock(JiraIntegrationRepository.class);
		SyncJobLogRepository jobs = Mockito.mock(SyncJobLogRepository.class);
		AttributionWarningService warnings = Mockito.mock(AttributionWarningService.class);
		IntegrationProperties properties = new IntegrationProperties();
		JiraIntegration integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setWebhookExpiresAt(LocalDateTime.now().plusDays(1));
		integration.setConsecutiveFailures(2);
		when(integrations.findByConnectionStatusAndWebhookExpiresAtBefore(any(), any()))
				.thenReturn(new ArrayList<>(List.of(integration)))
				.thenReturn(new ArrayList<>());
		when(integrations.save(any())).thenThrow(new RuntimeException("jira down")).thenReturn(integration);
		JiraWebhookRefreshScheduler scheduler =
				new JiraWebhookRefreshScheduler(integrations, jobs, properties, warnings);
		scheduler.refreshExpiringWebhooks();
		assertEquals(3, integration.getConsecutiveFailures());
		verify(warnings).securityFailure(any(), any());
	}
}
