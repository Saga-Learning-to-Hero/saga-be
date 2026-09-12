-- SAGA V15: Reuse a GitHub repository after disconnect.
-- V1-V14 remain immutable. Additive/safe. utf8mb4.
--
-- uk_git_repo_provider_id(provider, repository_id) made a physical GitHub repository
-- permanently exclusive to whichever SAGA Project connected it first, even after that
-- Project disconnected: disconnectGithub() only soft-revokes rows (connection_status =
-- REVOKED) and git_repo rows are never deleted. So a REVOKED row blocked every other
-- SAGA Project from ever selecting that same repository again -- not the intended
-- behavior (same root cause as V14's Jira fix, applied here to git_repo).
--
-- New invariant: a physical GitHub repository may be ACTIVE in at most one SAGA
-- Project at a time. active_provider/active_repository_id are generated columns that
-- collapse to NULL whenever the row is not ACTIVE; MySQL treats multiple NULLs in a
-- UNIQUE index as distinct values, so REVOKED rows never collide with each other or
-- with a later ACTIVE row on the same repository, while two simultaneously-ACTIVE
-- rows on the same repository are still rejected by the database as the final
-- race-safety barrier.
--
-- Unlike jira_integration (at most one row per SAGA project, ever), a SAGA project may
-- own several git_repo rows (several repositories), so relaxing the global constraint
-- also needs a new project-scoped guard: uk_git_repo_project_provider_repository
-- ensures one project can never end up with two rows for the same physical repository
-- (regardless of status), which is also what lets a project safely rediscover and
-- reactivate its own historical row on reconnect.
--
-- No existing row is modified, re-parented, or deleted by this migration.

ALTER TABLE git_repo
    ADD COLUMN active_provider VARCHAR(32)
        GENERATED ALWAYS AS (CASE WHEN connection_status = 'ACTIVE' THEN provider ELSE NULL END) STORED,
    ADD COLUMN active_repository_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN connection_status = 'ACTIVE' THEN repository_id ELSE NULL END) STORED,
    DROP INDEX uk_git_repo_provider_id,
    ADD UNIQUE KEY uk_git_repo_active_provider_repository (active_provider, active_repository_id),
    ADD UNIQUE KEY uk_git_repo_project_provider_repository (project_id, provider, repository_id);
