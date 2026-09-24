-- SAGA V34: COURSE MULTI-PROVIDER AI. V1-V33 remain untouched.
--
-- 1. ai_course_provider_credential becomes unique per (course, providerRole, provider) instead of
--    per (course, providerRole), so a course can hold an OpenAI, a Gemini and an OpenRouter key for
--    the same role at the same time. `provider` becomes a closed canonical enum. Every pre-V34 row
--    was only ever dispatched to OpenAI (the only provider saga-ai had), whatever free text the
--    client sent, so normalising all of them to 'OPENAI' records how they were actually used. The
--    old unique key guarantees at most one row per (course, role), so the new key cannot collide.
UPDATE ai_course_provider_credential SET provider = 'OPENAI';

ALTER TABLE ai_course_provider_credential
    ADD UNIQUE KEY uk_ai_course_provider_credential_role_provider (course_id, provider_role, provider),
    ADD CONSTRAINT ck_ai_course_provider_credential_provider CHECK (provider IN ('OPENAI','GEMINI','OPENROUTER'));

-- fk_ai_course_provider_credential_course stays backed by the new unique key and by
-- ix_ai_course_provider_credential_status, both of which lead with course_id.
ALTER TABLE ai_course_provider_credential
    DROP INDEX uk_ai_course_provider_credential_role;

-- 2. Course bindings: which provider/model serves PRIMARY and SECONDARY. NULL provider/model means
--    the legacy behaviour (OpenAI credential, platform model), so existing courses are unchanged.
--    fallback_enabled defaults OFF: nothing new is ever attempted without an explicit opt-in.
ALTER TABLE ai_course_settings
    ADD COLUMN primary_provider VARCHAR(32) NULL,
    ADD COLUMN primary_model_id VARCHAR(128) NULL,
    ADD COLUMN fallback_enabled TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN secondary_provider VARCHAR(32) NULL,
    ADD COLUMN secondary_model_id VARCHAR(128) NULL,
    ADD CONSTRAINT ck_ai_course_settings_primary_provider CHECK (primary_provider IS NULL OR primary_provider IN ('OPENAI','GEMINI','OPENROUTER')),
    ADD CONSTRAINT ck_ai_course_settings_primary_pair CHECK ((primary_provider IS NULL) = (primary_model_id IS NULL)),
    ADD CONSTRAINT ck_ai_course_settings_secondary_provider CHECK (secondary_provider IS NULL OR secondary_provider IN ('OPENAI','GEMINI','OPENROUTER')),
    ADD CONSTRAINT ck_ai_course_settings_secondary_pair CHECK ((secondary_provider IS NULL) = (secondary_model_id IS NULL));

-- 3. Ordered, course-owned PRIMARY fallback chain (never platform, never SECONDARY). At most three
--    entries, each (provider, model) at most once.
CREATE TABLE ai_course_fallback_binding (
    id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    attempt_order INT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_course_fallback_binding_order (course_id, attempt_order),
    UNIQUE KEY uk_ai_course_fallback_binding_model (course_id, provider, model_id),
    CONSTRAINT fk_ai_course_fallback_binding_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT ck_ai_course_fallback_binding_provider CHECK (provider IN ('OPENAI','GEMINI','OPENROUTER')),
    CONSTRAINT ck_ai_course_fallback_binding_order CHECK (attempt_order BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. Provenance: the provider actually used for a decision (NULL on legacy/platform rows, whose
--    model_id is the platform model) and the bounded, secret-free list of course fallback attempts.
ALTER TABLE ai_analysis_provider_decision
    ADD COLUMN ai_provider VARCHAR(32) NULL AFTER credential_fingerprint,
    ADD COLUMN fallback_attempts_json MEDIUMTEXT NULL,
    ADD CONSTRAINT ck_ai_provider_decision_ai_provider CHECK (ai_provider IS NULL OR ai_provider IN ('OPENAI','GEMINI','OPENROUTER'));
