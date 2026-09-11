package com.saga.be.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.integration.WebhookReceipt;
import com.saga.be.integration.github.GitHubWebhookSignature;
import com.saga.be.integration.jira.JiraWebhookSignature;
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
	void jiraValidXHubSignatureSha256IsAccepted() {
		byte[] body = "{\"webhookEvent\":\"jira:issue_updated\"}".getBytes(StandardCharsets.UTF_8);
		String secret = "dedicated-jira-webhook-secret";
		String header = "sha256=" + JiraWebhookSignature.hmacSha256Hex(body, secret);
		assertTrue(JiraWebhookSignature.accepts(body, secret, header));
		assertTrue(JiraWebhookSignature.matches(body, secret, header));
	}

	@Test
	void jiraInvalidHmacIsRejected() {
		byte[] body = "{\"webhookEvent\":\"jira:issue_updated\"}".getBytes(StandardCharsets.UTF_8);
		assertFalse(JiraWebhookSignature.accepts(body, "secret", "sha256=deadbeef"));
		assertFalse(JiraWebhookSignature.matches(body, "secret", "sha256=deadbeef"));
	}

	@Test
	void jiraMissingSignatureRejectedWhenSecretConfigured() {
		byte[] body = "{\"webhookEvent\":\"jira:issue_updated\"}".getBytes(StandardCharsets.UTF_8);
		assertFalse(JiraWebhookSignature.accepts(body, "jira-webhook-secret", null));
		assertFalse(JiraWebhookSignature.accepts(body, "jira-webhook-secret", ""));
		assertFalse(JiraWebhookSignature.matches(body, "jira-webhook-secret", null));
	}

	@Test
	void jiraMalformedSignatureRejected() {
		byte[] body = "{\"webhookEvent\":\"sprint_created\"}".getBytes(StandardCharsets.UTF_8);
		String secret = "dedicated-jira-webhook-secret";
		assertFalse(JiraWebhookSignature.accepts(body, secret, "not-a-signature"));
		assertFalse(JiraWebhookSignature.accepts(body, secret, "="));
		assertFalse(JiraWebhookSignature.accepts(body, secret, "sha256="));
		assertFalse(JiraWebhookSignature.accepts(body, secret, "=abc"));
	}

	@Test
	void jiraUnsupportedMethodRejected() {
		byte[] body = "{\"webhookEvent\":\"sprint_created\"}".getBytes(StandardCharsets.UTF_8);
		String secret = "dedicated-jira-webhook-secret";
		String hex = JiraWebhookSignature.hmacSha256Hex(body, secret);
		assertFalse(JiraWebhookSignature.accepts(body, secret, "sha1=" + hex));
		assertFalse(JiraWebhookSignature.accepts(body, secret, "md5=" + hex));
	}

	@Test
	void jiraXHubSignature256AloneDoesNotCountAsValid() {
		// Controller only binds X-Hub-Signature; a lone X-Hub-Signature-256 header yields null here.
		byte[] body = "{\"webhookEvent\":\"jira:issue_created\"}".getBytes(StandardCharsets.UTF_8);
		String secret = "dedicated-jira-webhook-secret";
		String legacy256Value = "sha256=" + JiraWebhookSignature.hmacSha256Hex(body, secret);
		assertTrue(GitHubWebhookSignature.matches(body, secret, legacy256Value));
		assertFalse(JiraWebhookSignature.accepts(body, secret, null));
	}

	@Test
	void jiraBlankSecretPreservesDevCompatibility() {
		byte[] body = "{\"webhookEvent\":\"jira:issue_updated\"}".getBytes(StandardCharsets.UTF_8);
		assertTrue(JiraWebhookSignature.accepts(body, null, null));
		assertTrue(JiraWebhookSignature.accepts(body, "", null));
		assertTrue(JiraWebhookSignature.accepts(body, "   ", "sha256=deadbeef"));
	}

	@Test
	void jiraWebhookSignatureValidatesAgainstDedicatedSecretNotOauthClientSecret() {
		byte[] body = "{\"webhookEvent\":\"sprint_created\"}".getBytes(StandardCharsets.UTF_8);
		String webhookSecret = "dedicated-jira-webhook-secret";
		String oauthClientSecret = "atlassian-oauth-client-secret";
		String withWebhook = "sha256=" + JiraWebhookSignature.hmacSha256Hex(body, webhookSecret);
		String withOauth = "sha256=" + JiraWebhookSignature.hmacSha256Hex(body, oauthClientSecret);
		assertTrue(JiraWebhookSignature.accepts(body, webhookSecret, withWebhook));
		assertFalse(JiraWebhookSignature.accepts(body, webhookSecret, withOauth));
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
