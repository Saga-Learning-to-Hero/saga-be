package com.saga.be.push;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import java.util.Map;
import org.springframework.util.StringUtils;

public class FirebasePushNotificationSender implements PushNotificationSender {

	@FunctionalInterface
	interface MessageClient {
		String send(Message message) throws Exception;
	}

	private final MessageClient client;

	public FirebasePushNotificationSender(FirebaseMessaging messaging) {
		this(messaging::send);
	}

	FirebasePushNotificationSender(MessageClient client) {
		this.client = client;
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	@Override
	public void send(PushNotification notification) {
		if (notification == null || !StringUtils.hasText(notification.fcmToken())) {
			throw new PushSendException(FcmFailureCodes.TOKEN_MISSING, false, false, "FCM token is required.");
		}
		if (!StringUtils.hasText(notification.title()) || !StringUtils.hasText(notification.body())) {
			throw new PushSendException(FcmFailureCodes.INVALID_ARGUMENT, false, false, "FCM title and body are required.");
		}
		Message.Builder builder = Message.builder()
				.setToken(notification.fcmToken().trim())
				.setNotification(Notification.builder()
						.setTitle(notification.title())
						.setBody(notification.body())
						.build());
		for (Map.Entry<String, String> entry : FcmPayload.data(notification).entrySet()) {
			builder.putData(entry.getKey(), entry.getValue());
		}
		try {
			client.send(builder.build());
		} catch (FirebaseMessagingException ex) {
			throw classify(ex);
		} catch (PushSendException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new PushSendException(
					FcmFailureCodes.UNAVAILABLE, true, false, FcmFailureCodes.safeDetail(ex), ex);
		}
	}

	static PushSendException classify(FirebaseMessagingException ex) {
		MessagingErrorCode code = ex.getMessagingErrorCode();
		if (code == MessagingErrorCode.UNREGISTERED) {
			return new PushSendException(FcmFailureCodes.UNREGISTERED, false, true, "FCM token is unregistered.", ex);
		}
		if (code == MessagingErrorCode.SENDER_ID_MISMATCH) {
			return new PushSendException(
					FcmFailureCodes.SENDER_ID_MISMATCH, false, true, "FCM sender does not match the token.", ex);
		}
		if (code == MessagingErrorCode.UNAVAILABLE || code == MessagingErrorCode.INTERNAL) {
			return new PushSendException(
					code.name(), true, false, FcmFailureCodes.safeDetail(ex), ex);
		}
		if (code == MessagingErrorCode.QUOTA_EXCEEDED) {
			return new PushSendException(FcmFailureCodes.QUOTA_EXCEEDED, true, false, "FCM quota exceeded.", ex);
		}
		if (code == MessagingErrorCode.INVALID_ARGUMENT) {
			// Not token-specific: payload/data can also be invalid. firebase-admin 9.10.0 exposes
			// no structured field proving the registration token itself is dead.
			return new PushSendException(
					FcmFailureCodes.INVALID_ARGUMENT, false, false, FcmFailureCodes.safeDetail(ex), ex);
		}
		boolean retryable = code == null || code == MessagingErrorCode.THIRD_PARTY_AUTH_ERROR;
		return new PushSendException(
				code == null ? FcmFailureCodes.UNKNOWN : FcmFailureCodes.truncate(code.name()),
				retryable,
				false,
				FcmFailureCodes.safeDetail(ex),
				ex);
	}
}
