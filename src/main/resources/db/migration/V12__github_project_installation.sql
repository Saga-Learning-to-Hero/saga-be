-- SAGA V12: Shared GitHub App installation membership across SAGA projects.
-- V1–V11 remain immutable. UUID CHAR(36). Additive/safe. utf8mb4.
--
-- Product: one GitHub App installation (org/account authorization) may be used
-- by multiple SAGA projects. Repository ownership stays exclusive via
-- uk_git_repo_provider_id. github_installation.project_id is LEGACY and is
-- NOT dropped; membership table is authoritative after this migration.

CREATE TABLE github_project_installation (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    github_installation_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_github_project_installation (project_id, github_installation_id),
    KEY ix_gpi_project (project_id),
    KEY ix_gpi_installation (github_installation_id),
    CONSTRAINT fk_gpi_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_gpi_installation FOREIGN KEY (github_installation_id) REFERENCES github_installation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Backfill legacy exclusive bindings. Idempotent for unique (project, installation).
INSERT INTO github_project_installation (id, project_id, github_installation_id, created_at, updated_at)
SELECT UUID(), gi.project_id, gi.id, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM github_installation gi
WHERE gi.project_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM github_project_installation gpi
      WHERE gpi.project_id = gi.project_id
        AND gpi.github_installation_id = gi.id
  );
