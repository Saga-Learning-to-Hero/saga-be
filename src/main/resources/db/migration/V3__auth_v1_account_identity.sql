-- Auth V1 identity columns. V1 and V2 are immutable.
-- Does not add tables. Target domain table count remains 52.

SET NAMES utf8mb4;

ALTER TABLE user_account
    ADD COLUMN username VARCHAR(64) NULL AFTER email,
    ADD COLUMN google_subject VARCHAR(255) NULL AFTER username,
    ADD UNIQUE KEY uk_user_account_username (username),
    ADD UNIQUE KEY uk_user_account_google_subject (google_subject);
