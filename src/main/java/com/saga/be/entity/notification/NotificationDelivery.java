package com.saga.be.entity.notification;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.DeliveryStatus;
import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.entity.notification.UserNotification;
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
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "notification_delivery",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_notification_delivery_installation", columnNames = {"notification_id", "installation_id"})
	},
	indexes = {
		@Index(name = "ix_delivery_status", columnList = "delivery_status")
	}
)
public class NotificationDelivery extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "notification_id", nullable = false)
	private UserNotification notification;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "installation_id", nullable = false)
	private FirebaseInstallation installation;

	@Enumerated(EnumType.STRING)
	@Column(name = "delivery_status", length = 32, nullable = false)
	private DeliveryStatus deliveryStatus;

	@Column(name = "attempt_count", nullable = false)
	private Integer attemptCount;

	@Column(name = "last_attempt_at")
	private LocalDateTime lastAttemptAt;

	@Column(name = "processing_started_at")
	private LocalDateTime processingStartedAt;

	@Column(name = "sent_at")
	private LocalDateTime sentAt;

	@Column(name = "failure_code", length = 64)
	private String failureCode;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;
}
