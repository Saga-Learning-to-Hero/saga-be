package com.saga.be.dto.mail;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record EmailEnqueueRequest(
		String recipientEmail,
		UUID recipientUserId,
		String emailType,
		String templateKey,
		Map<String, Object> payload,
		LocalDateTime scheduledAt) {}
