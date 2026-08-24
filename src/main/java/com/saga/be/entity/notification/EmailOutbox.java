package com.saga.be.entity.notification;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.EmailDeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "email_outbox",
	indexes = {
		@Index(name = "ix_email_outbox_status", columnList = "delivery_status, scheduled_at")
	}
)
public class EmailOutbox extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "recipient_user_id", nullable = true)
	private UserAccount recipientUser;

	@Column(name = "recipient_email", length = 255, nullable = false)
	private String recipientEmail;

	@Column(name = "email_type", length = 64, nullable = false)
	private String emailType;

	@Column(name = "template_key", length = 128)
	private String templateKey;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload_json", columnDefinition = "json")
	private String payloadJson;

	@Enumerated(EnumType.STRING)
	@Column(name = "delivery_status", length = 32, nullable = false)
	private EmailDeliveryStatus deliveryStatus;

	@Column(name = "attempt_count", nullable = false)
	private Integer attemptCount;

	@Column(name = "scheduled_at")
	private LocalDateTime scheduledAt;

	@Column(name = "sent_at")
	private LocalDateTime sentAt;

	@Column(name = "last_failure_code", length = 64)
	private String lastFailureCode;

	@Column(name = "last_attempt_at")
	private LocalDateTime lastAttemptAt;
}
