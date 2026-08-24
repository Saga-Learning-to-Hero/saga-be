package com.saga.be.entity.integration;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.WebhookReceiptStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "webhook_receipt",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_webhook_provider_delivery", columnNames = {"provider", "delivery_id"})
	},
	indexes = {
		@Index(name = "ix_webhook_status", columnList = "receipt_status, created_at")
	}
)
public class WebhookReceipt extends BaseEntity {

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", length = 32, nullable = false)
	private IntegrationProvider provider;

	@Column(name = "delivery_id", length = 128, nullable = false)
	private String deliveryId;

	@Column(name = "event_type", length = 128, nullable = false)
	private String eventType;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "target_id", columnDefinition = "char(36)")
	private UUID targetId;

	@Column(name = "payload_json", columnDefinition = "LONGTEXT")
	private String payloadJson;

	@Enumerated(EnumType.STRING)
	@Column(name = "receipt_status", length = 32, nullable = false)
	private WebhookReceiptStatus receiptStatus;

	@Column(name = "attempt_count", nullable = false)
	private Integer attemptCount;

	@Column(name = "processed_at")
	private LocalDateTime processedAt;

	@Column(name = "error_category", length = 64)
	private String errorCategory;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;
}
