-- SAGA V18: persist exact GitCommit ↔ branch reachability from a successful FULL
-- GitHub repo sync. V1-V17 remain immutable. Additive/safe. utf8mb4.
--
-- GitCommit.headRef is a single observed-branch hint (first-seen SHA in a run).
-- It is not many-to-many membership. This table is the canonical snapshot:
-- one row per (git_commit_id, branch_name) collected while listing every branch
-- and every commit page, independently of seenShas commit-projection dedup.
--
-- branch_name uses utf8mb4_bin so Git refs that differ only by case stay distinct
-- (MySQL 8 default utf8mb4_0900_ai_ci would merge them).
--
-- git_repo.branch_membership_synced_at is the resolvedAt for REACHABLE_AT_SYNC.
-- last_synced_at already exists from pre-V18 full syncs that did NOT persist
-- membership, so it must not be reused as a lie that the snapshot is complete.
-- Webhooks never write this column.
--
-- Existing GitCommit / GitRepo / TaskGitCommitLink rows are not modified.
-- FK ON DELETE CASCADE drops memberships when a GitCommit row is deleted.

ALTER TABLE git_repo
    ADD COLUMN branch_membership_synced_at DATETIME(6) NULL AFTER last_synced_at;

CREATE TABLE git_commit_branch (
    id CHAR(36) NOT NULL,
    git_commit_id CHAR(36) NOT NULL,
    branch_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_git_commit_branch (git_commit_id, branch_name),
    CONSTRAINT fk_git_commit_branch_commit FOREIGN KEY (git_commit_id) REFERENCES git_commit (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
