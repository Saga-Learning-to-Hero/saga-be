-- SAGA V13: Forgot/Reset Password via one-time email reset token.
-- V1–V12 remain immutable. UUID CHAR(36). Additive/safe. utf8mb4.
--
-- Only a SHA-256 hex digest of the raw token is stored; the raw token is
-- never persisted. token_hash is unique so a hash collision cannot resolve
-- to two different tokens. used_at marks one-time consumption.

CREATE TABLE password_reset_token (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_password_reset_token_hash (token_hash),
    KEY ix_password_reset_token_user (user_id, used_at),
    CONSTRAINT fk_password_reset_token_user FOREIGN KEY (user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
