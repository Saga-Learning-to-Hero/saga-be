package com.saga.be.push;

import com.saga.be.entity.enums.PushPlatform;
import java.util.UUID;

public record PushNotification(
		String fcmToken,
		String title,
		String body,
		UUID notificationId,
		String notificationType,
		String actionUrl,
		PushPlatform platform) {}
