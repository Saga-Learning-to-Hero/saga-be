-- SAGA V5: Subject + versioned syllabus academic foundation
-- V1/V2/V3/V4 remain immutable. UUID CHAR(36) primary keys only. utf8mb4.
-- Subject stays independent of semester/class/course. No assessment weights. No provider coupling.
-- Composite FKs keep phase/outcome/unit/deliverable references inside one syllabus version.

-- ---------------------------------------------------------------------------
-- SUBJECT: additive catalog fields (reuse existing subject table)
-- name remains the English title.
-- status is the catalog lifecycle (ACTIVE / INACTIVE), independent of deleted_at.
-- deleted_at remains the legacy soft-delete marker. INACTIVE is not deletion.
-- A deleted subject must never be ACTIVE: deleted_at IS NULL OR status = 'INACTIVE'.
-- ---------------------------------------------------------------------------

ALTER TABLE subject
    ADD COLUMN name_vietnamese VARCHAR(255) NULL AFTER name,
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' AFTER name_vietnamese,
    ADD KEY ix_subject_status (status),
    ADD CONSTRAINT chk_subject_status CHECK (status IN ('ACTIVE', 'INACTIVE'));

UPDATE subject
SET status = 'INACTIVE'
WHERE deleted_at IS NOT NULL AND status = 'ACTIVE';

ALTER TABLE subject
    ADD CONSTRAINT chk_subject_lifecycle CHECK (deleted_at IS NULL OR status = 'INACTIVE');

-- ---------------------------------------------------------------------------
-- SYLLABUS VERSION (academic snapshot; not a course offering)
-- ---------------------------------------------------------------------------

CREATE TABLE subject_syllabus_version (
    id CHAR(36) NOT NULL,
    subject_id CHAR(36) NOT NULL,
    external_syllabus_id VARCHAR(64) NULL,
    version_label VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    title_english VARCHAR(255) NULL,
    title_vietnamese VARCHAR(255) NULL,
    credits DECIMAL(6, 2) NULL,
    level VARCHAR(64) NULL,
    learning_teaching_method TEXT NULL,
    time_allocation TEXT NULL,
    prerequisites TEXT NULL,
    description TEXT NULL,
    student_duties TEXT NULL,
    tools TEXT NULL,
    textbooks TEXT NULL,
    reference_materials TEXT NULL,
    grading_scale TEXT NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_syllabus_subject_version_label (subject_id, version_label),
    UNIQUE KEY uk_syllabus_subject_external_id (subject_id, external_syllabus_id),
    KEY ix_syllabus_subject_status (subject_id, status),
    CONSTRAINT chk_syllabus_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT fk_syllabus_subject FOREIGN KEY (subject_id) REFERENCES subject (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- LEARNING OUTCOME
-- ---------------------------------------------------------------------------

CREATE TABLE syllabus_learning_outcome (
    id CHAR(36) NOT NULL,
    syllabus_version_id CHAR(36) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    order_index INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_syllabus_lo_id_version (id, syllabus_version_id),
    UNIQUE KEY uk_syllabus_lo_code (syllabus_version_id, code),
    UNIQUE KEY uk_syllabus_lo_order (syllabus_version_id, order_index),
    CONSTRAINT chk_syllabus_lo_order CHECK (order_index > 0),
    CONSTRAINT fk_syllabus_lo_version FOREIGN KEY (syllabus_version_id) REFERENCES subject_syllabus_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- LEARNING UNIT / TOPIC (what is taught; distinct from phase)
-- ---------------------------------------------------------------------------

CREATE TABLE syllabus_learning_unit (
    id CHAR(36) NOT NULL,
    syllabus_version_id CHAR(36) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    order_index INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_syllabus_unit_id_version (id, syllabus_version_id),
    UNIQUE KEY uk_syllabus_unit_code (syllabus_version_id, code),
    UNIQUE KEY uk_syllabus_unit_order (syllabus_version_id, order_index),
    CONSTRAINT chk_syllabus_unit_order CHECK (order_index > 0),
    CONSTRAINT fk_syllabus_unit_version FOREIGN KEY (syllabus_version_id) REFERENCES subject_syllabus_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- PHASE (how/when work progresses; codes are syllabus data, not Java enums)
-- ---------------------------------------------------------------------------

CREATE TABLE syllabus_phase (
    id CHAR(36) NOT NULL,
    syllabus_version_id CHAR(36) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    order_index INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_syllabus_phase_id_version (id, syllabus_version_id),
    UNIQUE KEY uk_syllabus_phase_code (syllabus_version_id, code),
    UNIQUE KEY uk_syllabus_phase_order (syllabus_version_id, order_index),
    CONSTRAINT chk_syllabus_phase_order CHECK (order_index > 0),
    CONSTRAINT fk_syllabus_phase_version FOREIGN KEY (syllabus_version_id) REFERENCES subject_syllabus_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- EXPECTED ACTIVITY (provider-agnostic)
-- Composite FK keeps activity.syllabus_version_id aligned with its phase.
-- uk_syllabus_activity_code already leads with syllabus_version_id for version queries.
-- ---------------------------------------------------------------------------

CREATE TABLE syllabus_expected_activity (
    id CHAR(36) NOT NULL,
    syllabus_version_id CHAR(36) NOT NULL,
    phase_id CHAR(36) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    order_index INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_syllabus_activity_code (syllabus_version_id, code),
    UNIQUE KEY uk_syllabus_activity_phase_order (phase_id, order_index),
    CONSTRAINT chk_syllabus_activity_order CHECK (order_index > 0),
    CONSTRAINT fk_syllabus_activity_phase_version FOREIGN KEY (phase_id, syllabus_version_id)
        REFERENCES syllabus_phase (id, syllabus_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- EXPECTED DELIVERABLE (provider-agnostic)
-- ---------------------------------------------------------------------------

CREATE TABLE syllabus_expected_deliverable (
    id CHAR(36) NOT NULL,
    syllabus_version_id CHAR(36) NOT NULL,
    phase_id CHAR(36) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    order_index INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_syllabus_deliverable_id_version (id, syllabus_version_id),
    UNIQUE KEY uk_syllabus_deliverable_code (syllabus_version_id, code),
    UNIQUE KEY uk_syllabus_deliverable_phase_order (phase_id, order_index),
    CONSTRAINT chk_syllabus_deliverable_order CHECK (order_index > 0),
    CONSTRAINT fk_syllabus_deliverable_phase_version FOREIGN KEY (phase_id, syllabus_version_id)
        REFERENCES syllabus_phase (id, syllabus_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- ACADEMIC RELATIONSHIPS (blueprint source for a future graph; MySQL SoT)
-- Composite FKs reject cross-syllabus mappings. syllabus_version_id indexes
-- remain because list-by-version cannot use (phase_id, ...) FK indexes.
-- ---------------------------------------------------------------------------

CREATE TABLE syllabus_phase_learning_outcome (
    id CHAR(36) NOT NULL,
    syllabus_version_id CHAR(36) NOT NULL,
    phase_id CHAR(36) NOT NULL,
    learning_outcome_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_phase_lo (phase_id, learning_outcome_id),
    KEY ix_phase_lo_syllabus (syllabus_version_id),
    CONSTRAINT fk_phase_lo_phase_version FOREIGN KEY (phase_id, syllabus_version_id)
        REFERENCES syllabus_phase (id, syllabus_version_id) ON DELETE CASCADE,
    CONSTRAINT fk_phase_lo_outcome_version FOREIGN KEY (learning_outcome_id, syllabus_version_id)
        REFERENCES syllabus_learning_outcome (id, syllabus_version_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE syllabus_deliverable_learning_outcome (
    id CHAR(36) NOT NULL,
    syllabus_version_id CHAR(36) NOT NULL,
    deliverable_id CHAR(36) NOT NULL,
    learning_outcome_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_deliverable_lo (deliverable_id, learning_outcome_id),
    KEY ix_deliverable_lo_syllabus (syllabus_version_id),
    CONSTRAINT fk_deliverable_lo_deliverable_version FOREIGN KEY (deliverable_id, syllabus_version_id)
        REFERENCES syllabus_expected_deliverable (id, syllabus_version_id) ON DELETE CASCADE,
    CONSTRAINT fk_deliverable_lo_outcome_version FOREIGN KEY (learning_outcome_id, syllabus_version_id)
        REFERENCES syllabus_learning_outcome (id, syllabus_version_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE syllabus_learning_unit_outcome (
    id CHAR(36) NOT NULL,
    syllabus_version_id CHAR(36) NOT NULL,
    learning_unit_id CHAR(36) NOT NULL,
    learning_outcome_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_unit_lo (learning_unit_id, learning_outcome_id),
    KEY ix_unit_lo_syllabus (syllabus_version_id),
    CONSTRAINT fk_unit_lo_unit_version FOREIGN KEY (learning_unit_id, syllabus_version_id)
        REFERENCES syllabus_learning_unit (id, syllabus_version_id) ON DELETE CASCADE,
    CONSTRAINT fk_unit_lo_outcome_version FOREIGN KEY (learning_outcome_id, syllabus_version_id)
        REFERENCES syllabus_learning_outcome (id, syllabus_version_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
