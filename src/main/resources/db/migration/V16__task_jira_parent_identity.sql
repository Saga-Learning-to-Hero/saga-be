-- SAGA V16: Persist Jira's own parent issue identity on a Task (Subtask -> parent,
-- and Team-managed Story/Task -> Epic, since Jira reuses fields.parent for both).
-- V1-V15 remain immutable. Additive/safe. utf8mb4.
--
-- Same VARCHAR(64) width as task.external_id/task.external_key (V1) -- these two new
-- columns store the PARENT issue's identity in exactly the same shape SAGA already
-- stores for a Task's OWN identity, so no new type/width decision is introduced.
--
-- Deliberately NOT a self-referencing FK to task.id (unlike the existing, unrelated
-- blocks_task_id column): the parent issue may not have a local Task row yet when the
-- child is synced (JQL page ordering, or the parent simply hasn't synced yet), and a FK
-- would force either rejecting the child's parent value or a later backfill/reconciliation
-- pass. Storing the provider's own id/key directly has no such ordering dependency --
-- it is valid immediately regardless of whether/when the parent Task row exists, and
-- needs no backfill when the parent Task row later appears.
--
-- No index is added: no current query filters or joins by parent identity -- GET
-- /tasks already returns every Task row for a project in one query, and FE groups
-- Subtasks under their parent client-side using the returned parent externalId/
-- externalKey.
--
-- No existing row is modified, re-parented, or deleted by this migration. Every
-- existing Task row gets parent_external_id/parent_external_key = NULL until the next
-- Jira sync populates them from the provider's actual current fields.parent.

ALTER TABLE task
    ADD COLUMN parent_external_id VARCHAR(64) NULL AFTER external_id,
    ADD COLUMN parent_external_key VARCHAR(64) NULL AFTER parent_external_id;
