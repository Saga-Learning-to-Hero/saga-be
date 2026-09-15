package com.saga.be.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after a user_notification row is inserted in the current transaction. Listeners must
 * not run until AFTER_COMMIT so SSE never races the durable row.
 */
public record NotificationCreatedEvent(UUID recipientUserId, UUID notificationId, Instant occurredAt) {}
