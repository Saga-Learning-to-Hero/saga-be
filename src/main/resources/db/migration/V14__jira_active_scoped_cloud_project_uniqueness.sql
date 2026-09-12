-- SAGA V14: Reuse a Jira provider project after disconnect.
-- V1-V13 remain immutable. Additive/safe. utf8mb4.
--
-- uk_jira_cloud_project(cloud_id, jira_project_id) made a Jira provider project
-- permanently exclusive to whichever SAGA Project connected it first, even after
-- that Project disconnected: disconnectJira() only soft-revokes the row (clears
-- credentials, sets connection_status = REVOKED) and keeps cloud_id/jira_project_id
-- forever, and jira_integration rows are never deleted. So a REVOKED row blocked
-- every other SAGA Project from ever reconnecting that same Jira project again --
-- not the intended behavior.
--
-- New invariant: a Jira provider project may be ACTIVE in at most one SAGA Project
-- at a time. active_cloud_id/active_jira_project_id are generated columns that
-- collapse to NULL whenever the row is not ACTIVE; MySQL treats multiple NULLs in a
-- UNIQUE index as distinct values, so REVOKED rows never collide with each other or
-- with a later ACTIVE row on the same source, while two simultaneously-ACTIVE rows
-- on the same source are still rejected by the database as the final race-safety
-- barrier. No existing row is modified, re-parented, or deleted by this migration.

ALTER TABLE jira_integration
    ADD COLUMN active_cloud_id VARCHAR(128)
        GENERATED ALWAYS AS (CASE WHEN connection_status = 'ACTIVE' THEN cloud_id ELSE NULL END) STORED,
    ADD COLUMN active_jira_project_id VARCHAR(64)
        GENERATED ALWAYS AS (CASE WHEN connection_status = 'ACTIVE' THEN jira_project_id ELSE NULL END) STORED,
    DROP INDEX uk_jira_cloud_project,
    ADD UNIQUE KEY uk_jira_active_cloud_project (active_cloud_id, active_jira_project_id);
