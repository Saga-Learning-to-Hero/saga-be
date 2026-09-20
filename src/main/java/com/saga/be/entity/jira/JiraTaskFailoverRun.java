package com.saga.be.entity.jira;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.JiraFailoverRunStatus;
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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "jira_task_failover_run",
	indexes = {
		@Index(name = "ix_jira_failover_run_project_status", columnList = "project_id, status"),
		@Index(name = "ix_jira_failover_run_source", columnList = "source_jira_integration_id"),
		@Index(name = "ix_jira_failover_run_target", columnList = "target_jira_integration_id")
	}
)
public class JiraTaskFailoverRun extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "source_jira_integration_id", nullable = false)
	private JiraIntegration sourceJiraIntegration;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "target_jira_integration_id", nullable = false)
	private JiraIntegration targetJiraIntegration;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "requested_by_user_id", nullable = false)
	private UserAccount requestedByUser;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 32, nullable = false)
	private JiraFailoverRunStatus status;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "target_sprint_id", nullable = true)
	private Sprint targetSprint;

	@Column(name = "default_issue_type_id", length = 64)
	private String defaultIssueTypeId;

	@Column(name = "revoke_source_requested", nullable = false)
	private boolean revokeSourceRequested;

	@Column(name = "started_at")
	private LocalDateTime startedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;
}
