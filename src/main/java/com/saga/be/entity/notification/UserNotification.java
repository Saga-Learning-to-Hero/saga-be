package com.saga.be.entity.notification;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.notification.NotificationBroadcast;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "user_notification",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_user_notification_broadcast_recipient", columnNames = {"broadcast_id", "recipient_user_id"}),
		@UniqueConstraint(name = "uk_user_notification_recipient_event", columnNames = {"recipient_user_id", "event_key"})
	},
	indexes = {
		@Index(name = "ix_user_notification_inbox", columnList = "recipient_user_id, created_at")
	}
)
public class UserNotification extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "recipient_user_id", nullable = false)
	private UserAccount recipientUser;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "broadcast_id", nullable = true)
	private NotificationBroadcast broadcast;

	@Enumerated(EnumType.STRING)
	@Column(name = "notification_type", length = 64, nullable = false)
	private NotificationType notificationType;

	@Column(name = "title", length = 160, nullable = false)
	private String title;

	@Column(name = "message", length = 1000, nullable = false)
	private String message;

	@Column(name = "action_url", length = 500)
	private String actionUrl;

	@Column(name = "event_key", length = 255)
	private String eventKey;

	@Column(name = "read_at")
	private LocalDateTime readAt;
}
