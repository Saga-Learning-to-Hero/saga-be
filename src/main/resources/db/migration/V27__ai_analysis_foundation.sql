-- SAGA V27: provider-neutral AI analysis foundation. MySQL remains the source of truth.
-- Legacy V1 AI/review tables remain intentionally untouched.

CREATE TABLE ai_analysis_run (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    artifact_type VARCHAR(32) NOT NULL,
    artifact_id CHAR(36) NOT NULL,
    artifact_revision VARCHAR(128) NOT NULL,
    analysis_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    taxonomy_version VARCHAR(64) NULL,
    provider_config_hash CHAR(64) NOT NULL,
    idempotency_key CHAR(64) NOT NULL,
    requested_by_user_id CHAR(36) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    failure_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_analysis_run_idempotency (idempotency_key),
    KEY ix_ai_analysis_run_project_artifact_created (project_id, artifact_type, artifact_id, created_at),
    KEY ix_ai_analysis_run_status_started (status, started_at),
    CONSTRAINT fk_ai_analysis_run_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_ai_analysis_run_requested_by FOREIGN KEY (requested_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_analysis_evidence (
    id CHAR(36) NOT NULL,
    analysis_run_id CHAR(36) NOT NULL,
    evidence_type VARCHAR(64) NOT NULL,
    source_ref VARCHAR(512) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    payload_json MEDIUMTEXT NULL,
    metadata_json MEDIUMTEXT NULL,
    ordinal_index INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_analysis_evidence_run_ordinal (analysis_run_id, ordinal_index),
    KEY ix_ai_analysis_evidence_run_type (analysis_run_id, evidence_type),
    CONSTRAINT fk_ai_analysis_evidence_run FOREIGN KEY (analysis_run_id) REFERENCES ai_analysis_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_analysis_provider_decision (
    id CHAR(36) NOT NULL,
    analysis_run_id CHAR(36) NOT NULL,
    provider_role VARCHAR(32) NOT NULL,
    provider_key VARCHAR(64) NOT NULL,
    provider_config_hash CHAR(64) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    model_revision VARCHAR(128) NULL,
    route VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    structured_result_json MEDIUMTEXT NULL,
    schema_valid TINYINT(1) NULL,
    latency_ms BIGINT NULL,
    input_units BIGINT NULL,
    output_units BIGINT NULL,
    cost_metadata_json MEDIUMTEXT NULL,
    safe_error_code VARCHAR(64) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_provider_decision_run_role_config (analysis_run_id, provider_role, provider_config_hash),
    KEY ix_ai_provider_decision_run (analysis_run_id),
    CONSTRAINT fk_ai_provider_decision_run FOREIGN KEY (analysis_run_id) REFERENCES ai_analysis_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
