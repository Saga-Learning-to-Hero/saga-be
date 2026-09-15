package com.saga.be.notification;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.saga.be.realtime.UserSseHub;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@ExtendWith(MockitoExtension.class)
class NotificationCreatedAfterCommitListenerTest {

	@Mock
	private UserSseHub userSse;

	@Test
	void sseFailureIsSwallowedAndDoesNotRethrow() {
		UUID userId = UUID.randomUUID();
		UUID notificationId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-09-16T00:00:00Z");
		doThrow(new IllegalStateException("sse down"))
				.when(userSse)
				.notifyCreated(userId, notificationId, occurredAt);
		NotificationCreatedAfterCommitListener listener = new NotificationCreatedAfterCommitListener(userSse);
		listener.onNotificationCreated(new NotificationCreatedEvent(userId, notificationId, occurredAt));
		verify(userSse).notifyCreated(userId, notificationId, occurredAt);
	}

	@Test
	void nullEventIsIgnored() {
		NotificationCreatedAfterCommitListener listener = new NotificationCreatedAfterCommitListener(userSse);
		listener.onNotificationCreated(null);
		verify(userSse, never())
				.notifyCreated(
						org.mockito.ArgumentMatchers.any(),
						org.mockito.ArgumentMatchers.any(),
						org.mockito.ArgumentMatchers.any());
	}

	@Test
	void listenerRunsAfterCommitOnly() throws Exception {
		Method method = NotificationCreatedAfterCommitListener.class.getMethod(
				"onNotificationCreated", NotificationCreatedEvent.class);
		TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);
		org.junit.jupiter.api.Assertions.assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
	}
}
