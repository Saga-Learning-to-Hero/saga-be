package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.integration.jira.JiraTeamTokenService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
	private JiraIssueWriteClient jiraFields;
	@Mock
	private JiraTeamTokenService tokens;
	@Mock
	private WebhookReceiptRepository receiptRepository;
	@Mock
	private com.saga.be.realtime.ProjectRealtimePublisher realtime;
	@Mock
	private PlatformTransactionManager transactionManager;

	private ProviderWebhookProjectionService service;

	@BeforeEach
	void setUp() {
		org.mockito.Mockito.lenient()
				.when(transactionManager.getTransaction(org.mockito.ArgumentMatchers.any(TransactionDefinition.class)))
				.thenReturn(new SimpleTransactionStatus());
		// Default: cache warm with no field resolved ("" -- discovery ran, found nothing), not
		// null ("never tried") -- so tests that don't care about field-cache state stay on the
		// fast payload-only path and never need to stub the provider-refresh fallback. Tests that
		// specifically exercise the cold-cache path override this with null explicitly.
		org.mockito.Mockito.lenient()
				.when(jiraFields.peekCachedStoryPointsFieldId(org.mockito.ArgumentMatchers.any()))
				.thenReturn("");
		org.mockito.Mockito.lenient()
				.when(jiraFields.peekCachedSprintFieldId(org.mockito.ArgumentMatchers.any()))
				.thenReturn("");
		service = new ProviderWebhookProjectionService(
				new ObjectMapper(),
				repos,
				jiraIntegrations,
				commits,
				tasks,
				jiraFields,
				tokens,
				receiptRepository,
				realtime,
				transactionManager);
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
		repo.setCreatedAt(java.time.LocalDateTime.of(2025, 12, 1, 0, 0));
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
		assertThat(captor.getValue().getFirst().committedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
		assertThat(receipt.getReceiptStatus()).isEqualTo(WebhookReceiptStatus.PROCESSED);
		verify(realtime).publish(com.saga.be.realtime.ProjectRealtimeEventType.COMMITS_CHANGED, project.getId());
		verify(realtime).publish(com.saga.be.realtime.ProjectRealtimeEventType.TASK_LINKS_CHANGED, project.getId());
	}

	@Test
	void githubPush_passesPreAndPostClaimDrafts_projectionEnforcesCutoff() {
		// Webhook still forwards payload commits; GitCommitProjectionService applies Option B cutoff.
		WebhookReceipt receipt = receipt(IntegrationProvider.GITHUB);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		GitRepo repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setProject(project);
		repo.setRepositoryId(55L);
		repo.setCreatedAt(java.time.LocalDateTime.of(2026, 9, 1, 0, 0));
		when(repos.findFetchedActiveByProviderAndRepositoryId(GitProvider.GITHUB, 55L, IntegrationStatus.ACTIVE))
				.thenReturn(List.of(repo));
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(commits.upsertBatchDetailed(eq(repo), any()))
				.thenReturn(new GitCommitProjectionService.UpsertOutcome(1, 0));

		String payload =
				"""
				{"ref":"refs/heads/main","repository":{"id":55},"commits":[
				  {"id":"old","message":"SAGA-1","timestamp":"2026-08-15T00:00:00Z","author":{"username":"alice"}},
				  {"id":"new","message":"SAGA-2","timestamp":"2026-09-20T00:00:00Z","author":{"username":"alice"}}
				]}
				""";
		service.projectGithub(receipt, "push", payload);

		ArgumentCaptor<List<GitCommitProjectionService.CommitDraft>> captor = ArgumentCaptor.forClass(List.class);
		verify(commits).upsertBatchDetailed(eq(repo), captor.capture());
		assertThat(captor.getValue()).extracting(GitCommitProjectionService.CommitDraft::sha).containsExactly("old", "new");
		assertThat(GitRepoCommitClaimCutoff.filterEligible(repo, captor.getValue(), true))
				.extracting(GitCommitProjectionService.CommitDraft::sha)
				.containsExactly("new");
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
	void jiraIssueUpdated_payloadOmitsStoryPointAndSprint_marksBothNotProvided() {
		// Regression: a partial webhook that never even resolved/carried the dynamic fields must
		// not tell JiraTaskProjectionService to null out an already-known story point/sprint.
		WebhookReceipt receipt = receipt(IntegrationProvider.JIRA);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setCloudId("cloud-1");
		when(jiraIntegrations.findFetchedActiveByJiraProject(IntegrationStatus.ACTIVE, "10000", "SAGA"))
				.thenReturn(List.of(integration));
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(tasks.upsertBatch(eq(project), eq("SAGA"), any())).thenReturn(1);
		// Cache IS warm (discovery already ran and found nothing -- "" -- not "never tried"/null),
		// so this stays on the fast payload-only path and never calls the provider.
		when(jiraFields.peekCachedStoryPointsFieldId("cloud-1")).thenReturn("");
		when(jiraFields.peekCachedSprintFieldId("cloud-1")).thenReturn("");

		String payload =
				"""
				{"webhookEvent":"jira:issue_updated","issue":{"id":"200","key":"SAGA-9","fields":{"summary":"Auth","status":{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}},"issuetype":{"name":"Story"},"assignee":{"accountId":"acct-1"},"project":{"id":"10000","key":"SAGA"},"updated":"2026-01-02T00:00:00.000+0000"}}}
				""";
		service.projectJira(receipt, payload);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<IssueSummary>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).upsertBatch(eq(project), eq("SAGA"), captor.capture());
		IssueSummary summary = captor.getValue().getFirst();
		assertThat(summary.storyPointsProvided()).isFalse();
		assertThat(summary.sprintProvided()).isFalse();
		verify(tokens, never()).accessToken(any());
	}

	@Test
	void jiraIssueUpdated_coldCache_providerRefreshFails_preservesExistingRatherThanGuess() {
		// Cache genuinely never resolved (peekCached* returns null, not "") on this process for
		// this cloud -- e.g. right after connect/restart, before any sync has run. The handler
		// attempts a targeted single-issue refresh; when that ALSO fails (token/rate-limit/etc.),
		// it must fall back to the non-authoritative payload-derived summary rather than guess.
		WebhookReceipt receipt = receipt(IntegrationProvider.JIRA);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setCloudId("cloud-1");
		when(jiraIntegrations.findFetchedActiveByJiraProject(IntegrationStatus.ACTIVE, "10000", "SAGA"))
				.thenReturn(List.of(integration));
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(tasks.upsertBatch(eq(project), eq("SAGA"), any())).thenReturn(1);
		when(jiraFields.peekCachedStoryPointsFieldId("cloud-1")).thenReturn(null);
		when(jiraFields.peekCachedSprintFieldId("cloud-1")).thenReturn(null);
		when(tokens.accessToken(integration)).thenThrow(new RuntimeException("token unavailable"));

		String payload =
				"""
				{"webhookEvent":"jira:issue_updated","issue":{"id":"200","key":"SAGA-9","fields":{"summary":"Auth","status":{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}},"issuetype":{"name":"Story"},"assignee":{"accountId":"acct-1"},"project":{"id":"10000","key":"SAGA"},"updated":"2026-01-02T00:00:00.000+0000"}}}
				""";
		service.projectJira(receipt, payload);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<IssueSummary>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).upsertBatch(eq(project), eq("SAGA"), captor.capture());
		IssueSummary summary = captor.getValue().getFirst();
		assertThat(summary.storyPointsProvided()).isFalse();
		assertThat(summary.sprintProvided()).isFalse();
	}

	@Test
	void jiraIssueUpdated_coldCache_providerRefreshSucceeds_usesAuthoritativeIssue() {
		// Cache cold (right after connect/restart) but the targeted single-issue refresh succeeds:
		// the fetched IssueSummary (authoritative) is used instead of the raw payload, and it also
		// warms jiraFields' cache as a side effect (production behavior of getIssue -- not asserted
		// here since jiraFields is a mock, only that the returned summary is what gets persisted).
		WebhookReceipt receipt = receipt(IntegrationProvider.JIRA);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setCloudId("cloud-1");
		when(jiraIntegrations.findFetchedActiveByJiraProject(IntegrationStatus.ACTIVE, "10000", "SAGA"))
				.thenReturn(List.of(integration));
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(tasks.upsertBatch(eq(project), eq("SAGA"), any())).thenReturn(1);
		when(jiraFields.peekCachedStoryPointsFieldId("cloud-1")).thenReturn(null);
		when(jiraFields.peekCachedSprintFieldId("cloud-1")).thenReturn("");
		when(tokens.accessToken(integration)).thenReturn("token-1");
		IssueSummary refreshed = new IssueSummary(
				"200", "SAGA-9", "Auth", "3", "In Progress", "indeterminate", "Story", "10001", "acct-1", "Alice",
				null, null, 8, null, null, null, null, null, "2026-01-02T00:00:00.000+0000");
		when(jiraFields.getIssue("token-1", "cloud-1", "200")).thenReturn(refreshed);

		String payload =
				"""
				{"webhookEvent":"jira:issue_updated","issue":{"id":"200","key":"SAGA-9","fields":{"summary":"Auth","status":{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}},"issuetype":{"name":"Story"},"assignee":{"accountId":"acct-1"},"project":{"id":"10000","key":"SAGA"},"updated":"2026-01-02T00:00:00.000+0000"}}}
				""";
		service.projectJira(receipt, payload);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<IssueSummary>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).upsertBatch(eq(project), eq("SAGA"), captor.capture());
		assertThat(captor.getValue().getFirst()).isSameAs(refreshed);
		assertThat(captor.getValue().getFirst().storyPoints()).isEqualTo(8);
	}

	@Test
	void jiraIssueUpdated_payloadCarriesResolvedStoryPointField_marksProvidedWithValue() {
		WebhookReceipt receipt = receipt(IntegrationProvider.JIRA);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setCloudId("cloud-1");
		when(jiraIntegrations.findFetchedActiveByJiraProject(IntegrationStatus.ACTIVE, "10000", "SAGA"))
				.thenReturn(List.of(integration));
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(tasks.upsertBatch(eq(project), eq("SAGA"), any())).thenReturn(1);
		when(jiraFields.peekCachedStoryPointsFieldId("cloud-1")).thenReturn("customfield_777");
		when(jiraFields.peekCachedSprintFieldId("cloud-1")).thenReturn("");

		String payload =
				"""
				{"webhookEvent":"jira:issue_updated","issue":{"id":"200","key":"SAGA-9","fields":{"summary":"Auth","status":{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}},"issuetype":{"name":"Story"},"assignee":{"accountId":"acct-1"},"project":{"id":"10000","key":"SAGA"},"customfield_777":5,"updated":"2026-01-02T00:00:00.000+0000"}}}
				""";
		service.projectJira(receipt, payload);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<IssueSummary>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).upsertBatch(eq(project), eq("SAGA"), captor.capture());
		IssueSummary summary = captor.getValue().getFirst();
		assertThat(summary.storyPointsProvided()).isTrue();
		assertThat(summary.storyPoints()).isEqualTo(5);
	}

	@Test
	void jiraIssueUpdated_payloadExplicitlyClearsSprint_marksProvidedWithNull() {
		// Jira sent the "sprint" key with an empty array -- an EXPLICIT clear, distinct from the
		// key being absent entirely (which must be preserved, see the "omits" test above).
		WebhookReceipt receipt = receipt(IntegrationProvider.JIRA);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setCloudId("cloud-1");
		when(jiraIntegrations.findFetchedActiveByJiraProject(IntegrationStatus.ACTIVE, "10000", "SAGA"))
				.thenReturn(List.of(integration));
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(tasks.upsertBatch(eq(project), eq("SAGA"), any())).thenReturn(1);
		when(jiraFields.peekCachedStoryPointsFieldId("cloud-1")).thenReturn("");
		when(jiraFields.peekCachedSprintFieldId("cloud-1")).thenReturn("");

		String payload =
				"""
				{"webhookEvent":"jira:issue_updated","issue":{"id":"200","key":"SAGA-9","fields":{"summary":"Auth","status":{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}},"issuetype":{"name":"Story"},"assignee":{"accountId":"acct-1"},"project":{"id":"10000","key":"SAGA"},"sprint":[],"updated":"2026-01-02T00:00:00.000+0000"}}}
				""";
		service.projectJira(receipt, payload);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<IssueSummary>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).upsertBatch(eq(project), eq("SAGA"), captor.capture());
		IssueSummary summary = captor.getValue().getFirst();
		assertThat(summary.sprintProvided()).isTrue();
		assertThat(summary.sprintExternalId()).isNull();
	}

	@Test
	void jiraIssueUpdated_unrelatedWebhookOmitsParent_preservesExisting() {
		// fields.parent is a base/system field (unlike Story Points/Sprint it needs no dynamic
		// field id resolution), but a webhook could still legitimately omit it if Jira's payload
		// for this event simply doesn't include it -- must not be treated as "parent cleared".
		WebhookReceipt receipt = receipt(IntegrationProvider.JIRA);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setCloudId("cloud-1");
		when(jiraIntegrations.findFetchedActiveByJiraProject(IntegrationStatus.ACTIVE, "10000", "SAGA"))
				.thenReturn(List.of(integration));
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(tasks.upsertBatch(eq(project), eq("SAGA"), any())).thenReturn(1);

		String payload =
				"""
				{"webhookEvent":"jira:issue_updated","issue":{"id":"200","key":"SAGA-50","fields":{"summary":"Unrelated title change","status":{"id":"1","name":"To Do","statusCategory":{"key":"new"}},"issuetype":{"name":"Subtask"},"project":{"id":"10000","key":"SAGA"},"updated":"2026-01-02T00:00:00.000+0000"}}}
				""";
		service.projectJira(receipt, payload);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<IssueSummary>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).upsertBatch(eq(project), eq("SAGA"), captor.capture());
		assertThat(captor.getValue().getFirst().parentProvided()).isFalse();
	}

	@Test
	void jiraIssueUpdated_webhookParentChanged_reflectsNewParent() {
		WebhookReceipt receipt = receipt(IntegrationProvider.JIRA);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setCloudId("cloud-1");
		when(jiraIntegrations.findFetchedActiveByJiraProject(IntegrationStatus.ACTIVE, "10000", "SAGA"))
				.thenReturn(List.of(integration));
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(tasks.upsertBatch(eq(project), eq("SAGA"), any())).thenReturn(1);

		String payload =
				"""
				{"webhookEvent":"jira:issue_updated","issue":{"id":"200","key":"SAGA-50","fields":{"summary":"Moved","status":{"id":"1","name":"To Do","statusCategory":{"key":"new"}},"issuetype":{"name":"Subtask"},"project":{"id":"10000","key":"SAGA"},"parent":{"id":"10060","key":"SAGA-60"},"updated":"2026-01-02T00:00:00.000+0000"}}}
				""";
		service.projectJira(receipt, payload);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<IssueSummary>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).upsertBatch(eq(project), eq("SAGA"), captor.capture());
		IssueSummary summary = captor.getValue().getFirst();
		assertThat(summary.parentProvided()).isTrue();
		assertThat(summary.parentExternalId()).isEqualTo("10060");
		assertThat(summary.parentExternalKey()).isEqualTo("SAGA-60");
	}

	@Test
	void jiraIssueUpdated_webhookParentRemoved_clearedThroughExplicitNullField() {
		// Jira sent the "parent" key with an explicit null value -- distinguishable from the key
		// being absent entirely (the "omits" test above), matching the same has()-based semantics
		// already proven for Story Points/Sprint.
		WebhookReceipt receipt = receipt(IntegrationProvider.JIRA);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setCloudId("cloud-1");
		when(jiraIntegrations.findFetchedActiveByJiraProject(IntegrationStatus.ACTIVE, "10000", "SAGA"))
				.thenReturn(List.of(integration));
		when(receiptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(tasks.upsertBatch(eq(project), eq("SAGA"), any())).thenReturn(1);

		String payload =
				"""
				{"webhookEvent":"jira:issue_updated","issue":{"id":"200","key":"SAGA-50","fields":{"summary":"Detached","status":{"id":"1","name":"To Do","statusCategory":{"key":"new"}},"issuetype":{"name":"Task"},"project":{"id":"10000","key":"SAGA"},"parent":null,"updated":"2026-01-02T00:00:00.000+0000"}}}
				""";
		service.projectJira(receipt, payload);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<IssueSummary>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).upsertBatch(eq(project), eq("SAGA"), captor.capture());
		IssueSummary summary = captor.getValue().getFirst();
		assertThat(summary.parentProvided()).isTrue();
		assertThat(summary.parentExternalId()).isNull();
		assertThat(summary.parentExternalKey()).isNull();
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
