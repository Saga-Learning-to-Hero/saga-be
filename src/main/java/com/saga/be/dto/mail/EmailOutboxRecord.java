package com.saga.be.dto.mail;

import com.saga.be.entity.enums.EmailDeliveryStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record EmailOutboxRecord(
		UUID id,
		String recipientEmail,
		String emailType,
		String templateKey,
		EmailDeliveryStatus deliveryStatus,
		int attemptCount,
		LocalDateTime scheduledAt,
		LocalDateTime sentAt,
		String lastFailureCode,
		LocalDateTime lastAttemptAt,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {}
