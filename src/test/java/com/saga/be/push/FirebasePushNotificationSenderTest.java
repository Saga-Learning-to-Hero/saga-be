package com.saga.be.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.firebase.ErrorCode;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.saga.be.entity.enums.PushPlatform;
import java.lang.reflect.Constructor;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class FirebasePushNotificationSenderTest {

	@Test
	void sendPassesBuiltMessageToClient() throws Exception {
		FirebasePushNotificationSender.MessageClient client = Mockito.mock(FirebasePushNotificationSender.MessageClient.class);
		when(client.send(any(Message.class))).thenReturn("projects/x/messages/1");
		FirebasePushNotificationSender sender = new FirebasePushNotificationSender(client);
		assertTrue(sender.isEnabled());
		UUID id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		sender.send(new PushNotification(
				"fcm-token-secret", "Hello", "Body text", id, "SYSTEM", "/inbox", PushPlatform.WEB));
		ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
		verify(client).send(captor.capture());
		assertTrue(captor.getValue() != null);
	}

	@Test
	void missingTokenDoesNotEchoSecret() {
		FirebasePushNotificationSender sender = new FirebasePushNotificationSender(message -> "ok");
		PushSendException ex = assertThrows(
				PushSendException.class,
				() -> sender.send(new PushNotification(
						"  ", "Hello", "Body", UUID.randomUUID(), "SYSTEM", null, PushPlatform.WEB)));
		assertFalse(ex.isTokenInvalid());
		assertFalse(ex.isRetryable());
		assertEquals(FcmFailureCodes.TOKEN_MISSING, ex.getFailureCode());
		assertFalse(ex.getMessage().contains("fcm-token-secret"));
	}

	@Test
	void unregisteredIsPermanentTokenFailure() {
		PushSendException ex = FirebasePushNotificationSender.classify(
				messagingException(MessagingErrorCode.UNREGISTERED, "Requested entity was not found."));
		assertTrue(ex.isTokenInvalid());
		assertFalse(ex.isRetryable());
		assertEquals(FcmFailureCodes.UNREGISTERED, ex.getFailureCode());
	}

	@Test
	void senderIdMismatchIsPermanentTokenFailure() {
		PushSendException ex = FirebasePushNotificationSender.classify(
				messagingException(MessagingErrorCode.SENDER_ID_MISMATCH, "SenderId mismatch"));
		assertTrue(ex.isTokenInvalid());
		assertFalse(ex.isRetryable());
		assertEquals(FcmFailureCodes.SENDER_ID_MISMATCH, ex.getFailureCode());
	}

	@Test
	void invalidArgumentIsTerminalButNotTokenFailureEvenIfMessageMentionsToken() {
		PushSendException ex = FirebasePushNotificationSender.classify(messagingException(
				MessagingErrorCode.INVALID_ARGUMENT,
				"The registration token is not a valid FCM registration token"));
		assertFalse(ex.isTokenInvalid());
		assertFalse(ex.isRetryable());
		assertEquals(FcmFailureCodes.INVALID_ARGUMENT, ex.getFailureCode());
	}

	@Test
	void payloadInvalidArgumentIsTerminalAndNotTokenFailure() throws Exception {
		FirebasePushNotificationSender.MessageClient client = Mockito.mock(FirebasePushNotificationSender.MessageClient.class);
		when(client.send(any(Message.class)))
				.thenThrow(messagingException(
						MessagingErrorCode.INVALID_ARGUMENT, "Invalid data payload key"));
		FirebasePushNotificationSender sender = new FirebasePushNotificationSender(client);
		PushSendException ex = assertThrows(
				PushSendException.class,
				() -> sender.send(new PushNotification(
						"keep-this-token", "Hello", "Body", UUID.randomUUID(), "SYSTEM", "/inbox", PushPlatform.WEB)));
		assertFalse(ex.isTokenInvalid());
		assertFalse(ex.isRetryable());
		assertEquals(FcmFailureCodes.INVALID_ARGUMENT, ex.getFailureCode());
	}

	private static FirebaseMessagingException messagingException(MessagingErrorCode code, String message) {
		try {
			Constructor<FirebaseMessagingException> constructor = FirebaseMessagingException.class.getDeclaredConstructor(
					ErrorCode.class, String.class, Throwable.class, IncomingHttpResponse.class, MessagingErrorCode.class);
			constructor.setAccessible(true);
			return constructor.newInstance(ErrorCode.INVALID_ARGUMENT, message, null, null, code);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Unable to construct FirebaseMessagingException for tests.", ex);
		}
	}
}
