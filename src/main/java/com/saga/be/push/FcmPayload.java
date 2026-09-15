package com.saga.be.push;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Small FCM data map. {@code actionUrl} is an application navigation target for the service worker,
 * never fetched or executed by this server.
 */
public final class FcmPayload {

	private FcmPayload() {}

	public static Map<String, String> data(PushNotification notification) {
		Map<String, String> data = new LinkedHashMap<>();
		if (notification.notificationId() != null) {
			data.put("notificationId", notification.notificationId().toString());
		}
		if (StringUtils.hasText(notification.notificationType())) {
			data.put("notificationType", notification.notificationType().trim());
		}
		String actionUrl = navigationTarget(notification.actionUrl());
		if (actionUrl != null) {
			data.put("actionUrl", actionUrl);
		}
		return data;
	}

	public static String navigationTarget(String actionUrl) {
		if (!StringUtils.hasText(actionUrl)) {
			return null;
		}
		String trimmed = actionUrl.trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (lower.startsWith("javascript:")
				|| lower.startsWith("data:")
				|| lower.startsWith("vbscript:")
				|| lower.startsWith("file:")) {
			return null;
		}
		return trimmed;
	}
}
