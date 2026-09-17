package com.saga.be.controller;

import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.github.GitHubWebhookSignature;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.integration.jira.JiraWebhookAuth;
import com.saga.be.integration.webhook.WebhookReceiptService;
import com.saga.be.repository.WebhookReceiptRepository;
import com.saga.be.service.attribution.AttributionWarningService;
import com.saga.be.service.jira.JiraEvidenceJobExecutor;
import com.saga.be.service.jira.JiraIssueEvidenceSyncService;
import com.saga.be.service.projection.ProviderWebhookProjectionService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/webhooks")
@Workload(WorkloadClass.BACKGROUND_SYNC)
public class ProviderWebhookController {

	private static final Logger log = LoggerFactory.getLogger(ProviderWebhookController.class);

	private final IntegrationProperties properties;
	private final WebhookReceiptService receipts;
	private final AttributionWarningService warnings;
	private final ProviderWebhookProjectionService projection;
	private final JiraIssueEvidenceSyncService jiraEvidence;
	private final JiraEvidenceJobExecutor jiraEvidenceJobs;

	public ProviderWebhookController(
			IntegrationProperties properties,
			WebhookReceiptRepository receiptRepository,
			AttributionWarningService warnings,
			ProviderWebhookProjectionService projection,
			JiraIssueEvidenceSyncService jiraEvidence,
			JiraEvidenceJobExecutor jiraEvidenceJobs) {
		this.properties = properties;
		this.receipts = new WebhookReceiptService(new WebhookReceiptService.Store() {
			@Override
			public java.util.Optional<com.saga.be.entity.integration.WebhookReceipt> find(
					IntegrationProvider provider, String deliveryId) {
				return receiptRepository.findByProviderAndDeliveryId(provider, deliveryId);
			}

			@Override
			public com.saga.be.entity.integration.WebhookReceipt save(
					com.saga.be.entity.integration.WebhookReceipt receipt) {
				return receiptRepository.save(receipt);
			}
		});
		this.warnings = warnings;
		this.projection = projection;
		this.jiraEvidence = jiraEvidence;
		this.jiraEvidenceJobs = jiraEvidenceJobs;
	}

	@PostMapping("/github")
	public ResponseEntity<Void> github(
			@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
			@RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery,
			@RequestHeader(value = "X-GitHub-Event", required = false) String event,
			HttpServletRequest request)
			throws IOException {
		byte[] body = request.getInputStream().readAllBytes();
		if (!GitHubWebhookSignature.matches(body, properties.getGithub().getWebhookSecret(), signature)) {
			warnings.securityFailure("github-sig:" + (delivery == null ? "none" : delivery), "Invalid GitHub webhook signature.");
			throw new IntegrationException(
					IntegrationErrorCode.WEBHOOK_SIGNATURE_INVALID, HttpStatus.UNAUTHORIZED, "Invalid webhook signature.");
		}
		String payload = new String(body, StandardCharsets.UTF_8);
		String eventType = event == null ? "unknown" : event;
		WebhookReceiptService.IngestResult result = receipts.ingest(
				IntegrationProvider.GITHUB, delivery, eventType, null, payload, null, LocalDateTime.now());
		if (!result.duplicate()) {
			projection.projectGithub(result.receipt(), eventType, payload);
		}
		return ResponseEntity.accepted().build();
	}

	@PostMapping("/jira")
	public ResponseEntity<Void> jira(
			@RequestHeader(value = "X-Atlassian-Webhook-Identifier", required = false) String delivery,
			@RequestHeader(value = "Authorization", required = false) String authorization,
			@RequestHeader(value = "X-Hub-Signature", required = false) String signature,
			HttpServletRequest request)
			throws IOException {
		byte[] body = request.getInputStream().readAllBytes();
		// OAuth dynamic webhooks: Authorization Bearer JWT (client secret).
		// Optional manual admin fallback: X-Hub-Signature HMAC (SAGA_JIRA_WEBHOOK_SECRET).
		if (!JiraWebhookAuth.accepts(
				body,
				authorization,
				signature,
				properties.getJira().getClientSecret(),
				properties.getJira().getWebhookSecret())) {
			log.warn(
					"jira webhook ingress result=REJECTED reason=AUTH_INVALID deliveryPresent={} authorizationPresent={}",
					delivery != null,
					authorization != null);
			warnings.securityFailure(
					"jira-sig:" + (delivery == null ? "none" : delivery), "Invalid Jira webhook authentication.");
			throw new IntegrationException(
					IntegrationErrorCode.WEBHOOK_SIGNATURE_INVALID,
					HttpStatus.UNAUTHORIZED,
					"Invalid webhook signature.");
		}
		String payload = new String(body, StandardCharsets.UTF_8);
		String deliveryId = delivery == null ? java.util.UUID.nameUUIDFromBytes(body).toString() : delivery;
		WebhookReceiptService.IngestResult result = receipts.ingest(
				IntegrationProvider.JIRA, deliveryId, "jira:issue", null, payload, null, LocalDateTime.now());
		if (!result.duplicate()) {
			projection.projectJira(result.receipt(), payload);
		}
		try {
			// Duplicate deliveries still enqueue so a prior 503 (queue full after ingest) is not lost.
			jiraEvidenceJobs.submit(() -> jiraEvidence.handleWebhook(payload));
		} catch (RejectedExecutionException ex) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.SERVICE_UNAVAILABLE,
					"Jira evidence backlog is full.");
		}
		return ResponseEntity.accepted().build();
	}
}
