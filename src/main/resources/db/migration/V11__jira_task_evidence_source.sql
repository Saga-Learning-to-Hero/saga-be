-- Jira-synced evidence shares task_file / task_web_link with student uploads.
-- source=SAGA stays student-owned; source=JIRA is upserted from issue attachments
-- and remote links. Jira ingest must not delete SAGA rows. created_by is null
-- for provider-sourced rows.

ALTER TABLE task_web_link
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'SAGA' AFTER title,
    ADD COLUMN external_id VARCHAR(64) NULL AFTER source,
    MODIFY created_by_user_id CHAR(36) NULL,
    ADD UNIQUE KEY uk_task_web_link_external (task_id, external_id);

ALTER TABLE task_file
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'SAGA' AFTER content_hash,
    ADD COLUMN external_id VARCHAR(64) NULL AFTER source,
    MODIFY created_by_user_id CHAR(36) NULL,
    ADD UNIQUE KEY uk_task_file_external (task_id, external_id);
