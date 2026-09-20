-- SAGA V24: Multi-Jira source foundation — Task provenance + Project 1:N JiraIntegration capacity.
-- V1–V23 remain immutable. Additive/safe. utf8mb4.
--
-- 1) Drop forever-one-row uniqueness on jira_integration.project_id so a Project may hold N sources.
--    Preserve V14 active-scoped uk_jira_active_cloud_project (cloud + jira project exclusive while ACTIVE).
-- 2) Add task.jira_integration_id, backfill from the project's singular integration (still 1:1 during
--    this migration), then NOT NULL. Orphan tasks without a project integration fail the ALTER.
-- 3) Replace uk_task_project_external_id with source-scoped uk_task_jira_integration_external_id so
--    the same provider external_id from two sources may coexist as distinct Task UUIDs.
--
-- Does NOT enable attaching a second source in product APIs; Phase 2 covers sync/webhook/write routing.

-- ---------------------------------------------------------------------------
-- A. jira_integration: allow N rows per project
-- ---------------------------------------------------------------------------
ALTER TABLE jira_integration
    DROP INDEX uk_jira_integration_project,
    ADD KEY ix_jira_integration_project (project_id);

-- ---------------------------------------------------------------------------
-- B. task provenance column (nullable until backfill)
-- ---------------------------------------------------------------------------
ALTER TABLE task
    ADD COLUMN jira_integration_id CHAR(36) NULL AFTER project_id;

-- Deterministic backfill while production is still 1:1 Project → JiraIntegration.
UPDATE task t
INNER JOIN jira_integration j ON j.project_id = t.project_id
SET t.jira_integration_id = j.id
WHERE t.jira_integration_id IS NULL;

-- Fail closed if any Task lacks a assignable singular integration for its project.
ALTER TABLE task
    MODIFY COLUMN jira_integration_id CHAR(36) NOT NULL,
    ADD CONSTRAINT fk_task_jira_integration FOREIGN KEY (jira_integration_id) REFERENCES jira_integration (id),
    DROP INDEX uk_task_project_external_id,
    ADD UNIQUE KEY uk_task_jira_integration_external_id (jira_integration_id, external_id),
    ADD KEY ix_task_project_jira_integration (project_id, jira_integration_id);
