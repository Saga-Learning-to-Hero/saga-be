-- SAGA V31: fixes a single copy-paste omission in V30. ai_analysis_adjudication extends
-- BaseEntity (id/created_at/updated_at) exactly like every other AI result table (ai_task_intelligence,
-- ai_risk_analysis, ai_progress_narrative all got both columns in V29/V30), but V30 only added
-- created_at to ai_analysis_adjudication and omitted updated_at, causing Hibernate schema
-- validation to fail at startup ("missing column [updated_at] in table [ai_analysis_adjudication]").
-- V1-V30 remain untouched; this only adds the missing column using the exact same convention
-- already used for every other created_at/updated_at pair in this codebase.
ALTER TABLE ai_analysis_adjudication
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) AFTER created_at;
