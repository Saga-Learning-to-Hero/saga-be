package com.saga.be.service.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.SagaMailProperties;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.dto.mail.EmailOutboxRecord;
import com.saga.be.entity.enums.EmailDeliveryStatus;
import com.saga.be.mail.EmailSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EmailOutboxServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

	private InMemoryEmailOutboxStore store;
	private EmailOutboxService service;
	private EmailSender sender;
	private SagaMailProperties properties;

	@BeforeEach
	void setUp() {
		store = new InMemoryEmailOutboxStore();
		properties = new SagaMailProperties();
		properties.setMaxAttempts(3);
		properties.setRetryBackoff(Duration.ofSeconds(30));
		properties.setClaimStaleAfter(Duration.ofMinutes(5));
		properties.setBatchSize(20);
		service = new EmailOutboxService(
				store, new ObjectMapper(), properties, Clock.fixed(NOW, ZoneOffset.UTC));
		sender = Mockito.mock(EmailSender.class);
	}

	@Test
	void enqueueCreatesOnePendingRow() {
		EmailOutboxRecord row = service.enqueue(request("invite@example.com", "COURSE_INVITATION"));
		assertEquals(1, store.all().size());
		assertEquals(EmailDeliveryStatus.PENDING, row.deliveryStatus());
		assertEquals("invite@example.com", row.recipientEmail());
		assertEquals("COURSE_INVITATION", row.emailType());
		assertEquals(0, row.attemptCount());
		assertNotNull(row.id());
		assertNotNull(row.scheduledAt());
		Mockito.verifyNoInteractions(sender);
	}

	@Test
	void enqueueDoesNotCallSender() {
		service.enqueue(request("a@example.com", "DEV_SMOKE"));
		verify(sender, never()).send(Mockito.any());
		assertEquals(EmailDeliveryStatus.PENDING, store.all().getFirst().getDeliveryStatus());
	}

	@Test
	void successfulSendMarksSentAndSentAt() {
		EmailOutboxRecord queued = service.enqueue(request("ok@example.com", "DEV_SMOKE"));
		RecordingSender recording = new RecordingSender(true);
		EmailOutboxWorker worker = new EmailOutboxWorker(service, recording);
		assertEquals(1, worker.processBatch());
		EmailOutboxRecord sent = service.get(queued.id());
		assertEquals(EmailDeliveryStatus.SENT, sent.deliveryStatus());
		assertEquals(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), sent.sentAt());
		assertEquals(1, sent.attemptCount());
		assertNull(sent.lastFailureCode());
		assertEquals(1, recording.sends.get());
	}

	@Test
	void providerExceptionRetriesThenFailsAndIncrementsAttempts() {
		EmailOutboxRecord queued = service.enqueue(request("fail@example.com", "DEV_SMOKE"));
		EmailOutboxWorker worker = new EmailOutboxWorker(service, new RecordingSender(false));
		worker.processBatch();
		EmailOutboxRecord retry = service.get(queued.id());
		assertEquals(EmailDeliveryStatus.PENDING, retry.deliveryStatus());
		assertEquals(1, retry.attemptCount());
		assertEquals("SMTP_ERROR", retry.lastFailureCode());
		assertTrue(retry.scheduledAt().isAfter(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
		dueNow(queued.id());
		service.claim(queued.id());
		service.markFailure(queued.id(), "SMTP_ERROR");
		dueNow(queued.id());
		service.claim(queued.id());
		service.markFailure(queued.id(), "SMTP_ERROR");
		EmailOutboxRecord failed = service.get(queued.id());
		assertEquals(EmailDeliveryStatus.FAILED, failed.deliveryStatus());
		assertEquals(3, failed.attemptCount());
	}

	@Test
	void concurrentClaimAllowsOnlyOneWorkerToSend() {
		EmailOutboxRecord queued = service.enqueue(request("once@example.com", "DEV_SMOKE"));
		assertTrue(service.claim(queued.id()));
		assertEquals(false, service.claim(queued.id()));
		RecordingSender recording = new RecordingSender(true);
		EmailOutboxWorker worker = new EmailOutboxWorker(service, recording);
		assertEquals(false, worker.processOne(queued.id()));
		assertEquals(0, recording.sends.get());
		assertEquals(EmailDeliveryStatus.PROCESSING, service.get(queued.id()).deliveryStatus());
	}

	@Test
	void payloadSubjectIsRenderedWithoutExposingSecrets() {
		EmailOutboxRecord queued = service.enqueue(new EmailEnqueueRequest(
				"user@example.com",
				null,
				"PASSWORD_SETUP",
				"password-setup",
				Map.of("subject", "Set your password", "textBody", "Use the setup page.", "password", "should-not-send-raw"),
				null));
		var message = service.render(queued.id());
		assertEquals("Set your password", message.subject());
		assertEquals("Use the setup page.", message.textBody());
		assertNotEquals("should-not-send-raw", message.subject());
	}

	private void dueNow(UUID id) {
		store.findById(id).ifPresent(row -> row.setScheduledAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
	}

	@Test
	void disabledSenderLeavesRowPendingWithoutClaiming() {
		EmailOutboxRecord queued = service.enqueue(request("later@example.com", "DEV_SMOKE"));
		EmailOutboxWorker worker = new EmailOutboxWorker(service, new DisabledDuringTestSender());
		assertEquals(0, worker.processBatch());
		EmailOutboxRecord still = service.get(queued.id());
		assertEquals(EmailDeliveryStatus.PENDING, still.deliveryStatus());
		assertEquals(0, still.attemptCount());
	}

	private static EmailEnqueueRequest request(String to, String type) {
		return new EmailEnqueueRequest(
				to, null, type, "generic", Map.of("subject", "Hello", "textBody", "Body"), null);
	}

	private static final class DisabledDuringTestSender implements EmailSender {
		@Override
		public boolean isEnabled() {
			return false;
		}

		@Override
		public void send(com.saga.be.mail.EmailMessage message) {
			throw new AssertionError("disabled sender must not send");
		}
	}

	private static final class RecordingSender implements EmailSender {
		private final boolean succeed;
		private final AtomicInteger sends = new AtomicInteger();

		private RecordingSender(boolean succeed) {
			this.succeed = succeed;
		}

		@Override
		public boolean isEnabled() {
			return true;
		}

		@Override
		public void send(com.saga.be.mail.EmailMessage message) {
			sends.incrementAndGet();
			if (!succeed) {
				throw new com.saga.be.mail.EmailSendException("SMTP_ERROR", "Mail provider rejected the message.");
			}
		}
	}
}
