package com.saga.be.entity.jira;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.JiraWriteOperationStatus;
import com.saga.be.entity.enums.JiraWriteOperationType;
import com.saga.be.entity.project.Project;
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
	name = "jira_write_operation",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_jira_write_project_key", columnNames = {"project_id", "idempotency_key"})
	}
)
public class JiraWriteOperation extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "actor_user_id", nullable = false)
	private UserAccount actorUser;

	@Enumerated(EnumType.STRING)
	@Column(name = "operation_type", length = 32, nullable = false)
	private JiraWriteOperationType operationType;

	@Column(name = "idempotency_key", length = 128, nullable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", length = 64, nullable = false)
	private String requestFingerprint;

	@Column(name = "remote_resource_id", length = 128)
	private String remoteResourceId;

	@Column(name = "remote_resource_key", length = 128)
	private String remoteResourceKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 32, nullable = false)
	private JiraWriteOperationStatus status;

	@Column(name = "safe_error_code", length = 64)
	private String safeErrorCode;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;
}
