package com.saga.be.entity.integration;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
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

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "sync_job_log",
	indexes = {
		@Index(name = "ix_sync_job_status", columnList = "status, started_at")
	}
)
public class SyncJobLog extends BaseEntity {

	@Column(name = "target_system", length = 32)
	private String targetSystem;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "target_id", columnDefinition = "char(36)")
	private UUID targetId;

	@Enumerated(EnumType.STRING)
	@Column(name = "job_type", length = 64)
	private SyncJobType jobType;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 32)
	private SyncJobStatus status;

	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	@Column(name = "error_category", length = 128)
	private String errorCategory;

	@Column(name = "failure_stage", length = 64)
	private String failureStage;

	@Column(name = "started_at")
	private LocalDateTime startedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "items_processed")
	private Integer itemsProcessed;

	@Column(name = "items_failed")
	private Integer itemsFailed;

	@Column(name = "cursor_before")
	private LocalDateTime cursorBefore;

	@Column(name = "cursor_after")
	private LocalDateTime cursorAfter;
}
