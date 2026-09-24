-- Add COHERE to the closed provider checks introduced by V34. Existing provider values and
-- V35 retry lineage remain untouched; this migration only expands accepted canonical values.
ALTER TABLE ai_course_provider_credential
    DROP CHECK ck_ai_course_provider_credential_provider,
    ADD CONSTRAINT ck_ai_course_provider_credential_provider CHECK (provider IN ('OPENAI','GEMINI','OPENROUTER','COHERE'));

ALTER TABLE ai_course_settings
    DROP CHECK ck_ai_course_settings_primary_provider,
    ADD CONSTRAINT ck_ai_course_settings_primary_provider CHECK (primary_provider IS NULL OR primary_provider IN ('OPENAI','GEMINI','OPENROUTER','COHERE')),
    DROP CHECK ck_ai_course_settings_secondary_provider,
    ADD CONSTRAINT ck_ai_course_settings_secondary_provider CHECK (secondary_provider IS NULL OR secondary_provider IN ('OPENAI','GEMINI','OPENROUTER','COHERE'));

ALTER TABLE ai_course_fallback_binding
    DROP CHECK ck_ai_course_fallback_binding_provider,
    ADD CONSTRAINT ck_ai_course_fallback_binding_provider CHECK (provider IN ('OPENAI','GEMINI','OPENROUTER','COHERE'));

ALTER TABLE ai_analysis_provider_decision
    DROP CHECK ck_ai_provider_decision_ai_provider,
    ADD CONSTRAINT ck_ai_provider_decision_ai_provider CHECK (ai_provider IS NULL OR ai_provider IN ('OPENAI','GEMINI','OPENROUTER','COHERE'));
