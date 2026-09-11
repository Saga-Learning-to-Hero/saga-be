package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.WebhookReceiptStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.integration.WebhookReceipt;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.WebhookReceiptRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProviderWebhookProjectionServiceTest {

	@Mock
	private GitRepoRepository repos;
	@Mock
	private JiraIntegrationRepository jiraIntegrations;
	@Mock
	private GitCommitProjectionService commits;
	@Mock
	private JiraTaskProjectionService tasks;
	@Mock
	private WebhookReceiptRepository receiptRepository;
	@Mock
	private com.saga.be.realtime.ProjectRealtimePublisher realtime;

	private ProviderWebhookProjectionService service;

	@BeforeEach
	void setUp() {
		service = new ProviderWebhookProjectionService(
				new ObjectMapper(), repos, jiraIntegrations, commits, tasks, receiptRepository, realtime);
	}

	@Test
	void githubPush_projectsSelectedRepoOnly() {
		WebhookReceipt receipt = receipt(IntegrationProvider.GITHUB);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		GitRepo repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setProject(project);
		repo.setRepositoryId(55L);
		when(repos.findFetchedActiveByProviderAndRepositoryId(GitProvider.GITHUB, 55L, IntegrationStatus.ACTIVE))
				.thenReturn(List.of(repo));
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(commits.upsertBatchDetailed(eq(repo), any()))
				.thenReturn(new GitCommitProjectionService.UpsertOutcome(1, 1));

		String payload =
				"""
				{"ref":"refs/heads/main","repository":{"id":55},"commits":[{"id":"deadbeef","message":"SAGA-1","timestamp":"2026-01-01T00:00:00Z","author":{"username":"alice"}}]}
				""";
		service.projectGithub(receipt, "push", payload);

		ArgumentCaptor<List<GitCommitProjectionService.CommitDraft>> captor = ArgumentCaptor.forClass(List.class);
		verify(commits).upsertBatchDetailed(eq(repo), captor.capture());
		assertThat(captor.getValue()).hasSize(1);
		assertThat(captor.getValue().getFirst().sha()).isEqualTo("deadbeef");
		assertThat(receipt.getReceiptStatus()).isEqualTo(WebhookReceiptStatus.PROCESSED);
		verify(realtime).publish(com.saga.be.realtime.ProjectRealtimeEventType.COMMITS_CHANGED, project.getId());
		verify(realtime).publish(com.saga.be.realtime.ProjectRealtimeEventType.TASK_LINKS_CHANGED, project.getId());
	}

	@Test
	void githubPush_unselectedRepository_skipsCommitProjection() {
		WebhookReceipt receipt = receipt(IntegrationProvider.GITHUB);
		when(repos.findFetchedActiveByProviderAndRepositoryId(GitProvider.GITHUB, 99L, IntegrationStatus.ACTIVE))
				.thenReturn(List.of());
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		String payload =
				"""
				{"ref":"refs/heads/main","repository":{"id":99},"commits":[{"id":"deadbeef","message":"SAGA-1"}]}
				""";
		service.projectGithub(receipt, "push", payload);

		verify(commits, org.mockito.Mockito.never()).upsertBatchDetailed(any(), any());
		assertThat(receipt.getReceiptStatus()).isEqualTo(WebhookReceiptStatus.PROCESSED);
	}

	@Test
	void jiraIssueUpdated_upsertsTaskForMatchingIntegration() {
		WebhookReceipt receipt = receipt(IntegrationProvider.JIRA);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		when(jiraIntegrations.findFetchedActiveByJiraProject(IntegrationStatus.ACTIVE, "10000", "SAGA"))
				.thenReturn(List.of(integration));
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(tasks.upsertBatch(eq(project), eq("SAGA"), any())).thenReturn(1);

		String payload =
				"""
				{"webhookEvent":"jira:issue_updated","issue":{"id":"200","key":"SAGA-9","fields":{"summary":"Auth","status":{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}},"issuetype":{"name":"Story"},"assignee":{"accountId":"acct-1"},"project":{"id":"10000","key":"SAGA"},"updated":"2026-01-02T00:00:00.000+0000"}}}
				""";
		service.projectJira(receipt, payload);

		verify(tasks).upsertBatch(eq(project), eq("SAGA"), any());
		assertThat(receipt.getReceiptStatus()).isEqualTo(WebhookReceiptStatus.PROCESSED);
		verify(realtime)
				.publish(com.saga.be.realtime.ProjectRealtimeEventType.TASKS_CHANGED, project.getId(), "200");
	}

	@Test
	void jiraSprintCreated_projectsWhenBoardMatches() {
		WebhookReceipt receipt = receipt(IntegrationProvider.JIRA);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setJiraBoardId("68");
		when(jiraIntegrations.findFetchedActiveByBoardId(IntegrationStatus.ACTIVE, "68"))
				.thenReturn(List.of(integration));
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		String payload =
				"""
				{"webhookEvent":"sprint_created","sprint":{"id":31,"name":"Sprint 1","state":"future","originBoardId":68,"goal":"Ship"}}
				""";
		service.projectJira(receipt, payload);

		verify(tasks).upsertSprint(eq(integration), eq("31"), eq("Sprint 1"), eq("future"), any(), any(), eq("Ship"), any());
		verify(realtime)
				.publish(com.saga.be.realtime.ProjectRealtimeEventType.SPRINTS_CHANGED, project.getId(), "31");
	}

	@Test
	void jiraSprint_unrelatedBoardIgnored() {
		WebhookReceipt receipt = receipt(IntegrationProvider.JIRA);
		when(jiraIntegrations.findFetchedActiveByBoardId(IntegrationStatus.ACTIVE, "999"))
				.thenReturn(List.of());
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		String payload =
				"""
				{"webhookEvent":"sprint_updated","sprint":{"id":31,"name":"Other","originBoardId":999}}
				""";
		service.projectJira(receipt, payload);

		verify(tasks, org.mockito.Mockito.never())
				.upsertSprint(any(), any(), any(), any(), any(), any(), any(), any());
		assertThat(receipt.getReceiptStatus()).isEqualTo(WebhookReceiptStatus.PROCESSED);
	}

	@Test
	void jiraIssue_outsideSelectedProject_skipsTaskProjection() {
		WebhookReceipt receipt = receipt(IntegrationProvider.JIRA);
		when(jiraIntegrations.findFetchedActiveByJiraProject(IntegrationStatus.ACTIVE, "99999", "OTHER"))
				.thenReturn(List.of());
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		String payload =
				"""
				{"webhookEvent":"jira:issue_created","issue":{"id":"200","key":"OTHER-1","fields":{"summary":"Nope","project":{"id":"99999","key":"OTHER"}}}}
				""";
		service.projectJira(receipt, payload);

		verify(tasks, org.mockito.Mockito.never()).upsertBatch(any(), any(), any());
		assertThat(receipt.getReceiptStatus()).isEqualTo(WebhookReceiptStatus.PROCESSED);
	}

	private static WebhookReceipt receipt(IntegrationProvider provider) {
		WebhookReceipt receipt = new WebhookReceipt();
		receipt.setProvider(provider);
		receipt.setDeliveryId(UUID.randomUUID().toString());
		receipt.setReceiptStatus(WebhookReceiptStatus.RECEIVED);
		return receipt;
	}
}
