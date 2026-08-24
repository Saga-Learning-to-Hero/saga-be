package com.saga.be.entity.infra;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.UUID;
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
	name = "outbox_event",
	indexes = {
		@Index(name = "ix_outbox_status_available", columnList = "status, available_at")
	}
)
public class OutboxEvent extends BaseEntity {

	@Column(name = "aggregate_type", length = 64, nullable = false)
	private String aggregateType;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "aggregate_id", columnDefinition = "char(36)", nullable = false)
	private UUID aggregateId;

	@Column(name = "event_type", length = 128, nullable = false)
	private String eventType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", columnDefinition = "json", nullable = false)
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 32, nullable = false)
	private OutboxStatus status;

	@Column(name = "attempt_count", nullable = false)
	private Integer attemptCount;

	@Column(name = "available_at", nullable = false)
	private LocalDateTime availableAt;

	@Column(name = "processed_at")
	private LocalDateTime processedAt;

	@Column(name = "last_error", length = 1000)
	private String lastError;
}
