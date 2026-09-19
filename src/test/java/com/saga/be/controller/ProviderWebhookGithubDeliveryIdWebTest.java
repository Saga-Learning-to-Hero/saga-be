package com.saga.be.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.WebhookReceiptStatus;
import com.saga.be.entity.integration.WebhookReceipt;
import com.saga.be.exception.GlobalExceptionHandler;
import com.saga.be.integration.github.GitHubWebhookSignature;
import com.saga.be.repository.WebhookReceiptRepository;
import com.saga.be.service.attribution.AttributionWarningService;
import com.saga.be.service.jira.JiraEvidenceJobExecutor;
import com.saga.be.service.jira.JiraIssueEvidenceSyncService;
import com.saga.be.service.projection.ProviderWebhookProjectionService;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ProviderWebhookGithubDeliveryIdWebTest {

	private static final String SECRET = "whsec";
	private static final byte[] BODY = "{\"zen\":\"ok\"}".getBytes(StandardCharsets.UTF_8);

	@Mock
	private WebhookReceiptRepository receipts;
	@Mock
	private AttributionWarningService warnings;
	@Mock
	private ProviderWebhookProjectionService projection;
	@Mock
	private JiraIssueEvidenceSyncService jiraEvidence;
	@Mock
	private JiraEvidenceJobExecutor jiraEvidenceJobs;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		IntegrationProperties properties = new IntegrationProperties();
		properties.getGithub().setWebhookSecret(SECRET);
		mockMvc = MockMvcBuilders.standaloneSetup(
						new ProviderWebhookController(
								properties, receipts, warnings, projection, jiraEvidence, jiraEvidenceJobs))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void missingGithubDeliveryWithValidSignatureIs400AndWritesNothing() throws Exception {
		mockMvc.perform(post("/api/webhooks/github")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", signature())
						.header("X-GitHub-Event", "push")
						.content(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.message").value("X-GitHub-Delivery header is required."));
		assertNoGithubSideEffects();
	}

	@Test
	void blankGithubDeliveryIs400AndWritesNothing() throws Exception {
		mockMvc.perform(post("/api/webhooks/github")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", signature())
						.header("X-GitHub-Delivery", "")
						.header("X-GitHub-Event", "push")
						.content(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.message").value("X-GitHub-Delivery header is required."));
		assertNoGithubSideEffects();
	}

	@Test
	void whitespaceOnlyGithubDeliveryIs400AndWritesNothing() throws Exception {
		mockMvc.perform(post("/api/webhooks/github")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", signature())
						.header("X-GitHub-Delivery", "   ")
						.header("X-GitHub-Event", "push")
						.content(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
		assertNoGithubSideEffects();
	}

	@Test
	void paddedGithubDeliveryIsAcceptedAndStoredTrimmed() throws Exception {
		when(receipts.findByProviderAndDeliveryId(IntegrationProvider.GITHUB, "abc-123"))
				.thenReturn(Optional.empty());
		when(receipts.save(any(WebhookReceipt.class))).thenAnswer(invocation -> {
			WebhookReceipt row = invocation.getArgument(0);
			row.setId(UUID.randomUUID());
			return row;
		});
		mockMvc.perform(post("/api/webhooks/github")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", signature())
						.header("X-GitHub-Delivery", "  abc-123  ")
						.header("X-GitHub-Event", "push")
						.content(BODY))
				.andExpect(status().isAccepted());
		ArgumentCaptor<WebhookReceipt> saved = ArgumentCaptor.forClass(WebhookReceipt.class);
		verify(receipts).save(saved.capture());
		assertThat(saved.getValue().getDeliveryId()).isEqualTo("abc-123");
	}

	@Test
	void validGithubDeliveryIngestsAndProjects() throws Exception {
		when(receipts.findByProviderAndDeliveryId(IntegrationProvider.GITHUB, "abc-123"))
				.thenReturn(Optional.empty());
		when(receipts.save(any(WebhookReceipt.class))).thenAnswer(invocation -> {
			WebhookReceipt row = invocation.getArgument(0);
			row.setId(UUID.randomUUID());
			return row;
		});
		mockMvc.perform(post("/api/webhooks/github")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", signature())
						.header("X-GitHub-Delivery", "abc-123")
						.header("X-GitHub-Event", "push")
						.content(BODY))
				.andExpect(status().isAccepted());
		ArgumentCaptor<WebhookReceipt> saved = ArgumentCaptor.forClass(WebhookReceipt.class);
		verify(receipts).save(saved.capture());
		assertThat(saved.getValue().getDeliveryId()).isEqualTo("abc-123");
		assertThat(saved.getValue().getReceiptStatus()).isEqualTo(WebhookReceiptStatus.RECEIVED);
		verify(projection).projectGithub(any(WebhookReceipt.class), eq("push"), eq(new String(BODY, StandardCharsets.UTF_8)));
	}

	@Test
	void duplicateValidGithubDeliveryDoesNotReproject() throws Exception {
		WebhookReceipt existing = new WebhookReceipt();
		existing.setId(UUID.randomUUID());
		existing.setProvider(IntegrationProvider.GITHUB);
		existing.setDeliveryId("abc-123");
		existing.setEventType("push");
		existing.setReceiptStatus(WebhookReceiptStatus.PROCESSED);
		existing.setAttemptCount(1);
		existing.setVersion(0L);
		when(receipts.findByProviderAndDeliveryId(IntegrationProvider.GITHUB, "abc-123"))
				.thenReturn(Optional.of(existing));
		when(receipts.save(existing)).thenReturn(existing);
		mockMvc.perform(post("/api/webhooks/github")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", signature())
						.header("X-GitHub-Delivery", "abc-123")
						.header("X-GitHub-Event", "push")
						.content(BODY))
				.andExpect(status().isAccepted());
		verify(receipts).save(existing);
		assertThat(existing.getReceiptStatus()).isEqualTo(WebhookReceiptStatus.DUPLICATE);
		verify(projection, never()).projectGithub(any(), any(), any());
	}

	@Test
	void invalidSignatureWithDeliveryPresentIs401AndDoesNotIngest() throws Exception {
		mockMvc.perform(post("/api/webhooks/github")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", "sha256=deadbeef")
						.header("X-GitHub-Delivery", "abc-123")
						.header("X-GitHub-Event", "push")
						.content(BODY))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
		verify(warnings).securityFailure(eq("github-sig:abc-123"), eq("Invalid GitHub webhook signature."));
		assertNoGithubSideEffects();
	}

	@Test
	void jiraMissingIdentifierStillUsesBodyHashFallback() throws Exception {
		byte[] jiraBody = "{\"webhookEvent\":\"jira:issue_updated\"}".getBytes(StandardCharsets.UTF_8);
		String expectedDelivery = java.util.UUID.nameUUIDFromBytes(jiraBody).toString();
		when(receipts.findByProviderAndDeliveryId(IntegrationProvider.JIRA, expectedDelivery))
				.thenReturn(Optional.empty());
		when(receipts.save(any(WebhookReceipt.class))).thenAnswer(invocation -> {
			WebhookReceipt row = invocation.getArgument(0);
			row.setId(UUID.randomUUID());
			return row;
		});
		mockMvc.perform(post("/api/webhooks/jira")
						.contentType(MediaType.APPLICATION_JSON)
						.content(jiraBody))
				.andExpect(status().isAccepted());
		ArgumentCaptor<WebhookReceipt> saved = ArgumentCaptor.forClass(WebhookReceipt.class);
		verify(receipts).save(saved.capture());
		assertThat(saved.getValue().getProvider()).isEqualTo(IntegrationProvider.JIRA);
		assertThat(saved.getValue().getDeliveryId()).isEqualTo(expectedDelivery);
		verify(projection).projectJira(any(WebhookReceipt.class), eq(new String(jiraBody, StandardCharsets.UTF_8)));
		verify(jiraEvidenceJobs).submit(any());
	}

	private void assertNoGithubSideEffects() {
		verify(receipts, never()).save(any());
		verify(receipts, never()).findByProviderAndDeliveryId(any(), any());
		verify(projection, never()).projectGithub(any(), any(), any());
	}

	private static String signature() {
		return "sha256=" + GitHubWebhookSignature.hmacSha256Hex(BODY, SECRET);
	}
}
