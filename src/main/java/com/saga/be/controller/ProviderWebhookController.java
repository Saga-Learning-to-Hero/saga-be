package com.saga.be.controller;

import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.github.GitHubWebhookSignature;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.integration.webhook.WebhookReceiptService;
import com.saga.be.repository.WebhookReceiptRepository;
import com.saga.be.service.attribution.AttributionWarningService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDateTime;
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
public class ProviderWebhookController {

	private final IntegrationProperties properties;
	private final WebhookReceiptService receipts;
	private final AttributionWarningService warnings;

	public ProviderWebhookController(
			IntegrationProperties properties,
			WebhookReceiptRepository receiptRepository,
			AttributionWarningService warnings) {
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
		WebhookReceiptService.IngestResult result = receipts.ingest(
				IntegrationProvider.GITHUB,
				delivery,
				event == null ? "unknown" : event,
				null,
				new String(body, java.nio.charset.StandardCharsets.UTF_8),
				null,
				LocalDateTime.now());
		if (result.duplicate()) {
			return ResponseEntity.accepted().build();
		}
		return ResponseEntity.accepted().build();
	}

	@PostMapping("/jira")
	public ResponseEntity<Void> jira(
			@RequestHeader(value = "X-Atlassian-Webhook-Identifier", required = false) String delivery,
			@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
			HttpServletRequest request)
			throws IOException {
		byte[] body = request.getInputStream().readAllBytes();
		if (properties.getJira().getClientSecret() != null
				&& !properties.getJira().getClientSecret().isBlank()
				&& signature != null
				&& !GitHubWebhookSignature.matches(body, properties.getJira().getClientSecret(), signature)) {
			throw new IntegrationException(
					IntegrationErrorCode.WEBHOOK_SIGNATURE_INVALID, HttpStatus.UNAUTHORIZED, "Invalid webhook signature.");
		}
		String deliveryId = delivery == null ? java.util.UUID.nameUUIDFromBytes(body).toString() : delivery;
		receipts.ingest(
				IntegrationProvider.JIRA,
				deliveryId,
				"jira:issue",
				null,
				new String(body, java.nio.charset.StandardCharsets.UTF_8),
				null,
				LocalDateTime.now());
		return ResponseEntity.accepted().build();
	}
}
