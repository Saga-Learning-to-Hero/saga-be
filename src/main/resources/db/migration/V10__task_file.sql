-- Student-uploaded evidence files on a Task (PDF, Office, images).
-- Separate from task_attachment so Jira ingest cannot wipe SAGA uploads.
-- DOCUMENT/RESEARCH recognizes story points when the task has at least one
-- task_attachment, task_web_link, or task_file. Count does not increase score.

CREATE TABLE task_file (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    original_filename VARCHAR(512) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_by_user_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_file_hash (task_id, content_hash),
    KEY ix_task_file_task (task_id),
    CONSTRAINT fk_task_file_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_file_user FOREIGN KEY (created_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
