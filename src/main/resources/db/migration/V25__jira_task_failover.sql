-- SAGA V25: Jira unfinished-task failover run/item lineage foundation (Phase 4A).
-- V1–V24 immutable. No remote execution. utf8mb4.
--
-- Cross-run exclusivity: outbound_claim_task_id is a generated column that equals
-- source_task_id while the item holds the outbound create/reconcile claim
-- (PENDING/CREATING/REMOTE_OUTCOME_UNKNOWN/REMOTE_BOUND/SUCCEEDED). MySQL UNIQUE
-- allows multiple NULLs, so FAILED/SKIPPED/ABANDONED release the claim for a later
-- retry. SUCCEEDED keeps the source permanently claimed (A->C forbidden; B->C uses
-- B's task id as source_task_id).
--
-- Target B is run-level only (item has no target_jira_integration_id) to avoid drift.

CREATE TABLE jira_task_failover_run (
  id CHAR(36) NOT NULL,
  project_id CHAR(36) NOT NULL,
  source_jira_integration_id CHAR(36) NOT NULL,
  target_jira_integration_id CHAR(36) NOT NULL,
  requested_by_user_id CHAR(36) NOT NULL,
  status VARCHAR(32) NOT NULL,
  target_sprint_id CHAR(36) NULL,
  default_issue_type_id VARCHAR(64) NULL,
  revoke_source_requested TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  started_at DATETIME(6) NULL,
  completed_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  KEY ix_jira_failover_run_project_status (project_id, status),
  KEY ix_jira_failover_run_source (source_jira_integration_id),
  KEY ix_jira_failover_run_target (target_jira_integration_id),
  CONSTRAINT fk_jira_failover_run_project FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT fk_jira_failover_run_source FOREIGN KEY (source_jira_integration_id) REFERENCES jira_integration (id),
  CONSTRAINT fk_jira_failover_run_target FOREIGN KEY (target_jira_integration_id) REFERENCES jira_integration (id),
  CONSTRAINT fk_jira_failover_run_actor FOREIGN KEY (requested_by_user_id) REFERENCES user_account (id),
  CONSTRAINT fk_jira_failover_run_sprint FOREIGN KEY (target_sprint_id) REFERENCES sprint (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE jira_task_failover_item (
  id CHAR(36) NOT NULL,
  run_id CHAR(36) NOT NULL,
  source_task_id CHAR(36) NOT NULL,
  target_task_id CHAR(36) NULL,
  source_status_snapshot VARCHAR(32) NOT NULL,
  source_external_key_snapshot VARCHAR(64) NULL,
  status VARCHAR(32) NOT NULL,
  remote_issue_id VARCHAR(128) NULL,
  remote_issue_key VARCHAR(128) NULL,
  error_code VARCHAR(64) NULL,
  outbound_claim_task_id CHAR(36)
    GENERATED ALWAYS AS (
      CASE
        WHEN status IN (
          'PENDING',
          'CREATING',
          'REMOTE_OUTCOME_UNKNOWN',
          'REMOTE_BOUND',
          'SUCCEEDED'
        ) THEN source_task_id
        ELSE NULL
      END
    ) STORED,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  started_at DATETIME(6) NULL,
  completed_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_jira_failover_item_run_source (run_id, source_task_id),
  UNIQUE KEY uk_jira_failover_item_outbound_claim (outbound_claim_task_id),
  KEY ix_jira_failover_item_source_task (source_task_id),
  KEY ix_jira_failover_item_target_task (target_task_id),
  KEY ix_jira_failover_item_status (status),
  CONSTRAINT fk_jira_failover_item_run FOREIGN KEY (run_id) REFERENCES jira_task_failover_run (id),
  CONSTRAINT fk_jira_failover_item_source_task FOREIGN KEY (source_task_id) REFERENCES task (id),
  CONSTRAINT fk_jira_failover_item_target_task FOREIGN KEY (target_task_id) REFERENCES task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
