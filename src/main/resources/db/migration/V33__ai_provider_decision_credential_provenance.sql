-- SAGA V33: HYBRID AI CREDENTIAL MODEL, part 2 -- safe credential provenance on
-- ai_analysis_provider_decision. Deliberately additive and nullable: existing (pre-BYOK) rows all
-- predate this feature and stay NULL, meaning "unknown/platform-era", which is accurate. This
-- never changes the meaning of the existing provider_config_hash/uk_ai_provider_decision_run_role_config
-- columns/constraint -- those still identify the provider BEAN used at execution time. Credential
-- rotation identity instead flows through AiAnalysisRun.idempotency_key (application-level), so a
-- replaced course key can always start a fresh run without being blocked by an old failed one.
ALTER TABLE ai_analysis_provider_decision
    ADD COLUMN credential_source VARCHAR(16) NULL AFTER provider_config_hash,
    ADD COLUMN course_credential_id CHAR(36) NULL AFTER credential_source,
    ADD COLUMN credential_fingerprint CHAR(64) NULL AFTER course_credential_id,
    ADD CONSTRAINT fk_ai_provider_decision_course_credential FOREIGN KEY (course_credential_id) REFERENCES ai_course_provider_credential (id),
    ADD CONSTRAINT ck_ai_provider_decision_credential_source CHECK (credential_source IS NULL OR credential_source IN ('COURSE','PLATFORM'));
