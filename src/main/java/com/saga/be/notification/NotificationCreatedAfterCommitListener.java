package com.saga.be.notification;

import com.saga.be.realtime.UserSseHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Signals this instance's user SSE after the inbox row commits. Process-local only; missed events
 * are recovered by REST fetch. SSE failure must not delete or roll back the notification.
 */
@Component
public class NotificationCreatedAfterCommitListener {

	private static final Logger log = LoggerFactory.getLogger(NotificationCreatedAfterCommitListener.class);

	private final UserSseHub userSse;

	public NotificationCreatedAfterCommitListener(UserSseHub userSse) {
		this.userSse = userSse;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onNotificationCreated(NotificationCreatedEvent event) {
		if (event == null || event.recipientUserId() == null || event.notificationId() == null) {
			return;
		}
		try {
			userSse.notifyCreated(event.recipientUserId(), event.notificationId(), event.occurredAt());
		} catch (RuntimeException ex) {
			log.error("notification method=SSE result=failure category=NOTIFICATION_CREATED", ex);
		}
	}
}
