package com.saga.be.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
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

	@Test
	void selectsActiveIntegrationWithNullWebhookId_andRegistersItViaEnsureRegistered() {
		JiraIntegrationRepository integrations = Mockito.mock(JiraIntegrationRepository.class);
		SyncJobLogRepository jobs = Mockito.mock(SyncJobLogRepository.class);
		AttributionWarningService warnings = Mockito.mock(AttributionWarningService.class);
		JiraDynamicWebhookService webhooks = Mockito.mock(JiraDynamicWebhookService.class);
		PlatformTransactionManager tx = Mockito.mock(PlatformTransactionManager.class);
		IntegrationProperties properties = new IntegrationProperties();

		UUID projectId = UUID.randomUUID();
		Project project = new Project();
		project.setId(projectId);
		JiraIntegration integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setProject(project);
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setWebhookId(null);
		integration.setWebhookExpiresAt(null);

		when(integrations.findByConnectionStatusAndWebhookExpiresAtBefore(any(), any())).thenReturn(new ArrayList<>());
		when(integrations.findByConnectionStatusAndWebhookMissing(IntegrationStatus.ACTIVE))
				.thenReturn(new ArrayList<>(List.of(integration)));
		// ensureRegistered "succeeded": simulate the persisted state it would have set.
		Mockito.doAnswer(inv -> {
					integration.setWebhookId("42");
					integration.setWebhookExpiresAt(LocalDateTime.now().plusDays(30));
					return null;
				})
				.when(webhooks)
				.ensureRegistered(integration.getId(), null);
		when(integrations.findById(integration.getId())).thenReturn(Optional.of(integration));

		JiraWebhookRefreshScheduler scheduler =
				new JiraWebhookRefreshScheduler(integrations, jobs, properties, warnings, webhooks, tx);
		scheduler.refreshExpiringWebhooks();

		verify(webhooks).ensureRegistered(integration.getId(), null);
		verify(webhooks, never()).refreshDue(any());
		assertThat(integration.getWebhookId()).isEqualTo("42");
	}

	@Test
	void selectsActiveIntegrationWithNullWebhookExpiresAt() {
		JiraIntegrationRepository integrations = Mockito.mock(JiraIntegrationRepository.class);
		SyncJobLogRepository jobs = Mockito.mock(SyncJobLogRepository.class);
		AttributionWarningService warnings = Mockito.mock(AttributionWarningService.class);
		JiraDynamicWebhookService webhooks = Mockito.mock(JiraDynamicWebhookService.class);
		PlatformTransactionManager tx = Mockito.mock(PlatformTransactionManager.class);
		IntegrationProperties properties = new IntegrationProperties();

		UUID projectId = UUID.randomUUID();
		Project project = new Project();
		project.setId(projectId);
		JiraIntegration integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setProject(project);
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		// webhookId present but expiresAt somehow never got persisted (defensive edge case).
		integration.setWebhookId("77");
		integration.setWebhookExpiresAt(null);

		when(integrations.findByConnectionStatusAndWebhookExpiresAtBefore(any(), any())).thenReturn(new ArrayList<>());
		when(integrations.findByConnectionStatusAndWebhookMissing(IntegrationStatus.ACTIVE))
				.thenReturn(new ArrayList<>(List.of(integration)));

		JiraWebhookRefreshScheduler scheduler =
				new JiraWebhookRefreshScheduler(integrations, jobs, properties, warnings, webhooks, tx);
		scheduler.refreshExpiringWebhooks();

		// webhookId is present, so the scheduler takes the refreshDue path, not ensureRegistered.
		verify(webhooks).refreshDue(integration);
		verify(webhooks, never()).ensureRegistered(any(), any());
	}

	@Test
	void selectsExpiringWebhook_viaExistingExpiryQuery() {
		JiraIntegrationRepository integrations = Mockito.mock(JiraIntegrationRepository.class);
		SyncJobLogRepository jobs = Mockito.mock(SyncJobLogRepository.class);
		AttributionWarningService warnings = Mockito.mock(AttributionWarningService.class);
		JiraDynamicWebhookService webhooks = Mockito.mock(JiraDynamicWebhookService.class);
		PlatformTransactionManager tx = Mockito.mock(PlatformTransactionManager.class);
		IntegrationProperties properties = new IntegrationProperties();
		properties.setJiraWebhookRefreshBeforeExpiry(Duration.ofDays(7));

		JiraIntegration expiring = new JiraIntegration();
		expiring.setId(UUID.randomUUID());
		expiring.setConnectionStatus(IntegrationStatus.ACTIVE);
		expiring.setWebhookId("100");
		expiring.setWebhookExpiresAt(LocalDateTime.now().plusDays(3));

		when(integrations.findByConnectionStatusAndWebhookExpiresAtBefore(any(), any()))
				.thenReturn(new ArrayList<>(List.of(expiring)))
				.thenReturn(new ArrayList<>());
		when(integrations.findByConnectionStatusAndWebhookMissing(IntegrationStatus.ACTIVE)).thenReturn(new ArrayList<>());

		JiraWebhookRefreshScheduler scheduler =
				new JiraWebhookRefreshScheduler(integrations, jobs, properties, warnings, webhooks, tx);
		scheduler.refreshExpiringWebhooks();

		verify(webhooks).refreshDue(expiring);
	}

	@Test
	void ignoresHealthyNonExpiringWebhook() {
		JiraIntegrationRepository integrations = Mockito.mock(JiraIntegrationRepository.class);
		SyncJobLogRepository jobs = Mockito.mock(SyncJobLogRepository.class);
		AttributionWarningService warnings = Mockito.mock(AttributionWarningService.class);
		JiraDynamicWebhookService webhooks = Mockito.mock(JiraDynamicWebhookService.class);
		PlatformTransactionManager tx = Mockito.mock(PlatformTransactionManager.class);
		IntegrationProperties properties = new IntegrationProperties();
		properties.setJiraWebhookRefreshBeforeExpiry(Duration.ofDays(7));

		// Healthy: webhookId present, expiresAt far in the future -- excluded by BOTH queries
		// (not < threshold, and neither webhookId nor webhookExpiresAt is null).
		when(integrations.findByConnectionStatusAndWebhookExpiresAtBefore(any(), any())).thenReturn(new ArrayList<>());
		when(integrations.findByConnectionStatusAndWebhookMissing(IntegrationStatus.ACTIVE)).thenReturn(new ArrayList<>());

		JiraWebhookRefreshScheduler scheduler =
				new JiraWebhookRefreshScheduler(integrations, jobs, properties, warnings, webhooks, tx);
		scheduler.refreshExpiringWebhooks();

		verify(webhooks, never()).refreshDue(any());
		verify(webhooks, never()).ensureRegistered(any(), any());
		verify(jobs, never()).save(any());
	}

	@Test
	void revokedIntegrationWithMissingWebhook_isNeverARepairCandidate() {
		// The repair query itself is scoped to IntegrationStatus.ACTIVE only -- a REVOKED
		// integration's null webhook_id/expires_at must never surface here regardless of how many
		// such rows exist, since findByConnectionStatusAndWebhookMissing(ACTIVE) structurally
		// excludes them. This test documents/locks in that the scheduler only ever queries ACTIVE.
		JiraIntegrationRepository integrations = Mockito.mock(JiraIntegrationRepository.class);
		SyncJobLogRepository jobs = Mockito.mock(SyncJobLogRepository.class);
		AttributionWarningService warnings = Mockito.mock(AttributionWarningService.class);
		JiraDynamicWebhookService webhooks = Mockito.mock(JiraDynamicWebhookService.class);
		PlatformTransactionManager tx = Mockito.mock(PlatformTransactionManager.class);
		IntegrationProperties properties = new IntegrationProperties();

		when(integrations.findByConnectionStatusAndWebhookExpiresAtBefore(any(), any())).thenReturn(new ArrayList<>());
		when(integrations.findByConnectionStatusAndWebhookMissing(IntegrationStatus.ACTIVE)).thenReturn(new ArrayList<>());

		JiraWebhookRefreshScheduler scheduler =
				new JiraWebhookRefreshScheduler(integrations, jobs, properties, warnings, webhooks, tx);
		scheduler.refreshExpiringWebhooks();

		verify(integrations, never()).findByConnectionStatusAndWebhookMissing(IntegrationStatus.REVOKED);
		verify(webhooks, never()).ensureRegistered(any(), any());
		verify(webhooks, never()).refreshDue(any());
	}
}
