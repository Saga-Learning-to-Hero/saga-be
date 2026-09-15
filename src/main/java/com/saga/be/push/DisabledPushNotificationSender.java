package com.saga.be.push;

public class DisabledPushNotificationSender implements PushNotificationSender {

	@Override
	public boolean isEnabled() {
		return false;
	}

	@Override
	public void send(PushNotification notification) {
		throw new PushSendException(
				FcmFailureCodes.FCM_DISABLED, false, false, "FCM sender is disabled.");
	}
}
