SET NAMES utf8mb4;

CREATE TABLE user_account (
    id CHAR(36) NOT NULL,
    email VARCHAR(255) NOT NULL,
    username VARCHAR(64) NULL,
    google_subject VARCHAR(255) NULL,
    full_name VARCHAR(255) NULL,
    avatar_url VARCHAR(500) NULL,
    password_hash VARCHAR(255) NULL,
    account_role VARCHAR(32) NOT NULL,
    account_status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_email (email),
    UNIQUE KEY uk_user_account_username (username),
    UNIQUE KEY uk_user_account_google_subject (google_subject),
    KEY ix_user_account_role_status (account_role, account_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE student_profile (
    id CHAR(36) NOT NULL,
    user_account_id CHAR(36) NOT NULL,
    student_code VARCHAR(64) NULL,
    approved_by_user_id CHAR(36) NULL,
    approved_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_student_profile_user (user_account_id),
    UNIQUE KEY uk_student_profile_code (student_code),
    CONSTRAINT fk_student_profile_user FOREIGN KEY (user_account_id) REFERENCES user_account (id),
    CONSTRAINT fk_student_profile_approver FOREIGN KEY (approved_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE lecturer_profile (
    id CHAR(36) NOT NULL,
    user_account_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_lecturer_profile_user (user_account_id),
    CONSTRAINT fk_lecturer_profile_user FOREIGN KEY (user_account_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE subject (
    id CHAR(36) NOT NULL,
    subject_code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    name_vietnamese VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_subject_code (subject_code),
    KEY ix_subject_status (status),
    CONSTRAINT chk_subject_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_subject_lifecycle CHECK (deleted_at IS NULL OR status = 'INACTIVE')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE semester (
    id CHAR(36) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    start_date DATETIME(6) NULL,
    end_date DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_semester_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE academic_class (
    id CHAR(36) NOT NULL,
    semester_id CHAR(36) NULL,
    class_code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_academic_class_semester_code (semester_id, class_code),
    UNIQUE KEY uk_academic_class_id_semester (id, semester_id),
    KEY ix_academic_class_semester (semester_id),
    CONSTRAINT fk_academic_class_semester FOREIGN KEY (semester_id) REFERENCES semester (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE active_semester_setting (
    singleton_id TINYINT NOT NULL,
    semester_id CHAR(36) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by_user_id CHAR(36) NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT chk_active_semester_singleton CHECK (singleton_id = 1),
    CONSTRAINT fk_active_semester FOREIGN KEY (semester_id) REFERENCES semester (id),
    CONSTRAINT fk_active_semester_updated_by FOREIGN KEY (updated_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
    UNIQUE KEY uk_syllabus_id_subject (id, subject_id),
    KEY ix_syllabus_subject_status (subject_id, status),
    CONSTRAINT chk_syllabus_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT fk_syllabus_subject FOREIGN KEY (subject_id) REFERENCES subject (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

CREATE TABLE course (
    id CHAR(36) NOT NULL,
    subject_id CHAR(36) NOT NULL,
    syllabus_version_id CHAR(36) NULL,
    academic_class_id CHAR(36) NOT NULL,
    semester_id CHAR(36) NOT NULL,
    instructor_id CHAR(36) NULL,
    course_code VARCHAR(64) NULL,
    name VARCHAR(255) NOT NULL,
    code_contribution_weight DOUBLE NOT NULL DEFAULT 25,
    test_contribution_weight DOUBLE NOT NULL DEFAULT 25,
    document_contribution_weight DOUBLE NOT NULL DEFAULT 25,
    research_contribution_weight DOUBLE NOT NULL DEFAULT 25,
    contribution_config_mode VARCHAR(32) NOT NULL DEFAULT 'COURSE',
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_id_course (id),
    UNIQUE KEY uk_course_class_subject (academic_class_id, subject_id),
    KEY ix_course_semester_instructor (semester_id, instructor_id),
    KEY ix_course_subject (subject_id),
    KEY ix_course_class (academic_class_id),
    CONSTRAINT fk_course_subject FOREIGN KEY (subject_id) REFERENCES subject (id),
    CONSTRAINT fk_course_class FOREIGN KEY (academic_class_id) REFERENCES academic_class (id),
    CONSTRAINT fk_course_semester FOREIGN KEY (semester_id) REFERENCES semester (id),
    CONSTRAINT fk_course_instructor FOREIGN KEY (instructor_id) REFERENCES lecturer_profile (id),
    CONSTRAINT fk_course_syllabus_subject FOREIGN KEY (syllabus_version_id, subject_id)
        REFERENCES subject_syllabus_version (id, subject_id),
    CONSTRAINT fk_course_class_semester FOREIGN KEY (academic_class_id, semester_id)
        REFERENCES academic_class (id, semester_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE student_course_invitation (
    id CHAR(36) NOT NULL,
    student_profile_id CHAR(36) NULL,
    email VARCHAR(255) NULL,
    student_code VARCHAR(64) NULL,
    full_name VARCHAR(255) NULL,
    course_id CHAR(36) NOT NULL,
    invitation_type VARCHAR(32) NOT NULL,
    invitation_status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_attempt_at DATETIME(6) NULL,
    processing_started_at DATETIME(6) NULL,
    sent_at DATETIME(6) NULL,
    failure_code VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_invitation_student_course_type (student_profile_id, course_id, invitation_type),
    UNIQUE KEY uk_invitation_course_email (course_id, email),
    UNIQUE KEY uk_invitation_course_student_code (course_id, student_code),
    KEY ix_invitation_status (invitation_status),
    KEY ix_invitation_email (email),
    KEY ix_invitation_student_code (student_code),
    CONSTRAINT chk_invitation_identity CHECK (
        student_profile_id IS NOT NULL
        OR (
            email IS NOT NULL
            AND TRIM(email) <> ''
            AND student_code IS NOT NULL
            AND TRIM(student_code) <> ''
        )
    ),
    CONSTRAINT fk_invitation_student FOREIGN KEY (student_profile_id) REFERENCES student_profile (id),
    CONSTRAINT fk_invitation_course FOREIGN KEY (course_id) REFERENCES course (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE course_enrollment (
    id CHAR(36) NOT NULL,
    student_profile_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    enrollment_status VARCHAR(32) NOT NULL,
    enrolled_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_enrollment_student_course (student_profile_id, course_id),
    UNIQUE KEY uk_enrollment_id_course (id, course_id),
    KEY ix_enrollment_course (course_id),
    CONSTRAINT fk_enrollment_student FOREIGN KEY (student_profile_id) REFERENCES student_profile (id),
    CONSTRAINT fk_enrollment_course FOREIGN KEY (course_id) REFERENCES course (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_type (
    id CHAR(36) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000) NULL,
    criteria_config TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_type_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project (
    id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    project_type_id CHAR(36) NULL,
    name VARCHAR(255) NOT NULL,
    description MEDIUMTEXT NULL,
    repository_url VARCHAR(500) NULL,
    created_by_user_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_project_course (course_id),
    CONSTRAINT fk_project_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_project_type FOREIGN KEY (project_type_id) REFERENCES project_type (id),
    CONSTRAINT fk_project_created_by FOREIGN KEY (created_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE team (
    id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    team_no INT NOT NULL,
    project_id CHAR(36) NULL,
    name VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_project (project_id),
    UNIQUE KEY uk_team_id_course (id, course_id),
    UNIQUE KEY uk_team_course_team_no (course_id, team_no),
    KEY ix_team_course (course_id),
    CONSTRAINT fk_team_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_team_project FOREIGN KEY (project_id) REFERENCES project (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE team_member (
    id CHAR(36) NOT NULL,
    team_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    course_enrollment_id CHAR(36) NOT NULL,
    role_in_team VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_member_enrollment (team_id, course_enrollment_id),
    UNIQUE KEY uk_team_member_enrollment_once (course_enrollment_id),
    KEY ix_team_member_enrollment (course_enrollment_id),
    CONSTRAINT fk_team_member_team_course FOREIGN KEY (team_id, course_id) REFERENCES team (id, course_id),
    CONSTRAINT fk_team_member_enrollment_course FOREIGN KEY (course_enrollment_id, course_id) REFERENCES course_enrollment (id, course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE jira_integration (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    name VARCHAR(255) NULL,
    board_type VARCHAR(32) NULL,
    jira_board_id VARCHAR(64) NULL,
    cloud_id VARCHAR(128) NULL,
    site_url VARCHAR(500) NULL,
    site_name VARCHAR(255) NULL,
    jira_project_id VARCHAR(64) NULL,
    project_key VARCHAR(64) NULL,
    encrypted_access_token TEXT NULL,
    encrypted_refresh_token TEXT NULL,
    token_expires_at DATETIME(6) NULL,
    granted_scopes TEXT NULL,
    connection_status VARCHAR(32) NOT NULL,
    connected_by_user_id CHAR(36) NULL,
    webhook_id VARCHAR(128) NULL,
    webhook_expires_at DATETIME(6) NULL,
    webhook_secret_hash VARCHAR(64) NULL,
    encrypted_webhook_secret TEXT NULL,
    sync_cursor DATETIME(6) NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64) NULL,
    last_synced_at DATETIME(6) NULL,
    last_successful_sync_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    active_cloud_id VARCHAR(128)
        GENERATED ALWAYS AS (CASE WHEN connection_status = 'ACTIVE' THEN cloud_id ELSE NULL END) STORED,
    active_jira_project_id VARCHAR(64)
        GENERATED ALWAYS AS (CASE WHEN connection_status = 'ACTIVE' THEN jira_project_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jira_active_cloud_project (active_cloud_id, active_jira_project_id),
    KEY ix_jira_webhook_expires (connection_status, webhook_expires_at),
    KEY ix_jira_integration_project (project_id),
    CONSTRAINT fk_jira_integration_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_jira_integration_connected_by FOREIGN KEY (connected_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sprint (
    id CHAR(36) NOT NULL,
    jira_integration_id CHAR(36) NOT NULL,
    name VARCHAR(255) NULL,
    external_sprint_id VARCHAR(128) NULL,
    start_date DATETIME(6) NULL,
    end_date DATETIME(6) NULL,
    goal VARCHAR(1000) NULL,
    state VARCHAR(64) NULL,
    complete_date DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sprint_external (jira_integration_id, external_sprint_id),
    CONSTRAINT fk_sprint_jira FOREIGN KEY (jira_integration_id) REFERENCES jira_integration (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    jira_integration_id CHAR(36) NOT NULL,
    sprint_id CHAR(36) NULL,
    assignee_student_id CHAR(36) NULL,
    reporter_student_id CHAR(36) NULL,
    assignee_external_id VARCHAR(128) NULL,
    reporter_external_id VARCHAR(128) NULL,
    blocks_task_id CHAR(36) NULL,
    external_key VARCHAR(64) NULL,
    external_id VARCHAR(64) NULL,
    parent_external_id VARCHAR(64) NULL,
    parent_external_key VARCHAR(64) NULL,
    parent_task_id CHAR(36) NULL,
    title VARCHAR(500) NULL,
    task_type VARCHAR(32) NULL,
    status VARCHAR(32) NULL,
    jira_status_id VARCHAR(64) NULL,
    jira_status_name VARCHAR(128) NULL,
    jira_status_category VARCHAR(64) NULL,
    issue_type_name VARCHAR(64) NULL,
    saga_completion_state VARCHAR(32) NULL,
    priority VARCHAR(32) NULL,
    story_point INT NULL,
    due_date DATETIME(6) NULL,
    start_date DATETIME(6) NULL,
    external_updated_at DATETIME(6) NULL,
    resolved_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    resolution VARCHAR(128) NULL,
    description TEXT NULL,
    labels_json TEXT NULL,
    components_json TEXT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_jira_integration_external_id (jira_integration_id, external_id),
    KEY ix_task_project_sprint (project_id, sprint_id),
    KEY ix_task_assignee (assignee_student_id),
    KEY ix_task_due_date (due_date),
    KEY ix_task_external_key (external_key),
    KEY ix_task_parent_task_id (parent_task_id),
    KEY ix_task_project_jira_integration (project_id, jira_integration_id),
    CONSTRAINT fk_task_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_task_jira_integration FOREIGN KEY (jira_integration_id) REFERENCES jira_integration (id),
    CONSTRAINT fk_task_sprint FOREIGN KEY (sprint_id) REFERENCES sprint (id),
    CONSTRAINT fk_task_assignee FOREIGN KEY (assignee_student_id) REFERENCES student_profile (id),
    CONSTRAINT fk_task_reporter FOREIGN KEY (reporter_student_id) REFERENCES student_profile (id),
    CONSTRAINT fk_task_blocks FOREIGN KEY (blocks_task_id) REFERENCES task (id),
    CONSTRAINT fk_task_parent_task FOREIGN KEY (parent_task_id) REFERENCES task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_attachment (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    external_id VARCHAR(64) NOT NULL,
    filename VARCHAR(512) NULL,
    mime_type VARCHAR(255) NULL,
    size_bytes BIGINT NULL,
    author_external_id VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_attachment_external (task_id, external_id),
    CONSTRAINT fk_task_attachment_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_web_link (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    url_hash CHAR(64) NOT NULL,
    title VARCHAR(255) NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'SAGA',
    external_id VARCHAR(64) NULL,
    created_by_user_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_web_link_hash (task_id, url_hash),
    UNIQUE KEY uk_task_web_link_external (task_id, external_id),
    KEY ix_task_web_link_task (task_id),
    CONSTRAINT fk_task_web_link_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_web_link_user FOREIGN KEY (created_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_file (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    original_filename VARCHAR(512) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'SAGA',
    external_id VARCHAR(64) NULL,
    created_by_user_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_file_hash (task_id, content_hash),
    UNIQUE KEY uk_task_file_external (task_id, external_id),
    KEY ix_task_file_task (task_id),
    CONSTRAINT fk_task_file_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_file_user FOREIGN KEY (created_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE jira_write_operation (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    actor_user_id CHAR(36) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    remote_resource_id VARCHAR(128) NULL,
    remote_resource_key VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    safe_error_code VARCHAR(64) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_jira_write_project_key (project_id, idempotency_key),
    CONSTRAINT fk_jira_write_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_jira_write_actor FOREIGN KEY (actor_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE jira_task_failover_run (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    source_jira_integration_id CHAR(36) NOT NULL,
    target_jira_integration_id CHAR(36) NOT NULL,
    requested_by_user_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    target_sprint_id CHAR(36) NULL,
    default_issue_type_id VARCHAR(64) NULL,
    revoke_source_requested TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY ix_jira_failover_run_project_status (project_id, status),
    KEY ix_jira_failover_run_source (source_jira_integration_id),
    KEY ix_jira_failover_run_target (target_jira_integration_id),
    CONSTRAINT fk_jira_failover_run_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_jira_failover_run_source FOREIGN KEY (source_jira_integration_id) REFERENCES jira_integration (id),
    CONSTRAINT fk_jira_failover_run_target FOREIGN KEY (target_jira_integration_id) REFERENCES jira_integration (id),
    CONSTRAINT fk_jira_failover_run_actor FOREIGN KEY (requested_by_user_id) REFERENCES user_account (id),
    CONSTRAINT fk_jira_failover_run_sprint FOREIGN KEY (target_sprint_id) REFERENCES sprint (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE jira_task_failover_item (
    id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    source_task_id CHAR(36) NOT NULL,
    target_task_id CHAR(36) NULL,
    source_status_snapshot VARCHAR(32) NOT NULL,
    source_external_key_snapshot VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    remote_issue_id VARCHAR(128) NULL,
    remote_issue_key VARCHAR(128) NULL,
    error_code VARCHAR(64) NULL,
    outbound_claim_task_id CHAR(36)
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN (
                    'PENDING',
                    'CREATING',
                    'REMOTE_OUTCOME_UNKNOWN',
                    'REMOTE_BOUND',
                    'SUCCEEDED'
                ) THEN source_task_id
                ELSE NULL
            END
        ) STORED,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jira_failover_item_run_source (run_id, source_task_id),
    UNIQUE KEY uk_jira_failover_item_outbound_claim (outbound_claim_task_id),
    KEY ix_jira_failover_item_source_task (source_task_id),
    KEY ix_jira_failover_item_target_task (target_task_id),
    KEY ix_jira_failover_item_status (status),
    CONSTRAINT fk_jira_failover_item_run FOREIGN KEY (run_id) REFERENCES jira_task_failover_run (id),
    CONSTRAINT fk_jira_failover_item_source_task FOREIGN KEY (source_task_id) REFERENCES task (id),
    CONSTRAINT fk_jira_failover_item_target_task FOREIGN KEY (target_task_id) REFERENCES task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE jira_task_failover_remote_issue_binding (
    id CHAR(36) NOT NULL,
    item_id CHAR(36) NOT NULL,
    target_jira_integration_id CHAR(36) NOT NULL,
    remote_issue_id VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_jira_failover_remote_binding_item (item_id),
    UNIQUE KEY uk_jira_failover_remote_binding_target_issue (target_jira_integration_id, remote_issue_id),
    CONSTRAINT fk_jira_failover_remote_binding_item FOREIGN KEY (item_id) REFERENCES jira_task_failover_item (id),
    CONSTRAINT fk_jira_failover_remote_binding_target FOREIGN KEY (target_jira_integration_id) REFERENCES jira_integration (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE github_installation (
    id CHAR(36) NOT NULL,
    installation_id BIGINT NOT NULL,
    app_id BIGINT NULL,
    installed_by_user_id CHAR(36) NULL,
    project_id CHAR(36) NULL,
    account_login VARCHAR(255) NULL,
    account_type VARCHAR(64) NULL,
    html_url VARCHAR(500) NULL,
    installation_status VARCHAR(32) NOT NULL,
    last_verified_at DATETIME(6) NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_github_installation_id (installation_id),
    KEY ix_github_installation_project (project_id),
    CONSTRAINT fk_github_installation_user FOREIGN KEY (installed_by_user_id) REFERENCES user_account (id),
    CONSTRAINT fk_github_installation_project FOREIGN KEY (project_id) REFERENCES project (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE github_project_installation (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    github_installation_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_github_project_installation (project_id, github_installation_id),
    KEY ix_gpi_project (project_id),
    KEY ix_gpi_installation (github_installation_id),
    CONSTRAINT fk_gpi_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_gpi_installation FOREIGN KEY (github_installation_id) REFERENCES github_installation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE git_repo (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    installation_id CHAR(36) NULL,
    name VARCHAR(255) NULL,
    url VARCHAR(500) NULL,
    provider VARCHAR(32) NOT NULL DEFAULT 'GITHUB',
    repository_id BIGINT NULL,
    owner_login VARCHAR(255) NULL,
    full_name VARCHAR(255) NULL,
    default_branch VARCHAR(128) NULL,
    repository_role VARCHAR(32) NULL,
    is_private TINYINT(1) NULL,
    connection_status VARCHAR(32) NOT NULL,
    sync_cursor DATETIME(6) NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_synced_at DATETIME(6) NULL,
    branch_membership_synced_at DATETIME(6) NULL,
    review_cutover_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    active_provider VARCHAR(32)
        GENERATED ALWAYS AS (CASE WHEN connection_status = 'ACTIVE' THEN provider ELSE NULL END) STORED,
    active_repository_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN connection_status = 'ACTIVE' THEN repository_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_git_repo_project_full_name (project_id, full_name),
    UNIQUE KEY uk_git_repo_active_provider_repository (active_provider, active_repository_id),
    UNIQUE KEY uk_git_repo_project_provider_repository (project_id, provider, repository_id),
    KEY ix_git_repo_project_status (project_id, connection_status),
    CONSTRAINT fk_git_repo_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_git_repo_installation FOREIGN KEY (installation_id) REFERENCES github_installation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE git_issue (
    id CHAR(36) NOT NULL,
    repo_id CHAR(36) NOT NULL,
    author_student_id CHAR(36) NULL,
    assignee_student_id CHAR(36) NULL,
    author_external_id VARCHAR(128) NULL,
    assignee_external_id VARCHAR(128) NULL,
    issue_number INT NULL,
    github_issue_id BIGINT NULL,
    node_id VARCHAR(128) NULL,
    title VARCHAR(500) NULL,
    state VARCHAR(32) NULL,
    closed_at DATETIME(6) NULL,
    external_updated_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_git_issue_github_id (repo_id, github_issue_id),
    UNIQUE KEY uk_git_issue_number (repo_id, issue_number),
    CONSTRAINT fk_git_issue_repo FOREIGN KEY (repo_id) REFERENCES git_repo (id) ON DELETE CASCADE,
    CONSTRAINT fk_git_issue_author FOREIGN KEY (author_student_id) REFERENCES student_profile (id),
    CONSTRAINT fk_git_issue_assignee FOREIGN KEY (assignee_student_id) REFERENCES student_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pull_request (
    id CHAR(36) NOT NULL,
    repo_id CHAR(36) NOT NULL,
    author_student_id CHAR(36) NULL,
    author_external_id VARCHAR(128) NULL,
    title VARCHAR(500) NULL,
    body MEDIUMTEXT NULL,
    github_pull_request_id BIGINT NULL,
    node_id VARCHAR(128) NULL,
    pull_number INT NULL,
    status VARCHAR(32) NULL,
    merged_at DATETIME(6) NULL,
    review_count INT NULL,
    comment_count INT NULL,
    head_ref VARCHAR(255) NULL,
    base_ref VARCHAR(255) NULL,
    external_updated_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pr_github_id (repo_id, github_pull_request_id),
    UNIQUE KEY uk_pr_number (repo_id, pull_number),
    CONSTRAINT fk_pr_repo FOREIGN KEY (repo_id) REFERENCES git_repo (id) ON DELETE CASCADE,
    CONSTRAINT fk_pr_author FOREIGN KEY (author_student_id) REFERENCES student_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE git_commit (
    id CHAR(36) NOT NULL,
    repo_id CHAR(36) NOT NULL,
    author_student_id CHAR(36) NULL,
    sha_hash VARCHAR(64) NOT NULL,
    github_commit_id VARCHAR(64) NULL,
    author_external_id VARCHAR(128) NULL,
    message MEDIUMTEXT NULL,
    committed_at DATETIME(6) NULL,
    additions INT NULL,
    deletions INT NULL,
    files_changed INT NULL,
    signature_verified TINYINT(1) NULL,
    verification_reason VARCHAR(64) NULL,
    head_ref VARCHAR(255) NULL,
    external_updated_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    parent_count INT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_git_commit_repo_sha (repo_id, sha_hash),
    KEY ix_git_commit_sha (sha_hash),
    CONSTRAINT fk_git_commit_repo FOREIGN KEY (repo_id) REFERENCES git_repo (id) ON DELETE CASCADE,
    CONSTRAINT fk_git_commit_author FOREIGN KEY (author_student_id) REFERENCES student_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE git_commit_branch (
    id CHAR(36) NOT NULL,
    git_commit_id CHAR(36) NOT NULL,
    branch_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_git_commit_branch (git_commit_id, branch_name),
    CONSTRAINT fk_git_commit_branch_commit FOREIGN KEY (git_commit_id) REFERENCES git_commit (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pr_review (
    id CHAR(36) NOT NULL,
    pull_request_id CHAR(36) NOT NULL,
    reviewer_student_id CHAR(36) NULL,
    status VARCHAR(32) NULL,
    reviewed_at DATETIME(6) NULL,
    github_review_id BIGINT NULL,
    reviewer_external_id VARCHAR(128) NULL,
    external_updated_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pr_review_github_id (pull_request_id, github_review_id),
    CONSTRAINT fk_pr_review_pr FOREIGN KEY (pull_request_id) REFERENCES pull_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_pr_review_reviewer FOREIGN KEY (reviewer_student_id) REFERENCES student_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE comment (
    id CHAR(36) NOT NULL,
    author_student_id CHAR(36) NULL,
    git_issue_id CHAR(36) NULL,
    pull_request_id CHAR(36) NULL,
    parent_comment_id CHAR(36) NULL,
    task_id CHAR(36) NULL,
    body TEXT NULL,
    source_system VARCHAR(32) NULL,
    external_comment_id VARCHAR(128) NULL,
    author_external_id VARCHAR(128) NULL,
    target_type VARCHAR(32) NULL,
    external_updated_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_comment_issue (git_issue_id),
    KEY ix_comment_pr (pull_request_id),
    KEY ix_comment_task (task_id),
    CONSTRAINT fk_comment_author FOREIGN KEY (author_student_id) REFERENCES student_profile (id),
    CONSTRAINT fk_comment_issue FOREIGN KEY (git_issue_id) REFERENCES git_issue (id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_pr FOREIGN KEY (pull_request_id) REFERENCES pull_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_parent FOREIGN KEY (parent_comment_id) REFERENCES comment (id),
    CONSTRAINT fk_comment_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_git_issue_link (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    git_issue_id CHAR(36) NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'REFERENCE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_git_issue_link_pair (task_id, git_issue_id),
    CONSTRAINT fk_task_issue_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_issue_issue FOREIGN KEY (git_issue_id) REFERENCES git_issue (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE git_issue_commit_link (
    id CHAR(36) NOT NULL,
    git_issue_id CHAR(36) NOT NULL,
    git_commit_id CHAR(36) NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_git_issue_commit_link_pair (git_issue_id, git_commit_id),
    CONSTRAINT fk_issue_commit_issue FOREIGN KEY (git_issue_id) REFERENCES git_issue (id) ON DELETE CASCADE,
    CONSTRAINT fk_issue_commit_commit FOREIGN KEY (git_commit_id) REFERENCES git_commit (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE git_issue_pull_request_link (
    id CHAR(36) NOT NULL,
    git_issue_id CHAR(36) NOT NULL,
    pull_request_id CHAR(36) NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_git_issue_pr_link_pair (git_issue_id, pull_request_id),
    CONSTRAINT fk_issue_pr_issue FOREIGN KEY (git_issue_id) REFERENCES git_issue (id) ON DELETE CASCADE,
    CONSTRAINT fk_issue_pr_pr FOREIGN KEY (pull_request_id) REFERENCES pull_request (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_git_commit_link (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    git_commit_id CHAR(36) NOT NULL,
    link_source VARCHAR(32) NOT NULL,
    jira_key_snapshot VARCHAR(64) NULL,
    confidence VARCHAR(16) NULL,
    metadata_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_git_commit_link (task_id, git_commit_id),
    KEY ix_task_commit_commit (git_commit_id),
    CONSTRAINT fk_task_commit_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_commit_commit FOREIGN KEY (git_commit_id) REFERENCES git_commit (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_pull_request_link (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    pull_request_id CHAR(36) NOT NULL,
    link_source VARCHAR(32) NOT NULL,
    jira_key_snapshot VARCHAR(64) NULL,
    confidence VARCHAR(16) NULL,
    metadata_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_pr_link (task_id, pull_request_id),
    KEY ix_task_pr_pr (pull_request_id),
    CONSTRAINT fk_task_pr_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_pr_pr FOREIGN KEY (pull_request_id) REFERENCES pull_request (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE commit_review_intent (
    id CHAR(36) NOT NULL,
    git_repo_id CHAR(36) NOT NULL,
    git_commit_id CHAR(36) NOT NULL,
    sha_hash VARCHAR(64) NOT NULL,
    review_mode VARCHAR(32) NOT NULL,
    priority VARCHAR(16) NOT NULL,
    priority_rank INT NOT NULL,
    intent_status VARCHAR(32) NOT NULL,
    ai_job_id CHAR(36) NULL,
    review_policy_version VARCHAR(64) NULL,
    last_job_status VARCHAR(32) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    safe_error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_commit_review_intent_repo_sha (git_repo_id, sha_hash),
    CONSTRAINT fk_review_intent_repo FOREIGN KEY (git_repo_id) REFERENCES git_repo (id),
    CONSTRAINT fk_review_intent_commit FOREIGN KEY (git_commit_id) REFERENCES git_commit (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE commit_review_result (
    id CHAR(36) NOT NULL,
    intent_id CHAR(36) NOT NULL,
    ai_job_id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    git_repo_id CHAR(36) NOT NULL,
    git_commit_id CHAR(36) NOT NULL,
    sha_hash VARCHAR(64) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    review_mode VARCHAR(32) NOT NULL,
    traceability_status VARCHAR(32) NOT NULL,
    message_quality VARCHAR(16) NOT NULL,
    code_quality VARCHAR(32) NOT NULL,
    inferred_function_label VARCHAR(32) NULL,
    inferred_function_confidence VARCHAR(16) NULL,
    task_alignment VARCHAR(32) NOT NULL,
    verdict_eligible TINYINT(1) NOT NULL,
    verdict VARCHAR(32) NOT NULL,
    overall_status VARCHAR(32) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    findings_json MEDIUMTEXT NULL,
    evidence_refs_json MEDIUMTEXT NULL,
    completed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_commit_review_result_intent (intent_id),
    UNIQUE KEY uk_commit_review_result_job (ai_job_id),
    CONSTRAINT fk_review_result_intent FOREIGN KEY (intent_id) REFERENCES commit_review_intent (id),
    CONSTRAINT fk_review_result_repo FOREIGN KEY (git_repo_id) REFERENCES git_repo (id),
    CONSTRAINT fk_review_result_commit FOREIGN KEY (git_commit_id) REFERENCES git_commit (id),
    CONSTRAINT fk_review_result_project FOREIGN KEY (project_id) REFERENCES project (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE identity_map (
    id CHAR(36) NOT NULL,
    user_account_id CHAR(36) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_account_id VARCHAR(255) NULL,
    external_username VARCHAR(255) NULL,
    provider_display_name VARCHAR(255) NULL,
    provider_avatar_url VARCHAR(500) NULL,
    provider_instance_id VARCHAR(255) NULL,
    external_email VARCHAR(255) NULL,
    mapping_status VARCHAR(32) NOT NULL,
    is_primary TINYINT(1) NOT NULL DEFAULT 0,
    verified_at DATETIME(6) NULL,
    linked_at DATETIME(6) NULL,
    last_verified_at DATETIME(6) NULL,
    disconnected_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    reviewed_by_user_id CHAR(36) NULL,
    reviewed_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    active_provider_subject VARCHAR(255)
        GENERATED ALWAYS AS (
            CASE
                WHEN mapping_status IN ('ACTIVE', 'VERIFIED', 'PENDING') THEN external_account_id
                ELSE NULL
            END
        ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_identity_active_provider_subject (provider, active_provider_subject),
    KEY ix_identity_user_provider (user_account_id, provider),
    KEY ix_identity_user_provider_primary (user_account_id, provider, is_primary),
    CONSTRAINT fk_identity_map_user FOREIGN KEY (user_account_id) REFERENCES user_account (id),
    CONSTRAINT fk_identity_map_reviewer FOREIGN KEY (reviewed_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE identity_mapping_history (
    id CHAR(36) NOT NULL,
    identity_map_id CHAR(36) NOT NULL,
    user_account_id CHAR(36) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_account_id VARCHAR(255) NOT NULL,
    action VARCHAR(32) NOT NULL,
    previous_status VARCHAR(32) NULL,
    new_status VARCHAR(32) NULL,
    reason VARCHAR(255) NULL,
    source VARCHAR(32) NULL,
    is_primary_snapshot TINYINT(1) NULL,
    previous_state_json JSON NULL,
    new_state_json JSON NULL,
    actor_user_id CHAR(36) NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_identity_history_map (identity_map_id),
    KEY ix_identity_history_user_time (user_account_id, occurred_at),
    CONSTRAINT fk_identity_history_map FOREIGN KEY (identity_map_id) REFERENCES identity_map (id),
    CONSTRAINT fk_identity_history_user FOREIGN KEY (user_account_id) REFERENCES user_account (id),
    CONSTRAINT fk_identity_history_actor FOREIGN KEY (actor_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE webauthn_credential (
    id CHAR(36) NOT NULL,
    user_account_id CHAR(36) NOT NULL,
    credential_id VARCHAR(512) NOT NULL,
    public_key_cose TEXT NOT NULL,
    signature_count BIGINT NOT NULL DEFAULT 0,
    uv_initialized TINYINT(1) NULL,
    backup_eligible TINYINT(1) NULL,
    backup_state TINYINT(1) NULL,
    transports VARCHAR(255) NULL,
    label VARCHAR(255) NULL,
    last_used_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_webauthn_credential_id (credential_id),
    KEY ix_webauthn_user (user_account_id),
    CONSTRAINT fk_webauthn_user FOREIGN KEY (user_account_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE password_reset_token (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_password_reset_token_hash (token_hash),
    KEY ix_password_reset_token_user (user_id, used_at),
    CONSTRAINT fk_password_reset_token_user FOREIGN KEY (user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE webhook_receipt (
    id CHAR(36) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    delivery_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_action VARCHAR(64) NULL,
    target_id CHAR(36) NULL,
    payload_json LONGTEXT NULL,
    receipt_status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    processed_at DATETIME(6) NULL,
    error_category VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_webhook_provider_delivery (provider, delivery_id),
    KEY ix_webhook_status (receipt_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sync_job_log (
    id CHAR(36) NOT NULL,
    target_system VARCHAR(32) NULL,
    target_id CHAR(36) NULL,
    job_type VARCHAR(64) NULL,
    status VARCHAR(32) NULL,
    error_message TEXT NULL,
    error_category VARCHAR(128) NULL,
    failure_stage VARCHAR(64) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    items_processed INT NULL,
    items_failed INT NULL,
    cursor_before DATETIME(6) NULL,
    cursor_after DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_sync_job_status (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_log (
    id CHAR(36) NOT NULL,
    actor_user_id CHAR(36) NULL,
    actor_full_name_snapshot VARCHAR(255) NULL,
    actor_role_snapshot VARCHAR(32) NULL,
    actor_email_snapshot VARCHAR(255) NULL,
    actor_student_code_snapshot VARCHAR(64) NULL,
    context_class_id CHAR(36) NULL,
    context_class_code_snapshot VARCHAR(64) NULL,
    context_class_name_snapshot VARCHAR(255) NULL,
    context_course_id CHAR(36) NULL,
    context_team_id CHAR(36) NULL,
    context_team_no_snapshot INT NULL,
    context_team_name_snapshot VARCHAR(255) NULL,
    context_project_id CHAR(36) NULL,
    context_project_name_snapshot VARCHAR(255) NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id CHAR(36) NULL,
    before_data JSON NULL,
    after_data JSON NULL,
    metadata_json JSON NULL,
    source VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NULL,
    ip_address VARCHAR(64) NULL,
    user_agent VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_audit_actor_time (actor_user_id, occurred_at),
    KEY ix_audit_project_time (context_project_id, occurred_at),
    KEY ix_audit_action_time (action, occurred_at),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_work_session (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    team_id CHAR(36) NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_work_session_task_user_time (task_id, user_id, started_at),
    KEY ix_work_session_user_status (user_id, status),
    CONSTRAINT fk_work_session_task FOREIGN KEY (task_id) REFERENCES task (id),
    CONSTRAINT fk_work_session_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_work_session_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_work_session_team FOREIGN KEY (team_id) REFERENCES team (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE contribution_confirmation (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    project_id CHAR(36) NULL,
    event_state VARCHAR(32) NOT NULL,
    confirmation_method VARCHAR(32) NOT NULL,
    evidence_hash VARCHAR(64) NOT NULL,
    evidence_snapshot_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_confirmation_task_user_time (task_id, user_id, created_at),
    CONSTRAINT fk_confirmation_task FOREIGN KEY (task_id) REFERENCES task (id),
    CONSTRAINT fk_confirmation_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_confirmation_project FOREIGN KEY (project_id) REFERENCES project (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rubric_template (
    id CHAR(36) NOT NULL,
    subject_id CHAR(36) NULL,
    criteria_name VARCHAR(255) NULL,
    weight DECIMAL(10, 4) NULL,
    description VARCHAR(1000) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_rubric_subject FOREIGN KEY (subject_id) REFERENCES subject (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE peer_review (
    id CHAR(36) NOT NULL,
    sprint_id CHAR(36) NOT NULL,
    reviewer_student_id CHAR(36) NOT NULL,
    reviewee_student_id CHAR(36) NOT NULL,
    star_rating INT NULL,
    comment TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_peer_review_sprint_pair (sprint_id, reviewer_student_id, reviewee_student_id),
    CONSTRAINT fk_peer_review_sprint FOREIGN KEY (sprint_id) REFERENCES sprint (id),
    CONSTRAINT fk_peer_review_reviewer FOREIGN KEY (reviewer_student_id) REFERENCES student_profile (id),
    CONSTRAINT fk_peer_review_reviewee FOREIGN KEY (reviewee_student_id) REFERENCES student_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE peer_review_detail (
    id CHAR(36) NOT NULL,
    peer_review_id CHAR(36) NOT NULL,
    rubric_id CHAR(36) NOT NULL,
    criteria_name VARCHAR(255) NOT NULL,
    criteria_order INT NOT NULL,
    star_rating INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_peer_review_detail_review (peer_review_id),
    CONSTRAINT fk_peer_review_detail_review FOREIGN KEY (peer_review_id) REFERENCES peer_review (id) ON DELETE CASCADE,
    CONSTRAINT fk_peer_review_detail_rubric FOREIGN KEY (rubric_id) REFERENCES rubric_template (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_group_weight_config (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    team_id CHAR(36) NOT NULL,
    code_weight DECIMAL(6, 5) NOT NULL,
    test_weight DECIMAL(6, 5) NOT NULL,
    document_weight DECIMAL(6, 5) NOT NULL,
    research_weight DECIMAL(6, 5) NOT NULL,
    note VARCHAR(1000) NULL,
    updated_by_user_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_weight_config_project (project_id),
    CONSTRAINT fk_weight_config_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_weight_config_team FOREIGN KEY (team_id) REFERENCES team (id),
    CONSTRAINT fk_weight_config_user FOREIGN KEY (updated_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE contribution_override (
    id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    team_id CHAR(36) NULL,
    student_profile_id CHAR(36) NULL,
    override_type VARCHAR(64) NOT NULL,
    old_value DECIMAL(10, 4) NULL,
    new_value DECIMAL(10, 4) NULL,
    reason TEXT NULL,
    created_by_user_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_contribution_override_course (course_id),
    CONSTRAINT fk_override_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_override_team FOREIGN KEY (team_id) REFERENCES team (id),
    CONSTRAINT fk_override_student FOREIGN KEY (student_profile_id) REFERENCES student_profile (id),
    CONSTRAINT fk_override_created_by FOREIGN KEY (created_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE assessment_run (
    id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    project_id CHAR(36) NULL,
    sprint_id CHAR(36) NULL,
    run_type VARCHAR(32) NOT NULL,
    calculation_version VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_assessment_run_course_sprint (course_id, sprint_id),
    CONSTRAINT fk_assessment_run_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_assessment_run_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_assessment_run_sprint FOREIGN KEY (sprint_id) REFERENCES sprint (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE assessment_result (
    id CHAR(36) NOT NULL,
    assessment_run_id CHAR(36) NOT NULL,
    student_profile_id CHAR(36) NOT NULL,
    contribution_score DECIMAL(10, 4) NULL,
    peer_review_score DECIMAL(10, 4) NULL,
    final_score DECIMAL(10, 4) NULL,
    breakdown_json JSON NULL,
    calculated_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_assessment_result_run_student (assessment_run_id, student_profile_id),
    CONSTRAINT fk_assessment_result_run FOREIGN KEY (assessment_run_id) REFERENCES assessment_run (id),
    CONSTRAINT fk_assessment_result_student FOREIGN KEY (student_profile_id) REFERENCES student_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification_broadcast (
    id CHAR(36) NOT NULL,
    sender_user_id CHAR(36) NOT NULL,
    audience VARCHAR(64) NOT NULL,
    title VARCHAR(160) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    recipient_count INT NOT NULL DEFAULT 0,
    notification_count INT NOT NULL DEFAULT 0,
    delivery_queued_count INT NOT NULL DEFAULT 0,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_broadcast_sender_key (sender_user_id, idempotency_key),
    CONSTRAINT fk_broadcast_sender FOREIGN KEY (sender_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_notification (
    id CHAR(36) NOT NULL,
    recipient_user_id CHAR(36) NOT NULL,
    broadcast_id CHAR(36) NULL,
    notification_type VARCHAR(64) NOT NULL,
    title VARCHAR(160) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    action_url VARCHAR(500) NULL,
    event_key VARCHAR(255) NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_notification_broadcast_recipient (broadcast_id, recipient_user_id),
    UNIQUE KEY uk_user_notification_recipient_event (recipient_user_id, event_key),
    KEY ix_user_notification_inbox (recipient_user_id, created_at),
    CONSTRAINT fk_user_notification_recipient FOREIGN KEY (recipient_user_id) REFERENCES user_account (id),
    CONSTRAINT fk_user_notification_broadcast FOREIGN KEY (broadcast_id) REFERENCES notification_broadcast (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE firebase_installation (
    id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    firebase_installation_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    fcm_token VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    platform VARCHAR(16) NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    last_registered_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_firebase_installation_fid (firebase_installation_id),
    UNIQUE KEY uk_firebase_installation_fcm_token (fcm_token),
    KEY ix_firebase_owner (owner_user_id, active),
    CONSTRAINT fk_firebase_owner FOREIGN KEY (owner_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification_delivery (
    id CHAR(36) NOT NULL,
    notification_id CHAR(36) NOT NULL,
    installation_id CHAR(36) NOT NULL,
    delivery_status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_attempt_at DATETIME(6) NULL,
    processing_started_at DATETIME(6) NULL,
    sent_at DATETIME(6) NULL,
    failure_code VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_delivery_installation (notification_id, installation_id),
    KEY ix_delivery_status (delivery_status),
    CONSTRAINT fk_delivery_notification FOREIGN KEY (notification_id) REFERENCES user_notification (id) ON DELETE CASCADE,
    CONSTRAINT fk_delivery_installation FOREIGN KEY (installation_id) REFERENCES firebase_installation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE email_outbox (
    id CHAR(36) NOT NULL,
    recipient_user_id CHAR(36) NULL,
    recipient_email VARCHAR(255) NOT NULL,
    email_type VARCHAR(64) NOT NULL,
    template_key VARCHAR(128) NULL,
    payload_json JSON NULL,
    delivery_status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    scheduled_at DATETIME(6) NULL,
    sent_at DATETIME(6) NULL,
    last_failure_code VARCHAR(64) NULL,
    last_attempt_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_email_outbox_status (delivery_status, scheduled_at),
    CONSTRAINT fk_email_outbox_user FOREIGN KEY (recipient_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE business_warning (
    id CHAR(36) NOT NULL,
    warning_type VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    event_key VARCHAR(255) NOT NULL,
    severity VARCHAR(32) NULL,
    course_id CHAR(36) NULL,
    team_id CHAR(36) NULL,
    project_id CHAR(36) NULL,
    sprint_id CHAR(36) NULL,
    student_profile_id CHAR(36) NULL,
    commit_sha VARCHAR(64) NULL,
    evidence_summary VARCHAR(1000) NOT NULL,
    progress_mode VARCHAR(32) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_warning_event (event_key),
    KEY ix_business_warning_course (course_id),
    CONSTRAINT fk_warning_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_warning_team FOREIGN KEY (team_id) REFERENCES team (id),
    CONSTRAINT fk_warning_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_warning_sprint FOREIGN KEY (sprint_id) REFERENCES sprint (id),
    CONSTRAINT fk_warning_student FOREIGN KEY (student_profile_id) REFERENCES student_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_agent_delegation_context (
    id CHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    actor_user_id CHAR(36) NOT NULL,
    actor_application_role VARCHAR(32) NOT NULL,
    capabilities VARCHAR(128) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    course_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_delegation_token (token_hash),
    CONSTRAINT fk_ai_delegation_actor FOREIGN KEY (actor_user_id) REFERENCES user_account (id),
    CONSTRAINT fk_ai_delegation_course FOREIGN KEY (course_id) REFERENCES course (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_agent_conversation_scope (
    id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    owner_application_role VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_conversation_id (conversation_id),
    CONSTRAINT fk_ai_scope_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_ai_scope_owner FOREIGN KEY (owner_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE graph_processing_run (
    id CHAR(36) NOT NULL,
    graph_kind VARCHAR(32) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    course_id CHAR(36) NULL,
    team_id CHAR(36) NULL,
    student_profile_id CHAR(36) NULL,
    nodes_built INT NOT NULL DEFAULT 0,
    edges_built INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_graph_processing_run_occurred_at (occurred_at),
    KEY ix_graph_processing_run_kind_occurred_at (graph_kind, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_event (
    id CHAR(36) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_outbox_status_available (status, available_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_course_settings (
    id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    automation_enabled TINYINT(1) NOT NULL DEFAULT 0,
    allow_platform_fallback TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    primary_provider VARCHAR(32) NULL,
    primary_model_id VARCHAR(128) NULL,
    fallback_enabled TINYINT(1) NOT NULL DEFAULT 0,
    secondary_provider VARCHAR(32) NULL,
    secondary_model_id VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_course_settings_course (course_id),
    CONSTRAINT fk_ai_course_settings_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT ck_ai_course_settings_primary_provider CHECK (primary_provider IS NULL OR primary_provider IN ('OPENAI','GEMINI','OPENROUTER','COHERE')),
    CONSTRAINT ck_ai_course_settings_primary_pair CHECK ((primary_provider IS NULL) = (primary_model_id IS NULL)),
    CONSTRAINT ck_ai_course_settings_secondary_provider CHECK (secondary_provider IS NULL OR secondary_provider IN ('OPENAI','GEMINI','OPENROUTER','COHERE')),
    CONSTRAINT ck_ai_course_settings_secondary_pair CHECK ((secondary_provider IS NULL) = (secondary_model_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_course_provider_credential (
    id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    provider_role VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    encrypted_secret TEXT NOT NULL,
    encryption_nonce VARCHAR(32) NOT NULL,
    encryption_key_version INT NOT NULL DEFAULT 1,
    fingerprint CHAR(64) NOT NULL,
    last_four VARCHAR(4) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by_user_id CHAR(36) NULL,
    last_successful_use_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_course_provider_credential_role_provider (course_id, provider_role, provider),
    KEY ix_ai_course_provider_credential_status (course_id, provider_role, status),
    CONSTRAINT fk_ai_course_provider_credential_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_ai_course_provider_credential_creator FOREIGN KEY (created_by_user_id) REFERENCES user_account (id),
    CONSTRAINT ck_ai_course_provider_credential_role CHECK (provider_role IN ('PRIMARY','SECONDARY')),
    CONSTRAINT ck_ai_course_provider_credential_status CHECK (status IN ('UNVERIFIED','ACTIVE','DEGRADED','INVALID','REVOKED')),
    CONSTRAINT ck_ai_course_provider_credential_provider CHECK (provider IN ('OPENAI','GEMINI','OPENROUTER','COHERE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
    CONSTRAINT ck_ai_course_fallback_binding_provider CHECK (provider IN ('OPENAI','GEMINI','OPENROUTER','COHERE')),
    CONSTRAINT ck_ai_course_fallback_binding_order CHECK (attempt_order BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_analysis_run (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NULL,
    course_id CHAR(36) NULL,
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
    canonical_identity_key CHAR(64) NOT NULL,
    retry_attempt INT UNSIGNED NOT NULL,
    requested_by_user_id CHAR(36) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    failure_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_analysis_run_idempotency (idempotency_key),
    UNIQUE KEY uk_ai_analysis_run_canonical_retry (canonical_identity_key, retry_attempt),
    KEY ix_ai_analysis_run_project_artifact_created (project_id, artifact_type, artifact_id, created_at),
    KEY ix_ai_analysis_run_status_started (status, started_at),
    KEY ix_ai_analysis_run_course_artifact_created (course_id, artifact_type, artifact_id, created_at),
    KEY ix_ai_analysis_run_canonical_attempt (canonical_identity_key, retry_attempt, status),
    CONSTRAINT fk_ai_analysis_run_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_ai_analysis_run_requested_by FOREIGN KEY (requested_by_user_id) REFERENCES user_account (id),
    CONSTRAINT fk_ai_analysis_run_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT ck_ai_analysis_run_owner CHECK (project_id IS NOT NULL OR course_id IS NOT NULL)
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
    credential_source VARCHAR(16) NULL,
    course_credential_id CHAR(36) NULL,
    credential_fingerprint CHAR(64) NULL,
    ai_provider VARCHAR(32) NULL,
    model_id VARCHAR(128) NOT NULL,
    model_revision VARCHAR(128) NULL,
    route VARCHAR(32) NOT NULL,
    started_at DATETIME(6) NULL,
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
    fallback_attempts_json MEDIUMTEXT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_provider_decision_run_role_config (analysis_run_id, provider_role, provider_config_hash),
    KEY ix_ai_provider_decision_run (analysis_run_id),
    CONSTRAINT fk_ai_provider_decision_run FOREIGN KEY (analysis_run_id) REFERENCES ai_analysis_run (id),
    CONSTRAINT fk_ai_provider_decision_course_credential FOREIGN KEY (course_credential_id) REFERENCES ai_course_provider_credential (id),
    CONSTRAINT ck_ai_provider_decision_credential_source CHECK (credential_source IS NULL OR credential_source IN ('COURSE','PLATFORM')),
    CONSTRAINT ck_ai_provider_decision_ai_provider CHECK (ai_provider IS NULL OR ai_provider IN ('OPENAI','GEMINI','OPENROUTER','COHERE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_analysis_adjudication (
    id CHAR(36) NOT NULL,
    analysis_run_id CHAR(36) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    disagreement_details_json MEDIUMTEXT NULL,
    human_review_required TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_analysis_adjudication_run (analysis_run_id),
    CONSTRAINT fk_ai_analysis_adjudication_run FOREIGN KEY (analysis_run_id) REFERENCES ai_analysis_run (id),
    CONSTRAINT ck_ai_analysis_adjudication_outcome CHECK (outcome IN ('AGREED','MINOR_DISAGREEMENT','MAJOR_DISAGREEMENT','PRIMARY_ONLY','SECONDARY_ONLY','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_academic_classification (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    analysis_run_id CHAR(36) NOT NULL,
    artifact_type VARCHAR(32) NOT NULL,
    artifact_id CHAR(36) NOT NULL,
    artifact_revision VARCHAR(128) NOT NULL,
    syllabus_version_id CHAR(36) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    phase_id CHAR(36) NULL,
    deliverable_id CHAR(36) NULL,
    confidence DOUBLE NOT NULL,
    ai_summary TEXT NULL,
    status VARCHAR(32) NOT NULL,
    provenance VARCHAR(32) NOT NULL,
    source_classification_id CHAR(36) NULL,
    reviewed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_academic_classification_analysis_phase (analysis_run_id, phase_id),
    UNIQUE KEY uk_ai_academic_classification_analysis_deliverable (analysis_run_id, deliverable_id),
    KEY ix_ai_academic_classification_artifact (project_id, artifact_type, artifact_id, artifact_revision),
    KEY ix_ai_academic_classification_authoritative (project_id, artifact_type, artifact_id, artifact_revision, status, provenance),
    CONSTRAINT fk_ai_academic_classification_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_ai_academic_classification_run FOREIGN KEY (analysis_run_id) REFERENCES ai_analysis_run (id),
    CONSTRAINT fk_ai_academic_classification_source FOREIGN KEY (source_classification_id) REFERENCES ai_academic_classification (id),
    CONSTRAINT fk_ai_academic_classification_syllabus FOREIGN KEY (syllabus_version_id) REFERENCES subject_syllabus_version (id),
    CONSTRAINT fk_ai_academic_classification_phase FOREIGN KEY (phase_id) REFERENCES syllabus_phase (id),
    CONSTRAINT fk_ai_academic_classification_deliverable FOREIGN KEY (deliverable_id) REFERENCES syllabus_expected_deliverable (id),
    CONSTRAINT ck_ai_academic_classification_target CHECK ((target_type = 'PHASE' AND phase_id IS NOT NULL AND deliverable_id IS NULL) OR (target_type = 'EXPECTED_DELIVERABLE' AND phase_id IS NULL AND deliverable_id IS NOT NULL)),
    CONSTRAINT ck_ai_academic_classification_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_ai_academic_classification_provenance CHECK ((provenance = 'AI' AND source_classification_id IS NULL) OR (provenance = 'HUMAN' AND source_classification_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_academic_classification_review (
    id CHAR(36) NOT NULL,
    classification_id CHAR(36) NOT NULL,
    action VARCHAR(32) NOT NULL,
    reviewer_user_id CHAR(36) NOT NULL,
    reason TEXT NULL,
    corrected_target_type VARCHAR(32) NULL,
    corrected_phase_id CHAR(36) NULL,
    corrected_deliverable_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_academic_classification_review_once (classification_id),
    CONSTRAINT fk_ai_academic_review_classification FOREIGN KEY (classification_id) REFERENCES ai_academic_classification (id),
    CONSTRAINT fk_ai_academic_review_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES user_account (id),
    CONSTRAINT fk_ai_academic_review_phase FOREIGN KEY (corrected_phase_id) REFERENCES syllabus_phase (id),
    CONSTRAINT fk_ai_academic_review_deliverable FOREIGN KEY (corrected_deliverable_id) REFERENCES syllabus_expected_deliverable (id),
    CONSTRAINT ck_ai_academic_review_correction CHECK ((action IN ('CONFIRM','REJECT') AND corrected_target_type IS NULL AND corrected_phase_id IS NULL AND corrected_deliverable_id IS NULL) OR (action = 'CORRECT' AND ((corrected_target_type = 'PHASE' AND corrected_phase_id IS NOT NULL AND corrected_deliverable_id IS NULL) OR (corrected_target_type = 'EXPECTED_DELIVERABLE' AND corrected_phase_id IS NULL AND corrected_deliverable_id IS NOT NULL))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

INSERT INTO active_semester_setting (singleton_id, semester_id) VALUES (1, NULL);

INSERT INTO project_type (id, code, name, description) VALUES
    ('11111111-1111-1111-1111-111111111111', 'DESIGN_ARCHITECTURE', 'Design / Architecture', 'Canonical SAGA project-type catalog'),
    ('22222222-2222-2222-2222-222222222222', 'RESEARCH', 'Research', 'Canonical SAGA project-type catalog'),
    ('33333333-3333-3333-3333-333333333333', 'TESTER', 'Tester', 'Canonical SAGA project-type catalog'),
    ('44444444-4444-4444-4444-444444444444', 'DOCUMENT', 'Document', 'Canonical SAGA project-type catalog');

INSERT INTO rubric_template (id, subject_id, criteria_name, description) VALUES
    ('c0a80101-0000-4000-8000-000000000001', NULL, 'Hoàn thành & Chất lượng',
     'Làm đúng, đủ task được giao; code/chức năng chạy ổn định, ít lỗi.'),
    ('c0a80101-0000-4000-8000-000000000002', NULL, 'Tiến độ & Quy trình',
     'Đáp ứng đúng deadline; đẩy/merge code kịp thời, không làm kẹt tiến độ chung.'),
    ('c0a80101-0000-4000-8000-000000000003', NULL, 'Giao tiếp & Hỗ trợ',
     'Dễ liên lạc; chủ động phối hợp và sẵn sàng giúp đỡ đồng đội.'),
    ('c0a80101-0000-4000-8000-000000000004', NULL, 'Thái độ & Xử lý sự cố',
     'Chịu trách nhiệm với công việc được giao; xử lý sự cố kịp thời và hiệu quả, cởi mở tiếp thu góp ý.');