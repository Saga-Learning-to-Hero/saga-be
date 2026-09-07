package com.saga.be.service.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.integration.WebhookReceipt;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.integration.webhook.WebhookReceiptService;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.WebhookReceiptRepository;
import com.saga.be.service.projection.GitCommitProjectionService.CommitDraft;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
	private final WebhookReceiptService receipts;
	private final WebhookReceiptRepository receiptRepository;

	public ProviderWebhookProjectionService(
			ObjectMapper mapper,
			GitRepoRepository repos,
			JiraIntegrationRepository jiraIntegrations,
			GitCommitProjectionService commits,
			JiraTaskProjectionService tasks,
			WebhookReceiptRepository receiptRepository) {
		this.mapper = mapper;
		this.repos = repos;
		this.jiraIntegrations = jiraIntegrations;
		this.commits = commits;
		this.tasks = tasks;
		this.receiptRepository = receiptRepository;
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
			for (GitRepo repo : matches) {
				commits.upsertBatch(repo, drafts);
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
			JsonNode issue = root.path("issue");
			if (issue.isMissingNode() || issue.isNull()) {
				receipts.markProcessed(receipt, LocalDateTime.now());
				return;
			}
			String externalId = text(issue, "id");
			String key = text(issue, "key");
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
			if (webhookEvent != null && webhookEvent.toLowerCase().contains("deleted")) {
				LocalDateTime now = LocalDateTime.now();
				for (JiraIntegration integration : matches) {
					tasks.softDelete(integration.getProject(), externalId, now);
				}
				receipts.markProcessed(receipt, LocalDateTime.now());
				return;
			}
			IssueSummary summary = toSummary(externalId, key, fields);
			for (JiraIntegration integration : matches) {
				tasks.upsertBatch(integration.getProject(), integration.getProjectKey(), List.of(summary));
			}
			receipts.markProcessed(receipt, LocalDateTime.now());
		} catch (Exception ex) {
			log.warn("jira webhook projection failed type={}", ex.getClass().getSimpleName());
			if (receipt != null) {
				receipts.markFailed(receipt, "JIRA_PROJECTION_FAILED");
			}
		}
	}

	private static IssueSummary toSummary(String id, String key, JsonNode fields) {
		JsonNode status = fields.path("status");
		JsonNode category = status.path("statusCategory");
		JsonNode type = fields.path("issuetype");
		JsonNode assignee = fields.path("assignee");
		return new IssueSummary(
				id,
				key,
				text(fields, "summary"),
				text(status, "id"),
				text(status, "name"),
				text(category, "key"),
				text(type, "name"),
				text(assignee, "accountId"),
				text(fields, "created"),
				text(fields, "updated"));
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
