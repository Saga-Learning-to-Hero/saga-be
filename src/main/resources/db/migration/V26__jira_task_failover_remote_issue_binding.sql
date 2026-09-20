-- SAGA V26: durable uniqueness reservation for a verified target Jira issue.
-- V25 owns the outbound source-task claim. This table is not a second execution state machine;
-- it only prevents two failover items from binding the same B issue, scoped to B integration.

CREATE TABLE jira_task_failover_remote_issue_binding (
  id CHAR(36) NOT NULL,
  item_id CHAR(36) NOT NULL,
  target_jira_integration_id CHAR(36) NOT NULL,
  remote_issue_id VARCHAR(128) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_jira_failover_remote_binding_item (item_id),
  UNIQUE KEY uk_jira_failover_remote_binding_target_issue (target_jira_integration_id, remote_issue_id),
  CONSTRAINT fk_jira_failover_remote_binding_item FOREIGN KEY (item_id) REFERENCES jira_task_failover_item (id),
  CONSTRAINT fk_jira_failover_remote_binding_target FOREIGN KEY (target_jira_integration_id) REFERENCES jira_integration (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
