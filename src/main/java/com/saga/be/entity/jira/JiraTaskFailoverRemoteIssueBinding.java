package com.saga.be.entity.jira;

import com.saga.be.entity.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DB uniqueness reservation for a remote B issue. Execution state remains exclusively on the
 * failover item; this row merely makes duplicate verified binding impossible across runs.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
		name = "jira_task_failover_remote_issue_binding",
		uniqueConstraints = {
			@UniqueConstraint(name = "uk_jira_failover_remote_binding_item", columnNames = "item_id"),
			@UniqueConstraint(
					name = "uk_jira_failover_remote_binding_target_issue",
					columnNames = {"target_jira_integration_id", "remote_issue_id"})
		})
public class JiraTaskFailoverRemoteIssueBinding extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "item_id", nullable = false)
	private JiraTaskFailoverItem item;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "target_jira_integration_id", nullable = false)
	private JiraIntegration targetJiraIntegration;

	@jakarta.persistence.Column(name = "remote_issue_id", length = 128, nullable = false)
	private String remoteIssueId;
}
