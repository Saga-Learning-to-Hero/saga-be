package com.saga.be.service.notification;

import com.saga.be.dto.notification.NotificationReadAllResponse;
import com.saga.be.dto.notification.UnreadNotificationCountResponse;
import com.saga.be.dto.notification.UserNotificationPageResponse;
import com.saga.be.dto.notification.UserNotificationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.notification.UserNotification;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.notification.NotificationCreatedEvent;
import com.saga.be.repository.NotificationInboxRow;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.repository.UserNotificationRepository;
import com.saga.be.web.RequestTiming;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Own-inbox queries plus the internal writer for future business flows. REST never creates
 * notifications. Rows are inserted in the caller's transaction; {@link NotificationCreatedEvent}
 * is delivered AFTER_COMMIT.
 */
@Service
@Profile("!test")
public class NotificationService {

	private final UserNotificationRepository notifications;
	private final UserAccountRepository users;
	private final ApplicationEventPublisher events;

	public NotificationService(
			UserNotificationRepository notifications, UserAccountRepository users, ApplicationEventPublisher events) {
		this.notifications = notifications;
		this.users = users;
		this.events = events;
	}

	@Transactional(readOnly = true)
	public UserNotificationPageResponse listOwn(UUID recipientUserId, Integer page, Integer size, Boolean unreadOnly) {
		return RequestTiming.record("listOwnNotifications", () -> {
			int pageNumber = NotificationPaging.page(page);
			int pageSize = NotificationPaging.size(size);
			boolean unread = Boolean.TRUE.equals(unreadOnly);
			Page<NotificationInboxRow> rows =
					notifications.findInbox(recipientUserId, unread, PageRequest.of(pageNumber, pageSize));
			return new UserNotificationPageResponse(
					rows.getContent().stream().map(NotificationService::toResponse).toList(),
					pageNumber,
					pageSize,
					rows.getTotalElements());
		});
	}

	@Transactional(readOnly = true)
	public UnreadNotificationCountResponse unreadCount(UUID recipientUserId) {
		return RequestTiming.record(
				"countUnreadNotifications",
				() -> new UnreadNotificationCountResponse(
						notifications.countByRecipientUser_IdAndReadAtIsNull(recipientUserId)));
	}

	@Transactional
	public UserNotificationResponse markRead(UUID recipientUserId, UUID notificationId) {
		return RequestTiming.record("markNotificationRead", () -> {
			UserNotification row = notifications
					.findByIdAndRecipientUser_Id(notificationId, recipientUserId)
					.orElseThrow(NotificationService::notFound);
			if (row.getReadAt() == null) {
				row.setReadAt(LocalDateTime.now());
				notifications.save(row);
			}
			return toResponse(row);
		});
	}

	@Transactional
	public NotificationReadAllResponse markAllRead(UUID recipientUserId) {
		return RequestTiming.record("markAllNotificationsRead", () -> {
			LocalDateTime readAt = LocalDateTime.now();
			int updated = notifications.markAllUnreadAsRead(recipientUserId, readAt);
			return new NotificationReadAllResponse(updated, readAt);
		});
	}

	/**
	 * Internal only. Persists one inbox row for {@code recipientUserId}. Non-null {@code eventKey}
	 * is unique per recipient; a concurrent duplicate returns the existing row and does not emit
	 * another SSE event.
	 *
	 * <p>Do not catch unique-constraint failures as control flow. A flush-time constraint violation
	 * marks the JPA transaction rollback-only, so a swallowed duplicate would later fail the whole
	 * business transaction with {@code UnexpectedRollbackException}. Competing writers take
	 * {@code SELECT ... FOR UPDATE} on the recipient {@code user_account} row (MySQL/InnoDB row
	 * lock, works across instances), then check-and-insert. The unique constraint remains the last
	 * line of defense and must fail the transaction if it still fires.
	 */
	@Transactional
	public UserNotificationResponse createNotification(
			UUID recipientUserId,
			NotificationType notificationType,
			String title,
			String message,
			String actionUrl,
			String eventKey) {
		if (recipientUserId == null) {
			throw invalid("recipient is required.");
		}
		if (notificationType == null) {
			throw invalid("notification type is required.");
		}
		String trimmedTitle = requireText(title, 160, "title");
		String trimmedMessage = requireText(message, 1000, "message");
		String trimmedActionUrl = optionalText(actionUrl, 500, "actionUrl");
		String trimmedEventKey = optionalText(eventKey, 255, "eventKey");
		UserAccount recipient = users.findByIdForUpdate(recipientUserId).orElseThrow(() -> new AcademicException(
				AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "User was not found."));
		if (trimmedEventKey != null) {
			UserNotification existing =
					notifications.findByRecipientUser_IdAndEventKey(recipientUserId, trimmedEventKey).orElse(null);
			if (existing != null) {
				return toResponse(existing);
			}
		}
		UserNotification row = new UserNotification();
		row.setRecipientUser(recipient);
		row.setNotificationType(notificationType);
		row.setTitle(trimmedTitle);
		row.setMessage(trimmedMessage);
		row.setActionUrl(trimmedActionUrl);
		row.setEventKey(trimmedEventKey);
		notifications.save(row);
		events.publishEvent(new NotificationCreatedEvent(recipientUserId, row.getId(), Instant.now()));
		return toResponse(row);
	}

	private static UserNotificationResponse toResponse(NotificationInboxRow row) {
		return new UserNotificationResponse(
				row.id(),
				row.notificationType() == null ? null : row.notificationType().name(),
				row.title(),
				row.message(),
				row.actionUrl(),
				row.readAt(),
				row.createdAt());
	}

	private static UserNotificationResponse toResponse(UserNotification row) {
		return new UserNotificationResponse(
				row.getId(),
				row.getNotificationType() == null ? null : row.getNotificationType().name(),
				row.getTitle(),
				row.getMessage(),
				row.getActionUrl(),
				row.getReadAt(),
				row.getCreatedAt());
	}

	private static String requireText(String value, int maxLength, String field) {
		if (!StringUtils.hasText(value)) {
			throw invalid(field + " is required.");
		}
		String trimmed = value.trim();
		if (trimmed.length() > maxLength) {
			throw invalid(field + " is too long.");
		}
		return trimmed;
	}

	private static String optionalText(String value, int maxLength, String field) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.length() > maxLength) {
			throw invalid(field + " is too long.");
		}
		return trimmed;
	}

	private static AcademicException notFound() {
		return new AcademicException(
				AcademicErrorCode.NOTIFICATION_NOT_FOUND, HttpStatus.NOT_FOUND, "Notification was not found.");
	}

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST, message);
	}
}
