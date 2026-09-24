-- Preserve every physical execution attempt. Existing idempotency keys already identify the
-- canonical first execution, so they truthfully become retry_attempt = 0.
ALTER TABLE ai_analysis_run
    ADD COLUMN canonical_identity_key CHAR(64) NULL AFTER idempotency_key,
    ADD COLUMN retry_attempt INT UNSIGNED NULL AFTER canonical_identity_key;

UPDATE ai_analysis_run
SET canonical_identity_key = idempotency_key,
    retry_attempt = 0
WHERE canonical_identity_key IS NULL OR retry_attempt IS NULL;

ALTER TABLE ai_analysis_run
    MODIFY COLUMN canonical_identity_key CHAR(64) NOT NULL,
    MODIFY COLUMN retry_attempt INT UNSIGNED NOT NULL,
    ADD UNIQUE KEY uk_ai_analysis_run_canonical_retry (canonical_identity_key, retry_attempt),
    ADD KEY ix_ai_analysis_run_canonical_attempt (canonical_identity_key, retry_attempt, status);
