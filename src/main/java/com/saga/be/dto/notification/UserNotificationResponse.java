package com.saga.be.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Own inbox item. No recipient, event key, or broadcast internals.")
public record UserNotificationResponse(
		UUID id,
		String notificationType,
		String title,
		String message,
		String actionUrl,
		LocalDateTime readAt,
		LocalDateTime createdAt) {}
