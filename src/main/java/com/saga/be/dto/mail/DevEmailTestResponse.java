package com.saga.be.dto.mail;

import com.saga.be.entity.enums.EmailDeliveryStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record DevEmailTestResponse(
		UUID outboxId,
		EmailDeliveryStatus deliveryStatus,
		int attemptCount,
		String lastFailureCode,
		LocalDateTime sentAt,
		boolean mailEnabled) {}
