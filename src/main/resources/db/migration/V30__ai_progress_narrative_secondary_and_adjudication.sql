-- SAGA V30: Progress Narrative (STUDENT/TEAM/COURSE scope), Secondary Brain support, and
-- deterministic Adjudication. V1-V29 remain untouched.

-- Course-scope progress narratives have no single owning project, so project_id becomes optional
-- and a new nullable course_id anchor is added. Every existing/other analysis type (COMMIT, TASK,
-- STUDENT/TEAM-scope progress and risk) continues to always set project_id exactly as before.
ALTER TABLE ai_analysis_run
    MODIFY COLUMN project_id CHAR(36) NULL,
    ADD COLUMN course_id CHAR(36) NULL AFTER project_id,
    ADD CONSTRAINT fk_ai_analysis_run_course FOREIGN KEY (course_id) REFERENCES course (id),
    ADD KEY ix_ai_analysis_run_course_artifact_created (course_id, artifact_type, artifact_id, created_at),
    ADD CONSTRAINT ck_ai_analysis_run_owner CHECK (project_id IS NOT NULL OR course_id IS NOT NULL);

CREATE TABLE ai_progress_narrative (
    id CHAR(36) NOT NULL,
    analysis_run_id CHAR(36) NOT NULL,
    facts_json MEDIUMTEXT NOT NULL,
    overview TEXT NOT NULL,
    highlights_json MEDIUMTEXT NOT NULL,
    concerns_json MEDIUMTEXT NOT NULL,
    recommendations_json MEDIUMTEXT NOT NULL,
    blockers_json MEDIUMTEXT NOT NULL,
    due_soon_overdue_note TEXT NOT NULL,
    human_review_recommended TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_progress_narrative_run (analysis_run_id),
    CONSTRAINT fk_ai_progress_narrative_run FOREIGN KEY (analysis_run_id) REFERENCES ai_analysis_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Secondary brain: the SECONDARY provider decision is its own row on the existing
-- ai_analysis_provider_decision table (already supports one row per (run, role, config) since
-- V27's uk_ai_provider_decision_run_role_config). It runs and settles fully independently of the
-- run's own PRIMARY-tracked status/started_at/completed_at, so no change to that table is needed.
-- These columns let the secondary lane claim/complete/fail its own row without touching the run.
ALTER TABLE ai_analysis_provider_decision
    ADD COLUMN started_at DATETIME(6) NULL AFTER route;

CREATE TABLE ai_analysis_adjudication (
    id CHAR(36) NOT NULL,
    analysis_run_id CHAR(36) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    disagreement_details_json MEDIUMTEXT NULL,
    human_review_required TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_analysis_adjudication_run (analysis_run_id),
    CONSTRAINT fk_ai_analysis_adjudication_run FOREIGN KEY (analysis_run_id) REFERENCES ai_analysis_run (id),
    CONSTRAINT ck_ai_analysis_adjudication_outcome CHECK (outcome IN ('AGREED','MINOR_DISAGREEMENT','MAJOR_DISAGREEMENT','PRIMARY_ONLY','SECONDARY_ONLY','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
