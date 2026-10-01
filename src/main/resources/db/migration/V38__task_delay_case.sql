-- Delay cases: when a task misses its deadline the system opens a case, the assignee explains the
-- cause, the team leader confirms and, where needed, the lecturer decides objective vs subjective.
-- Contribution scoring is unchanged; closed cases only feed the separate on-time rate.
CREATE TABLE task_delay_case (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    student_profile_id CHAR(36) NOT NULL,
    due_date DATETIME(6) NOT NULL,
    opened_at DATETIME(6) NOT NULL,
    explanation_due_at DATETIME(6) NOT NULL,
    status VARCHAR(24) NOT NULL,
    category VARCHAR(32) NULL,
    explanation_note VARCHAR(1000) NULL,
    blocking_task_id CHAR(36) NULL,
    evidence_url VARCHAR(2048) NULL,
    explained_at DATETIME(6) NULL,
    explained_by_user_id CHAR(36) NULL,
    signals_json TEXT NULL,
    verification VARCHAR(16) NULL,
    verification_note VARCHAR(500) NULL,
    leader_decision VARCHAR(16) NULL,
    leader_comment VARCHAR(1000) NULL,
    leader_reviewed_by_user_id CHAR(36) NULL,
    leader_reviewed_at DATETIME(6) NULL,
    lecturer_outcome VARCHAR(16) NULL,
    lecturer_comment VARCHAR(1000) NULL,
    lecturer_reviewed_by_user_id CHAR(36) NULL,
    lecturer_reviewed_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    close_reason VARCHAR(32) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_delay_case_task_due (task_id, due_date),
    KEY ix_task_delay_case_project_status (project_id, status),
    KEY ix_task_delay_case_status_due (status, explanation_due_at),
    CONSTRAINT fk_task_delay_case_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_task_delay_case_task FOREIGN KEY (task_id) REFERENCES task (id),
    CONSTRAINT fk_task_delay_case_student FOREIGN KEY (student_profile_id) REFERENCES student_profile (id),
    CONSTRAINT fk_task_delay_case_blocking_task FOREIGN KEY (blocking_task_id) REFERENCES task (id),
    CONSTRAINT ck_task_delay_case_status
        CHECK (status IN ('OPEN','AWAITING_LEADER','AWAITING_LECTURER','CLOSED_OBJECTIVE','CLOSED_SUBJECTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- History of due date / story points / assignee changes seen by Jira sync (recorded from now on).
CREATE TABLE task_change_log (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    field VARCHAR(16) NOT NULL,
    old_value VARCHAR(128) NULL,
    new_value VARCHAR(128) NULL,
    changed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_task_change_log_task (task_id, field),
    CONSTRAINT fk_task_change_log_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
