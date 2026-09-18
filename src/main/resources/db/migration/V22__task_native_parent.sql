-- SAGA V22: Native SAGA parent/subtask hierarchy on Task.
-- V1-V21 remain immutable. Additive/safe. utf8mb4.
--
-- Jira parent_external_id / parent_external_key (V16) remain provider metadata.
-- blocks_task_id (V1) remains dependency semantics.
-- Native hierarchy is SAGA-owned: parent_task_id self-FK to task(id).
--
-- RESTRICT/default restrictive hard-delete: a parent row cannot be hard-deleted
-- while children still reference it. Soft-delete of a parent is allowed (column
-- stays populated); readers ignore deleted parents.
--
-- No backfill. Existing rows remain parent_task_id NULL.

ALTER TABLE task
    ADD COLUMN parent_task_id CHAR(36) NULL AFTER parent_external_key,
    ADD KEY ix_task_parent_task_id (parent_task_id),
    ADD CONSTRAINT fk_task_parent_task FOREIGN KEY (parent_task_id) REFERENCES task (id);
