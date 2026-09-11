package com.saga.be.service.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraDynamicWebhookClient;
import com.saga.be.integration.jira.JiraDynamicWebhookClient.RegisteredWebhook;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.repository.JiraIntegrationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class JiraDynamicWebhookServiceTest {

	@Mock
	private JiraIntegrationRepository integrations;
	@Mock
	private JiraDynamicWebhookClient client;
	@Mock
	private JiraTeamTokenService tokens;
	@Mock
	private PlatformTransactionManager transactionManager;

	private IntegrationProperties properties;
	private JiraDynamicWebhookService service;
	private UUID projectId;
	private JiraIntegration integration;

	@BeforeEach
	void setUp() {
		properties = new IntegrationProperties();
		properties.getJira().setWebhookUrl("https://saga-be-production.up.railway.app/api/webhooks/jira");
		org.mockito.Mockito.lenient()
				.when(transactionManager.getTransaction(any(TransactionDefinition.class)))
				.thenReturn(new SimpleTransactionStatus());
		service = new JiraDynamicWebhookService(integrations, client, tokens, properties, transactionManager);
		projectId = UUID.randomUUID();
		Project project = new Project();
		project.setId(projectId);
		integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setProject(project);
		integration.setCloudId("cloud-1");
		integration.setProjectKey("SAGA");
		integration.setJiraProjectId("10000");
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
	}

	@Test
	void ensureRegistered_registersOneWebhookWithIssueAndSprintEvents() {
		when(integrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		RegisteredWebhook created = new RegisteredWebhook(
				42L,
				"https://saga-be-production.up.railway.app/api/webhooks/jira",
				"project = \"SAGA\"",
				"2019-06-01T12:42:30.000+0000",
				JiraDynamicWebhookService.REQUIRED_EVENTS);
		when(client.listWebhooks("token", "cloud-1")).thenReturn(List.of()).thenReturn(List.of(created));
		when(client.registerWebhook(
						eq("token"),
						eq("cloud-1"),
						eq("https://saga-be-production.up.railway.app/api/webhooks/jira"),
						eq("project = \"SAGA\""),
						eq(JiraDynamicWebhookService.REQUIRED_EVENTS)))
				.thenReturn(42L);
		when(integrations.findById(integration.getId())).thenReturn(Optional.of(integration));
		when(integrations.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.ensureRegistered(projectId, "token");

		ArgumentCaptor<List<String>> events = ArgumentCaptor.forClass(List.class);
		verify(client)
				.registerWebhook(
						eq("token"),
						eq("cloud-1"),
						eq("https://saga-be-production.up.railway.app/api/webhooks/jira"),
						eq("project = \"SAGA\""),
						events.capture());
		assertThat(events.getValue()).contains("jira:issue_created", "sprint_created", "sprint_deleted");
		assertThat(integration.getWebhookId()).isEqualTo("42");
		assertThat(integration.getWebhookExpiresAt()).isNotNull();
		assertThat(integration.getLastErrorCode()).isNull();
	}

	@Test
	void ensureRegistered_reusesExistingWebhookWithoutCreatingDuplicate() {
		when(integrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		RegisteredWebhook existing = new RegisteredWebhook(
				99L,
				"https://saga-be-production.up.railway.app/api/webhooks/jira",
				"project = \"SAGA\"",
				"2019-06-01T12:42:30.000+0000",
				JiraDynamicWebhookService.REQUIRED_EVENTS);
		when(client.listWebhooks("token", "cloud-1")).thenReturn(List.of(existing));
		when(client.refreshWebhooks("token", "cloud-1", List.of(99L))).thenReturn("2020-07-01T00:00:00.000+0000");
		when(integrations.findById(integration.getId())).thenReturn(Optional.of(integration));
		when(integrations.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.ensureRegistered(projectId, "token");

		verify(client, never()).registerWebhook(any(), any(), any(), any(), anyList());
		verify(client).refreshWebhooks("token", "cloud-1", List.of(99L));
		assertThat(integration.getWebhookId()).isEqualTo("99");
	}

	@Test
	void ensureRegistered_providerFailureSetsLastErrorWithoutClearingActive() {
		when(integrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(client.listWebhooks("token", "cloud-1"))
				.thenThrow(new com.saga.be.exception.IntegrationException(
						IntegrationErrorCode.JIRA_WEBHOOK_REGISTER_FAILED,
						org.springframework.http.HttpStatus.BAD_GATEWAY,
						"fail"));
		when(integrations.findById(integration.getId())).thenReturn(Optional.of(integration));
		when(integrations.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.ensureRegistered(projectId, "token");

		assertThat(integration.getConnectionStatus()).isEqualTo(IntegrationStatus.ACTIVE);
		assertThat(integration.getLastErrorCode())
				.isEqualTo(IntegrationErrorCode.JIRA_WEBHOOK_REGISTER_FAILED.name());
		verify(client, never()).registerWebhook(any(), any(), any(), any(), anyList());
	}

	@Test
	void refreshDue_updatesExpiryFromProvider() {
		integration.setWebhookId("55");
		when(tokens.accessToken(integration)).thenReturn("token");
		when(client.refreshWebhooks("token", "cloud-1", List.of(55L))).thenReturn("2021-01-01T00:00:00.000+0000");
		when(integrations.findById(integration.getId())).thenReturn(Optional.of(integration));
		when(integrations.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.refreshDue(integration);

		assertThat(integration.getWebhookExpiresAt()).isNotNull();
		assertThat(integration.getWebhookExpiresAt().getYear()).isEqualTo(2021);
	}

	@Test
	void refreshDue_providerFailureDoesNotBumpLocalExpiry() {
		integration.setWebhookId("55");
		LocalDateTime before = LocalDateTime.of(2020, 6, 1, 0, 0);
		integration.setWebhookExpiresAt(before);
		when(tokens.accessToken(integration)).thenReturn("token");
		when(client.refreshWebhooks("token", "cloud-1", List.of(55L)))
				.thenThrow(new com.saga.be.exception.IntegrationException(
						IntegrationErrorCode.JIRA_WEBHOOK_REFRESH_FAILED,
						org.springframework.http.HttpStatus.BAD_GATEWAY,
						"fail"));

		org.junit.jupiter.api.Assertions.assertThrows(
				com.saga.be.exception.IntegrationException.class, () -> service.refreshDue(integration));

		assertThat(integration.getWebhookExpiresAt()).isEqualTo(before);
		verify(integrations, never()).save(any());
	}

	@Test
	void unregisterIfPresent_deletesProviderWebhookAndClearsFields() {
		integration.setWebhookId("77");
		when(tokens.accessToken(integration)).thenReturn("token");
		when(integrations.findById(integration.getId())).thenReturn(Optional.of(integration));
		when(integrations.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.unregisterIfPresent(integration, null);

		verify(client).deleteWebhooks("token", "cloud-1", List.of(77L));
		assertThat(integration.getWebhookId()).isNull();
		assertThat(integration.getWebhookExpiresAt()).isNull();
	}

	@Test
	void jqlForProject_escapesKey() {
		assertThat(JiraDynamicWebhookService.jqlForProject("SAGA")).isEqualTo("project = \"SAGA\"");
	}
}
