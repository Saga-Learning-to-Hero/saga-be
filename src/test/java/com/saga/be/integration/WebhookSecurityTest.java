package com.saga.be.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.integration.WebhookReceipt;
import com.saga.be.integration.github.GitHubWebhookSignature;
import com.saga.be.integration.webhook.WebhookReceiptService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class WebhookSecurityTest {

	@Test
	void validHmacIsAccepted() {
		byte[] body = "{\"zen\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
		String hex = GitHubWebhookSignature.hmacSha256Hex(body, "super-secret");
		assertTrue(GitHubWebhookSignature.matches(body, "super-secret", "sha256=" + hex));
	}

	@Test
	void invalidHmacIsRejected() {
		byte[] body = "{\"zen\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
		assertFalse(GitHubWebhookSignature.matches(body, "super-secret", "sha256=deadbeef"));
		assertFalse(GitHubWebhookSignature.matches(body, "super-secret", null));
	}

	@Test
	void duplicateDeliveryIsIdempotent() {
		Memory store = new Memory();
		WebhookReceiptService service = new WebhookReceiptService(store);
		WebhookReceiptService.IngestResult first = service.ingest(
				IntegrationProvider.GITHUB, "del-1", "push", "created", "{}", null, LocalDateTime.now());
		WebhookReceiptService.IngestResult second = service.ingest(
				IntegrationProvider.GITHUB, "del-1", "push", "created", "{}", null, LocalDateTime.now());
		assertFalse(first.duplicate());
		assertTrue(second.duplicate());
		assertEquals(1, store.rows.size());
	}

	@Test
	void jiraDuplicateDeliveryIsIdempotent() {
		Memory store = new Memory();
		WebhookReceiptService service = new WebhookReceiptService(store);
		service.ingest(IntegrationProvider.JIRA, "jira-1", "jira:issue_updated", "updated", "{}", null, LocalDateTime.now());
		assertTrue(service.ingest(IntegrationProvider.JIRA, "jira-1", "jira:issue_updated", "updated", "{}", null, LocalDateTime.now())
				.duplicate());
	}

	private static final class Memory implements WebhookReceiptService.Store {
		private final Map<String, WebhookReceipt> rows = new ConcurrentHashMap<>();

		@Override
		public Optional<WebhookReceipt> find(IntegrationProvider provider, String deliveryId) {
			return Optional.ofNullable(rows.get(provider + ":" + deliveryId));
		}

		@Override
		public WebhookReceipt save(WebhookReceipt receipt) {
			if (receipt.getId() == null) {
				receipt.setId(UUID.randomUUID());
			}
			rows.put(receipt.getProvider() + ":" + receipt.getDeliveryId(), receipt);
			return receipt;
		}
	}
}
