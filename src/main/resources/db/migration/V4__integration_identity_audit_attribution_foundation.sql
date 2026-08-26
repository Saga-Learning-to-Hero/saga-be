-- SAGA V4: Integration + Identity + Traceability + Audit + Attribution Foundation
-- V1/V2/V3 remain immutable. UUID CHAR(36) primary keys only. utf8mb4.
-- Unofficial file v4_delete_fk_subjectid_from_rubric_table.sql is NOT a Flyway version.

-- ---------------------------------------------------------------------------
-- IDENTITY: multiple identities per provider; one active owner per provider subject
-- ---------------------------------------------------------------------------
-- InnoDB uses uk_identity_user_provider to support fk_identity_map_user.
-- Add a replacement index before dropping that unique key (MySQL error 1553).

ALTER TABLE identity_map
    ADD COLUMN is_primary TINYINT(1) NOT NULL DEFAULT 0 AFTER mapping_status,
    ADD COLUMN provider_display_name VARCHAR(255) NULL AFTER external_username,
    ADD COLUMN provider_avatar_url VARCHAR(500) NULL AFTER provider_display_name,
    ADD COLUMN provider_instance_id VARCHAR(255) NULL AFTER provider_avatar_url,
    ADD COLUMN linked_at DATETIME(6) NULL AFTER verified_at,
    ADD COLUMN last_verified_at DATETIME(6) NULL AFTER linked_at,
    ADD COLUMN revoked_at DATETIME(6) NULL AFTER disconnected_at,
    ADD KEY ix_identity_user_provider (user_account_id, provider);

ALTER TABLE identity_map
    DROP INDEX uk_identity_user_provider,
    DROP INDEX uk_identity_provider_external;

ALTER TABLE identity_map
    ADD COLUMN active_provider_subject VARCHAR(255)
        GENERATED ALWAYS AS (
            CASE
                WHEN mapping_status IN ('ACTIVE', 'VERIFIED', 'PENDING') THEN external_account_id
                ELSE NULL
            END
        ) STORED,
    ADD UNIQUE KEY uk_identity_active_provider_subject (provider, active_provider_subject),
    ADD KEY ix_identity_user_provider_primary (user_account_id, provider, is_primary);

ALTER TABLE identity_mapping_history
    ADD COLUMN previous_status VARCHAR(32) NULL AFTER action,
    ADD COLUMN new_status VARCHAR(32) NULL AFTER previous_status,
    ADD COLUMN reason VARCHAR(255) NULL AFTER new_status,
    ADD COLUMN source VARCHAR(32) NULL AFTER reason,
    ADD COLUMN is_primary_snapshot TINYINT(1) NULL AFTER source,
    ADD COLUMN previous_state_json JSON NULL AFTER is_primary_snapshot,
    ADD COLUMN new_state_json JSON NULL AFTER previous_state_json,
    ADD KEY ix_identity_history_user_time (user_account_id, occurred_at);

-- ---------------------------------------------------------------------------
-- TEAM RESOURCE INTEGRATION EXTENSIONS
-- ---------------------------------------------------------------------------

ALTER TABLE jira_integration
    ADD COLUMN site_name VARCHAR(255) NULL AFTER site_url,
    ADD COLUMN last_successful_sync_at DATETIME(6) NULL AFTER last_synced_at,
    ADD COLUMN last_error_code VARCHAR(64) NULL AFTER consecutive_failures,
    ADD COLUMN encrypted_webhook_secret TEXT NULL AFTER webhook_secret_hash,
    ADD KEY ix_jira_webhook_expires (connection_status, webhook_expires_at);

ALTER TABLE github_installation
    ADD COLUMN app_id BIGINT NULL AFTER installation_id,
    ADD COLUMN html_url VARCHAR(500) NULL AFTER account_type,
    ADD COLUMN project_id CHAR(36) NULL AFTER installed_by_user_id,
    ADD KEY ix_github_installation_project (project_id),
    ADD CONSTRAINT fk_github_installation_project FOREIGN KEY (project_id) REFERENCES project (id);

ALTER TABLE git_repo
    ADD COLUMN repository_role VARCHAR(32) NULL AFTER default_branch,
    ADD COLUMN is_private TINYINT(1) NULL AFTER repository_role,
    ADD KEY ix_git_repo_project_status (project_id, connection_status);

ALTER TABLE git_commit
    ADD COLUMN signature_verified TINYINT(1) NULL AFTER files_changed,
    ADD COLUMN verification_reason VARCHAR(64) NULL AFTER signature_verified,
    ADD COLUMN head_ref VARCHAR(255) NULL AFTER verification_reason;

ALTER TABLE pull_request
    ADD COLUMN body MEDIUMTEXT NULL AFTER title,
    ADD COLUMN head_ref VARCHAR(255) NULL AFTER comment_count,
    ADD COLUMN base_ref VARCHAR(255) NULL AFTER head_ref;

ALTER TABLE task
    ADD COLUMN jira_status_id VARCHAR(64) NULL AFTER status,
    ADD COLUMN jira_status_name VARCHAR(128) NULL AFTER jira_status_id,
    ADD COLUMN jira_status_category VARCHAR(64) NULL AFTER jira_status_name,
    ADD COLUMN issue_type_name VARCHAR(64) NULL AFTER jira_status_category,
    ADD COLUMN saga_completion_state VARCHAR(32) NULL AFTER issue_type_name,
    ADD COLUMN completed_at DATETIME(6) NULL AFTER resolved_at;

ALTER TABLE webhook_receipt
    ADD COLUMN event_action VARCHAR(64) NULL AFTER event_type;

-- ---------------------------------------------------------------------------
-- DIRECT TASK TRACEABILITY (no GitHub Issue required)
-- ---------------------------------------------------------------------------

CREATE TABLE task_git_commit_link (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    git_commit_id CHAR(36) NOT NULL,
    link_source VARCHAR(32) NOT NULL,
    jira_key_snapshot VARCHAR(64) NULL,
    confidence VARCHAR(16) NULL,
    metadata_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_git_commit_link (task_id, git_commit_id),
    KEY ix_task_commit_commit (git_commit_id),
    CONSTRAINT fk_task_commit_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_commit_commit FOREIGN KEY (git_commit_id) REFERENCES git_commit (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_pull_request_link (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    pull_request_id CHAR(36) NOT NULL,
    link_source VARCHAR(32) NOT NULL,
    jira_key_snapshot VARCHAR(64) NULL,
    confidence VARCHAR(16) NULL,
    metadata_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_pr_link (task_id, pull_request_id),
    KEY ix_task_pr_pr (pull_request_id),
    CONSTRAINT fk_task_pr_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_pr_pr FOREIGN KEY (pull_request_id) REFERENCES pull_request (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- APPEND-ONLY AUDIT
-- ---------------------------------------------------------------------------

CREATE TABLE audit_log (
    id CHAR(36) NOT NULL,
    actor_user_id CHAR(36) NULL,
    actor_full_name_snapshot VARCHAR(255) NULL,
    actor_role_snapshot VARCHAR(32) NULL,
    actor_email_snapshot VARCHAR(255) NULL,
    actor_student_code_snapshot VARCHAR(64) NULL,
    context_class_id CHAR(36) NULL,
    context_class_code_snapshot VARCHAR(64) NULL,
    context_class_name_snapshot VARCHAR(255) NULL,
    context_course_id CHAR(36) NULL,
    context_team_id CHAR(36) NULL,
    context_project_id CHAR(36) NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id CHAR(36) NULL,
    before_data JSON NULL,
    after_data JSON NULL,
    metadata_json JSON NULL,
    source VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NULL,
    ip_address VARCHAR(64) NULL,
    user_agent VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_audit_actor_time (actor_user_id, occurred_at),
    KEY ix_audit_project_time (context_project_id, occurred_at),
    KEY ix_audit_action_time (action, occurred_at),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- SAGA-SIDE EVIDENCE
-- ---------------------------------------------------------------------------

CREATE TABLE task_work_session (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    team_id CHAR(36) NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_work_session_task_user_time (task_id, user_id, started_at),
    KEY ix_work_session_user_status (user_id, status),
    CONSTRAINT fk_work_session_task FOREIGN KEY (task_id) REFERENCES task (id),
    CONSTRAINT fk_work_session_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_work_session_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_work_session_team FOREIGN KEY (team_id) REFERENCES team (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE contribution_confirmation (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    project_id CHAR(36) NULL,
    event_state VARCHAR(32) NOT NULL,
    confirmation_method VARCHAR(32) NOT NULL,
    evidence_hash VARCHAR(64) NOT NULL,
    evidence_snapshot_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_confirmation_task_user_time (task_id, user_id, created_at),
    CONSTRAINT fk_confirmation_task FOREIGN KEY (task_id) REFERENCES task (id),
    CONSTRAINT fk_confirmation_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_confirmation_project FOREIGN KEY (project_id) REFERENCES project (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- WEBAUTHN / PASSKEY (feature-flagged at application layer)
-- ---------------------------------------------------------------------------

CREATE TABLE webauthn_credential (
    id CHAR(36) NOT NULL,
    user_account_id CHAR(36) NOT NULL,
    credential_id VARCHAR(512) NOT NULL,
    public_key_cose TEXT NOT NULL,
    signature_count BIGINT NOT NULL DEFAULT 0,
    uv_initialized TINYINT(1) NULL,
    backup_eligible TINYINT(1) NULL,
    backup_state TINYINT(1) NULL,
    transports VARCHAR(255) NULL,
    label VARCHAR(255) NULL,
    last_used_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_webauthn_credential_id (credential_id),
    KEY ix_webauthn_user (user_account_id),
    CONSTRAINT fk_webauthn_user FOREIGN KEY (user_account_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
