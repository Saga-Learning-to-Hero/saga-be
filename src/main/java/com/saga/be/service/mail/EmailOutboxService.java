package com.saga.be.service.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.SagaMailProperties;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.dto.mail.EmailOutboxRecord;
import com.saga.be.entity.enums.EmailDeliveryStatus;
import com.saga.be.entity.notification.EmailOutbox;
import com.saga.be.mail.EmailFailureCodes;
import com.saga.be.mail.EmailMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Profile("!test")
public class EmailOutboxService {

	private final EmailOutboxStore store;
	private final ObjectMapper mapper;
	private final SagaMailProperties properties;
	private final Clock clock;

	@Autowired
	public EmailOutboxService(EmailOutboxStore store, ObjectMapper mapper, SagaMailProperties properties) {
		this(store, mapper, properties, Clock.systemDefaultZone());
	}

	public EmailOutboxService(EmailOutboxStore store, ObjectMapper mapper, SagaMailProperties properties, Clock clock) {
		this.store = store;
		this.mapper = mapper;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public EmailOutboxRecord enqueue(EmailEnqueueRequest request) {
		if (request == null || !StringUtils.hasText(request.recipientEmail())) {
			throw new IllegalArgumentException("Recipient email is required.");
		}
		if (!StringUtils.hasText(request.emailType())) {
			throw new IllegalArgumentException("Email type is required.");
		}
		EmailOutbox row = new EmailOutbox();
		row.setRecipientEmail(request.recipientEmail().trim());
		if (request.recipientUserId() != null) {
			store.findUser(request.recipientUserId()).ifPresent(row::setRecipientUser);
		}
		row.setEmailType(trimTo(request.emailType(), 64));
		row.setTemplateKey(request.templateKey() == null ? null : trimTo(request.templateKey(), 128));
		row.setPayloadJson(writePayload(request.payload()));
		row.setDeliveryStatus(EmailDeliveryStatus.PENDING);
		row.setAttemptCount(0);
		row.setScheduledAt(request.scheduledAt() != null ? request.scheduledAt() : now());
		return toRecord(store.save(row));
	}

	@Transactional(readOnly = true)
	public EmailOutboxRecord get(UUID id) {
		return store.findById(id).map(this::toRecord).orElse(null);
	}

	List<UUID> findClaimableIds() {
		LocalDateTime current = now();
		return store.findClaimableIds(current, staleBefore(current), properties.getBatchSize());
	}

	@Transactional
	public boolean claim(UUID id) {
		LocalDateTime current = now();
		return store.claim(id, current, staleBefore(current));
	}

	@Transactional(readOnly = true)
	EmailMessage render(UUID id) {
		EmailOutbox row = store.findById(id).orElse(null);
		if (row == null) {
			return null;
		}
		JsonNode payload = readPayload(row.getPayloadJson());
		String subject = firstText(payload, "subject");
		if (!StringUtils.hasText(subject)) {
			subject = StringUtils.hasText(row.getTemplateKey()) ? row.getTemplateKey() : row.getEmailType();
		}
		String textBody = firstText(payload, "textBody");
		String htmlBody = firstText(payload, "htmlBody");
		if (!StringUtils.hasText(textBody)) {
			textBody = firstText(payload, "summary");
		}
		if (!StringUtils.hasText(textBody) && !StringUtils.hasText(htmlBody)) {
			textBody = row.getEmailType();
		}
		return new EmailMessage(row.getRecipientEmail(), subject, textBody, htmlBody);
	}

	@Transactional
	public void markSent(UUID id) {
		store.findById(id).ifPresent(row -> {
			row.setDeliveryStatus(EmailDeliveryStatus.SENT);
			row.setSentAt(now());
			row.setLastFailureCode(null);
			store.save(row);
		});
	}

	@Transactional
	public void markFailure(UUID id, String failureCode) {
		store.findById(id).ifPresent(row -> {
			int attempts = row.getAttemptCount() == null ? 0 : row.getAttemptCount();
			row.setLastFailureCode(EmailFailureCodes.truncate(failureCode));
			row.setLastAttemptAt(now());
			if (attempts >= properties.getMaxAttempts()) {
				row.setDeliveryStatus(EmailDeliveryStatus.FAILED);
			} else {
				row.setDeliveryStatus(EmailDeliveryStatus.PENDING);
				long multiplier = 1L << Math.min(Math.max(attempts, 1) - 1, 6);
				Duration backoff = properties.getRetryBackoff().multipliedBy(multiplier);
				if (backoff.compareTo(Duration.ofHours(1)) > 0) {
					backoff = Duration.ofHours(1);
				}
				row.setScheduledAt(now().plus(backoff));
			}
			store.save(row);
		});
	}

	private LocalDateTime staleBefore(LocalDateTime current) {
		return current.minus(properties.getClaimStaleAfter());
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

	private String writePayload(Map<String, Object> payload) {
		Map<String, Object> data = payload == null ? Map.of() : new LinkedHashMap<>(payload);
		try {
			return mapper.writeValueAsString(data);
		} catch (Exception ex) {
			return "{}";
		}
	}

	private JsonNode readPayload(String json) {
		if (!StringUtils.hasText(json)) {
			return mapper.createObjectNode();
		}
		try {
			return mapper.readTree(json);
		} catch (Exception ex) {
			return mapper.createObjectNode();
		}
	}

	private static String firstText(JsonNode payload, String field) {
		if (payload == null || !payload.has(field) || payload.get(field).isNull()) {
			return null;
		}
		String value = payload.get(field).asText();
		return StringUtils.hasText(value) ? value : null;
	}

	private static String trimTo(String value, int max) {
		String trimmed = value.trim();
		return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
	}

	private EmailOutboxRecord toRecord(EmailOutbox row) {
		int attempts = row.getAttemptCount() == null ? 0 : row.getAttemptCount();
		return new EmailOutboxRecord(
				row.getId(),
				row.getRecipientEmail(),
				row.getEmailType(),
				row.getTemplateKey(),
				row.getDeliveryStatus(),
				attempts,
				row.getScheduledAt(),
				row.getSentAt(),
				row.getLastFailureCode(),
				row.getLastAttemptAt(),
				row.getCreatedAt(),
				row.getUpdatedAt());
	}
}
