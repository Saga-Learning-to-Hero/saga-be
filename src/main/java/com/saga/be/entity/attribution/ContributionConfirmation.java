package com.saga.be.entity.attribution;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.ConfirmationEvent;
import com.saga.be.entity.enums.ConfirmationMethod;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
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
	name = "contribution_confirmation",
	indexes = {@Index(name = "ix_confirmation_task_user_time", columnList = "task_id, user_id, created_at")}
)
public class ContributionConfirmation extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "task_id", nullable = false)
	private Task task;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private UserAccount user;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "project_id")
	private Project project;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_state", length = 32, nullable = false)
	private ConfirmationEvent eventState;

	@Enumerated(EnumType.STRING)
	@Column(name = "confirmation_method", length = 32, nullable = false)
	private ConfirmationMethod confirmationMethod;

	@Column(name = "evidence_hash", length = 64, nullable = false)
	private String evidenceHash;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence_snapshot_json", columnDefinition = "json", nullable = false)
	private String evidenceSnapshotJson;
}
