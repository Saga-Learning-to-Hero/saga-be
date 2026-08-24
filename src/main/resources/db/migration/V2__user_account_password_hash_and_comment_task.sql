-- SAGA V2 follow-up. V1 is immutable (already applied to DEV).
-- Does not add tables. Target table count remains 52.

SET NAMES utf8mb4;

-- Local password credential for SAGA-owned auth.
-- Nullable: OIDC-only accounts may have no local password.
-- Store Spring Security PasswordEncoder output only (prefer Argon2id). Never plaintext.
ALTER TABLE user_account
    ADD COLUMN password_hash VARCHAR(255) NULL AFTER avatar_url;

-- Restore nullable Jira task comments without competing traceability FKs on PR/commit.
ALTER TABLE comment
    ADD COLUMN task_id CHAR(36) NULL AFTER parent_comment_id,
    ADD KEY ix_comment_task (task_id),
    ADD CONSTRAINT fk_comment_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE;
