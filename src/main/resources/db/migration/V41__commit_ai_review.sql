-- Commit AI review: a team leader may store the team's own AI key (used to review the team's
-- commits), and members may attach a commit to a task by hand. A manual attachment is display and
-- review context only: it lives in its own table so no contribution/evidence query ever counts it.

CREATE TABLE ai_team_credential (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    encrypted_secret TEXT NOT NULL,
    encryption_nonce VARCHAR(32) NOT NULL,
    encryption_key_version INT NOT NULL DEFAULT 1,
    fingerprint CHAR(64) NOT NULL,
    last_four VARCHAR(4) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_error_code VARCHAR(64) NULL,
    created_by_user_id CHAR(36) NULL,
    last_successful_use_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_team_credential_project (project_id),
    CONSTRAINT fk_ai_team_credential_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_ai_team_credential_creator FOREIGN KEY (created_by_user_id) REFERENCES user_account (id),
    CONSTRAINT ck_ai_team_credential_status CHECK (status IN ('UNVERIFIED','ACTIVE','DEGRADED','INVALID','REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A run paid by a team key keeps credential_source = 'COURSE' (the wire format saga-ai knows) and
-- records which team key served it here; course_credential_id stays NULL for such runs.
ALTER TABLE ai_analysis_provider_decision
    ADD COLUMN team_credential_id CHAR(36) NULL AFTER course_credential_id,
    ADD CONSTRAINT fk_ai_provider_decision_team_credential FOREIGN KEY (team_credential_id) REFERENCES ai_team_credential (id);

CREATE TABLE task_commit_manual_link (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    git_commit_id CHAR(36) NOT NULL,
    created_by_user_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_commit_manual_link (task_id, git_commit_id),
    KEY ix_task_commit_manual_link_commit (git_commit_id),
    CONSTRAINT fk_task_commit_manual_link_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_task_commit_manual_link_task FOREIGN KEY (task_id) REFERENCES task (id),
    CONSTRAINT fk_task_commit_manual_link_commit FOREIGN KEY (git_commit_id) REFERENCES git_commit (id),
    CONSTRAINT fk_task_commit_manual_link_creator FOREIGN KEY (created_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
