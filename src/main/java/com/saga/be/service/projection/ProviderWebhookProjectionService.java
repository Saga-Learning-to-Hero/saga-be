package com.saga.be.service.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.integration.WebhookReceipt;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.integration.webhook.WebhookReceiptService;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.WebhookReceiptRepository;
import com.saga.be.service.projection.GitCommitProjectionService.CommitDraft;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payload-only webhook projection. No provider HTTP on the hot path.
 */
@Service
@Profile("!test")
public class ProviderWebhookProjectionService {

	private static final Logger log = LoggerFactory.getLogger(ProviderWebhookProjectionService.class);

	private final ObjectMapper mapper;
	private final GitRepoRepository repos;
	private final JiraIntegrationRepository jiraIntegrations;
	private final GitCommitProjectionService commits;
	private final JiraTaskProjectionService tasks;
	private final JiraIssueWriteClient jiraFields;
	private final WebhookReceiptService receipts;
	private final WebhookReceiptRepository receiptRepository;
	private final ProjectRealtimePublisher realtime;

	public ProviderWebhookProjectionService(
			ObjectMapper mapper,
			GitRepoRepository repos,
			JiraIntegrationRepository jiraIntegrations,
			GitCommitProjectionService commits,
			JiraTaskProjectionService tasks,
			JiraIssueWriteClient jiraFields,
			WebhookReceiptRepository receiptRepository,
			ProjectRealtimePublisher realtime) {
		this.mapper = mapper;
		this.repos = repos;
		this.jiraIntegrations = jiraIntegrations;
		this.commits = commits;
		this.tasks = tasks;
		this.jiraFields = jiraFields;
		this.receiptRepository = receiptRepository;
		this.realtime = realtime;
		this.receipts = new WebhookReceiptService(new WebhookReceiptService.Store() {
			@Override
			public java.util.Optional<WebhookReceipt> find(
					com.saga.be.entity.enums.IntegrationProvider provider, String deliveryId) {
				return receiptRepository.findByProviderAndDeliveryId(provider, deliveryId);
			}

			@Override
			public WebhookReceipt save(WebhookReceipt receipt) {
				return receiptRepository.save(receipt);
			}
		});
	}

	@Transactional
	public void projectGithub(WebhookReceipt receipt, String eventType, String payloadJson) {
		try {
			if (receipt == null || "push".equalsIgnoreCase(eventType) == false) {
				if (receipt != null) {
					receipts.markProcessed(receipt, LocalDateTime.now());
				}
				return;
			}
			JsonNode root = mapper.readTree(payloadJson);
			long repositoryId = root.path("repository").path("id").asLong(0L);
			if (repositoryId <= 0L) {
				receipts.markFailed(receipt, "GITHUB_REPO_MISSING");
				return;
			}
			List<GitRepo> matches = repos.findFetchedActiveByProviderAndRepositoryId(
					GitProvider.GITHUB, repositoryId, IntegrationStatus.ACTIVE);
			if (matches.isEmpty()) {
				receipts.markProcessed(receipt, LocalDateTime.now());
				return;
			}
			String ref = root.path("ref").asText(null);
			String headRef = ref != null && ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
			List<CommitDraft> drafts = new ArrayList<>();
			for (JsonNode node : root.path("commits")) {
				String sha = text(node, "id");
				if (sha == null || sha.isBlank()) {
					continue;
				}
				JsonNode author = node.path("author");
				String login = text(author, "username");
				drafts.add(new CommitDraft(
						sha,
						text(node, "message"),
						ProjectionMappings.parseInstant(text(node, "timestamp")),
						null,
						login,
						headRef));
			}
			Set<UUID> commitChanged = new HashSet<>();
			Set<UUID> linkChanged = new HashSet<>();
			for (GitRepo repo : matches) {
				GitCommitProjectionService.UpsertOutcome outcome = commits.upsertBatchDetailed(repo, drafts);
				if (outcome.commitsTouched() > 0) {
					commitChanged.add(repo.getProject().getId());
				}
				if (outcome.linksCreated() > 0) {
					linkChanged.add(repo.getProject().getId());
				}
			}
			for (UUID projectId : commitChanged) {
				realtime.publish(ProjectRealtimeEventType.COMMITS_CHANGED, projectId);
			}
			for (UUID projectId : linkChanged) {
				realtime.publish(ProjectRealtimeEventType.TASK_LINKS_CHANGED, projectId);
			}
			receipts.markProcessed(receipt, LocalDateTime.now());
		} catch (Exception ex) {
			log.warn("github webhook projection failed type={}", ex.getClass().getSimpleName());
			if (receipt != null) {
				receipts.markFailed(receipt, "GITHUB_PROJECTION_FAILED");
			}
		}
	}

	@Transactional
	public void projectJira(WebhookReceipt receipt, String payloadJson) {
		try {
			JsonNode root = mapper.readTree(payloadJson);
			String webhookEvent = text(root, "webhookEvent");
			if (webhookEvent != null && webhookEvent.toLowerCase(Locale.ROOT).startsWith("sprint_")) {
				projectJiraSprint(receipt, root, webhookEvent);
				return;
			}
			JsonNode issue = root.path("issue");
			if (issue.isMissingNode() || issue.isNull()) {
				receipts.markProcessed(receipt, LocalDateTime.now());
				return;
			}
			String externalId = text(issue, "id");
			JsonNode fields = issue.path("fields");
			String jiraProjectId = text(fields.path("project"), "id");
			String projectKey = text(fields.path("project"), "key");
			if (externalId == null || jiraProjectId == null || projectKey == null) {
				receipts.markFailed(receipt, "JIRA_ISSUE_INCOMPLETE");
				return;
			}
			List<JiraIntegration> matches =
					jiraIntegrations.findFetchedActiveByJiraProject(IntegrationStatus.ACTIVE, jiraProjectId, projectKey);
			if (matches.isEmpty()) {
				receipts.markProcessed(receipt, LocalDateTime.now());
				return;
			}
			if (webhookEvent != null && webhookEvent.toLowerCase(Locale.ROOT).contains("deleted")) {
				LocalDateTime now = LocalDateTime.now();
				for (JiraIntegration integration : matches) {
					tasks.softDelete(integration.getProject(), externalId, now);
					realtime.publish(
							ProjectRealtimeEventType.TASKS_CHANGED,
							integration.getProject().getId(),
							externalId);
				}
				receipts.markProcessed(receipt, LocalDateTime.now());
				return;
			}
			for (JiraIntegration integration : matches) {
				// Cache-peek only -- this handler must never trigger provider HTTP field
				// discovery. Before any sync has warmed the cache for this cloud, both come back
				// null and the shared toSummary(authoritative=false) below simply cannot mark
				// those fields "provided", which correctly preserves whatever SAGA already has.
				String storyField = jiraFields.peekCachedStoryPointsFieldId(integration.getCloudId());
				String sprintField = jiraFields.peekCachedSprintFieldId(integration.getCloudId());
				IssueSummary summary = JiraIssueWriteClient.toSummary(issue, storyField, sprintField, false);
				int applied =
						tasks.upsertBatch(integration.getProject(), integration.getProjectKey(), List.of(summary));
				if (applied > 0) {
					realtime.publish(
							ProjectRealtimeEventType.TASKS_CHANGED,
							integration.getProject().getId(),
							externalId);
				}
			}
			receipts.markProcessed(receipt, LocalDateTime.now());
		} catch (Exception ex) {
			log.warn("jira webhook projection failed type={}", ex.getClass().getSimpleName());
			if (receipt != null) {
				receipts.markFailed(receipt, "JIRA_PROJECTION_FAILED");
			}
		}
	}

	private void projectJiraSprint(WebhookReceipt receipt, JsonNode root, String webhookEvent) {
		JsonNode sprint = root.path("sprint");
		if (sprint.isMissingNode() || sprint.isNull()) {
			receipts.markProcessed(receipt, LocalDateTime.now());
			return;
		}
		String sprintId = text(sprint, "id");
		if (sprintId == null) {
			receipts.markFailed(receipt, "JIRA_SPRINT_INCOMPLETE");
			return;
		}
		String boardId = text(sprint, "originBoardId");
		if (boardId == null && sprint.has("originBoardId") && sprint.get("originBoardId").canConvertToLong()) {
			boardId = String.valueOf(sprint.get("originBoardId").asLong());
		}
		if (boardId == null || boardId.isBlank()) {
			receipts.markProcessed(receipt, LocalDateTime.now());
			return;
		}
		String cloudId = text(root, "cloudId");
		if (cloudId == null) {
			cloudId = text(root.path("matchedWebhookIds"), "cloudId");
		}
		List<JiraIntegration> matches;
		if (cloudId != null && !cloudId.isBlank()) {
			matches = jiraIntegrations.findFetchedActiveByCloudAndBoard(IntegrationStatus.ACTIVE, cloudId, boardId);
		} else {
			matches = jiraIntegrations.findFetchedActiveByBoardId(IntegrationStatus.ACTIVE, boardId);
		}
		if (matches.isEmpty()) {
			receipts.markProcessed(receipt, LocalDateTime.now());
			return;
		}
		boolean deleted = webhookEvent.toLowerCase(Locale.ROOT).contains("deleted");
		LocalDateTime now = LocalDateTime.now();
		for (JiraIntegration integration : matches) {
			if (deleted) {
				tasks.softDeleteSprint(integration, sprintId, now);
				realtime.publish(
						ProjectRealtimeEventType.SPRINTS_CHANGED,
						integration.getProject().getId(),
						sprintId);
				realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, integration.getProject().getId());
			} else {
				tasks.upsertSprint(
						integration,
						sprintId,
						text(sprint, "name"),
						text(sprint, "state"),
						ProjectionMappings.parseInstant(text(sprint, "startDate")),
						ProjectionMappings.parseInstant(text(sprint, "endDate")),
						text(sprint, "goal"),
						ProjectionMappings.parseInstant(text(sprint, "completeDate")));
				realtime.publish(
						ProjectRealtimeEventType.SPRINTS_CHANGED,
						integration.getProject().getId(),
						sprintId);
			}
		}
		receipts.markProcessed(receipt, LocalDateTime.now());
	}

	private static String text(JsonNode node, String field) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asText(null);
		return text == null || text.isBlank() ? null : text;
	}
}
