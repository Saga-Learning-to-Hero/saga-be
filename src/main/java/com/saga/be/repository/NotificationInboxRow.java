package com.saga.be.repository;

import com.saga.be.entity.enums.NotificationType;
import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationInboxRow(
		UUID id,
		NotificationType notificationType,
		String title,
		String message,
		String actionUrl,
		LocalDateTime readAt,
		LocalDateTime createdAt) {}
