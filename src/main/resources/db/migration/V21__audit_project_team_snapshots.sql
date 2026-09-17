-- SAGA V21: write-time Project/Team display snapshots on audit_log.
-- V1-V20 remain immutable. Additive/nullable. utf8mb4.
--
-- Actor and class already have first-class snapshot columns (V4).
-- context_project_id / context_team_id stay as-is. These columns capture
-- the Project name and Team number/name AT AUDIT WRITE TIME.
--
-- Pre-V21 rows survive with NULL snapshots. Do not backfill from live
-- Project/Team — that would fabricate historical data.
--
-- Display snapshots only. No indexes in this phase (not search keys).

ALTER TABLE audit_log
    ADD COLUMN context_team_no_snapshot INT NULL AFTER context_team_id,
    ADD COLUMN context_team_name_snapshot VARCHAR(255) NULL AFTER context_team_no_snapshot,
    ADD COLUMN context_project_name_snapshot VARCHAR(255) NULL AFTER context_project_id;
