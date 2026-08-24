package com.saga.be.entity.notification;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.BroadcastAudience;
import com.saga.be.entity.enums.BroadcastStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
	name = "notification_broadcast",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_broadcast_sender_key", columnNames = {"sender_user_id", "idempotency_key"})
	}
)
public class NotificationBroadcast extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sender_user_id", nullable = false)
	private UserAccount senderUser;

	@Enumerated(EnumType.STRING)
	@Column(name = "audience", length = 64, nullable = false)
	private BroadcastAudience audience;

	@Column(name = "title", length = 160, nullable = false)
	private String title;

	@Column(name = "message", length = 1000, nullable = false)
	private String message;

	@Column(name = "idempotency_key", length = 128, nullable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", length = 64, nullable = false)
	private String requestFingerprint;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 32, nullable = false)
	private BroadcastStatus status;

	@Column(name = "recipient_count", nullable = false)
	private Integer recipientCount;

	@Column(name = "notification_count", nullable = false)
	private Integer notificationCount;

	@Column(name = "delivery_queued_count", nullable = false)
	private Integer deliveryQueuedCount;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;
}
