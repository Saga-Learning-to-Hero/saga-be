-- SAGA V29: Task Intelligence and Risk Analysis result tables, built on the
-- V27 AI analysis foundation (ai_analysis_run/ai_analysis_evidence/ai_analysis_provider_decision).
-- V1-V28 remain untouched.

CREATE TABLE ai_task_intelligence (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    analysis_run_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    task_revision VARCHAR(128) NOT NULL,
    evidence_strength VARCHAR(32) NOT NULL,
    summary TEXT NOT NULL,
    deviation_detected TINYINT(1) NOT NULL DEFAULT 0,
    deviation_summary TEXT NULL,
    human_review_required TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_task_intelligence_run (analysis_run_id),
    KEY ix_ai_task_intelligence_task (project_id, task_id, created_at),
    CONSTRAINT fk_ai_task_intelligence_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_ai_task_intelligence_run FOREIGN KEY (analysis_run_id) REFERENCES ai_analysis_run (id),
    CONSTRAINT fk_ai_task_intelligence_task FOREIGN KEY (task_id) REFERENCES task (id),
    CONSTRAINT ck_ai_task_intelligence_evidence_strength CHECK (evidence_strength IN ('NO_EVIDENCE','EARLY_EVIDENCE','ACTIVE_PROGRESS','SUBSTANTIAL_EVIDENCE','COMPLETED_EVIDENCE','INSUFFICIENT_EVIDENCE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_risk_analysis (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    analysis_run_id CHAR(36) NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    reasons_json MEDIUMTEXT NOT NULL,
    recommended_actions_json MEDIUMTEXT NOT NULL,
    confidence DOUBLE NULL,
    human_review_recommended TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_risk_analysis_run (analysis_run_id),
    KEY ix_ai_risk_analysis_project (project_id, created_at),
    CONSTRAINT fk_ai_risk_analysis_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_ai_risk_analysis_run FOREIGN KEY (analysis_run_id) REFERENCES ai_analysis_run (id),
    CONSTRAINT ck_ai_risk_analysis_level CHECK (risk_level IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT ck_ai_risk_analysis_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
