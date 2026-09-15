package com.saga.be.push;

/**
 * Internal FCM send port. Notification domain code must not call {@code FirebaseMessaging} directly.
 */
public interface PushNotificationSender {

	boolean isEnabled();

	void send(PushNotification notification);
}
