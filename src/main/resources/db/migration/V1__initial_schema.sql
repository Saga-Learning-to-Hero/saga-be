-- SAGA V2 initial schema. Empty-database baseline. MySQL 8.4.
-- CHAR(36) UUID PKs. Do not apply against the old database.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- ACCOUNT / IDENTITY
-- ---------------------------------------------------------------------------

CREATE TABLE user_account (
    id CHAR(36) NOT NULL,
    email VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NULL,
    avatar_url VARCHAR(500) NULL,
    account_role VARCHAR(32) NOT NULL,
    account_status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_email (email),
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

-- ---------------------------------------------------------------------------
-- ACADEMIC
-- ---------------------------------------------------------------------------

CREATE TABLE subject (
    id CHAR(36) NOT NULL,
    subject_code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_subject_code (subject_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE academic_class (
    id CHAR(36) NOT NULL,
    class_code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_academic_class_code (class_code)
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

INSERT INTO active_semester_setting (singleton_id, semester_id) VALUES (1, NULL);

CREATE TABLE course (
    id CHAR(36) NOT NULL,
    subject_id CHAR(36) NOT NULL,
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
    KEY ix_course_semester_instructor (semester_id, instructor_id),
    KEY ix_course_subject (subject_id),
    KEY ix_course_class (academic_class_id),
    CONSTRAINT fk_course_subject FOREIGN KEY (subject_id) REFERENCES subject (id),
    CONSTRAINT fk_course_class FOREIGN KEY (academic_class_id) REFERENCES academic_class (id),
    CONSTRAINT fk_course_semester FOREIGN KEY (semester_id) REFERENCES semester (id),
    CONSTRAINT fk_course_instructor FOREIGN KEY (instructor_id) REFERENCES lecturer_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE student_course_invitation (
    id CHAR(36) NOT NULL,
    student_profile_id CHAR(36) NOT NULL,
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
    KEY ix_invitation_status (invitation_status),
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

-- ---------------------------------------------------------------------------
-- PROJECT / TEAM
-- ---------------------------------------------------------------------------

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

INSERT INTO project_type (id, code, name, description) VALUES
    ('11111111-1111-1111-1111-111111111111', 'DESIGN_ARCHITECTURE', 'Design / Architecture', 'Canonical SAGA project-type catalog'),
    ('22222222-2222-2222-2222-222222222222', 'RESEARCH', 'Research', 'Canonical SAGA project-type catalog'),
    ('33333333-3333-3333-3333-333333333333', 'TESTER', 'Tester', 'Canonical SAGA project-type catalog'),
    ('44444444-4444-4444-4444-444444444444', 'DOCUMENT', 'Document', 'Canonical SAGA project-type catalog');

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
    project_id CHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_project (project_id),
    UNIQUE KEY uk_team_id_course (id, course_id),
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
    KEY ix_team_member_enrollment (course_enrollment_id),
    CONSTRAINT fk_team_member_team_course FOREIGN KEY (team_id, course_id) REFERENCES team (id, course_id),
    CONSTRAINT fk_team_member_enrollment_course FOREIGN KEY (course_enrollment_id, course_id) REFERENCES course_enrollment (id, course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- JIRA
-- ---------------------------------------------------------------------------

CREATE TABLE jira_integration (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    name VARCHAR(255) NULL,
    board_type VARCHAR(32) NULL,
    jira_board_id VARCHAR(64) NULL,
    cloud_id VARCHAR(128) NULL,
    site_url VARCHAR(500) NULL,
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
    sync_cursor DATETIME(6) NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_synced_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_jira_integration_project (project_id),
    UNIQUE KEY uk_jira_cloud_project (cloud_id, jira_project_id),
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
    sprint_id CHAR(36) NULL,
    assignee_student_id CHAR(36) NULL,
    reporter_student_id CHAR(36) NULL,
    assignee_external_id VARCHAR(128) NULL,
    reporter_external_id VARCHAR(128) NULL,
    blocks_task_id CHAR(36) NULL,
    external_key VARCHAR(64) NULL,
    external_id VARCHAR(64) NULL,
    title VARCHAR(500) NULL,
    task_type VARCHAR(32) NULL,
    status VARCHAR(32) NULL,
    priority VARCHAR(32) NULL,
    story_point INT NULL,
    due_date DATETIME(6) NULL,
    external_updated_at DATETIME(6) NULL,
    resolved_at DATETIME(6) NULL,
    resolution VARCHAR(128) NULL,
    description TEXT NULL,
    labels_json TEXT NULL,
    components_json TEXT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_project_external_id (project_id, external_id),
    KEY ix_task_project_sprint (project_id, sprint_id),
    KEY ix_task_assignee (assignee_student_id),
    KEY ix_task_due_date (due_date),
    KEY ix_task_external_key (external_key),
    CONSTRAINT fk_task_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_task_sprint FOREIGN KEY (sprint_id) REFERENCES sprint (id),
    CONSTRAINT fk_task_assignee FOREIGN KEY (assignee_student_id) REFERENCES student_profile (id),
    CONSTRAINT fk_task_reporter FOREIGN KEY (reporter_student_id) REFERENCES student_profile (id),
    CONSTRAINT fk_task_blocks FOREIGN KEY (blocks_task_id) REFERENCES task (id)
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

-- ---------------------------------------------------------------------------
-- GITHUB
-- ---------------------------------------------------------------------------

CREATE TABLE github_installation (
    id CHAR(36) NOT NULL,
    installation_id BIGINT NOT NULL,
    installed_by_user_id CHAR(36) NULL,
    account_login VARCHAR(255) NULL,
    account_type VARCHAR(64) NULL,
    installation_status VARCHAR(32) NOT NULL,
    last_verified_at DATETIME(6) NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_github_installation_id (installation_id),
    CONSTRAINT fk_github_installation_user FOREIGN KEY (installed_by_user_id) REFERENCES user_account (id)
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
    connection_status VARCHAR(32) NOT NULL,
    sync_cursor DATETIME(6) NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_synced_at DATETIME(6) NULL,
    review_cutover_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_git_repo_provider_id (provider, repository_id),
    UNIQUE KEY uk_git_repo_project_full_name (project_id, full_name),
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
    github_pull_request_id BIGINT NULL,
    node_id VARCHAR(128) NULL,
    pull_number INT NULL,
    status VARCHAR(32) NULL,
    merged_at DATETIME(6) NULL,
    review_count INT NULL,
    comment_count INT NULL,
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
    external_updated_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_git_commit_repo_sha (repo_id, sha_hash),
    KEY ix_git_commit_sha (sha_hash),
    CONSTRAINT fk_git_commit_repo FOREIGN KEY (repo_id) REFERENCES git_repo (id) ON DELETE CASCADE,
    CONSTRAINT fk_git_commit_author FOREIGN KEY (author_student_id) REFERENCES student_profile (id)
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
    CONSTRAINT fk_comment_author FOREIGN KEY (author_student_id) REFERENCES student_profile (id),
    CONSTRAINT fk_comment_issue FOREIGN KEY (git_issue_id) REFERENCES git_issue (id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_pr FOREIGN KEY (pull_request_id) REFERENCES pull_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_parent FOREIGN KEY (parent_comment_id) REFERENCES comment (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- TRACEABILITY (canonical; no competing FKs on PR/commit)
-- ---------------------------------------------------------------------------

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

-- ---------------------------------------------------------------------------
-- IDENTITY / SYNC
-- ---------------------------------------------------------------------------

CREATE TABLE identity_map (
    id CHAR(36) NOT NULL,
    user_account_id CHAR(36) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_account_id VARCHAR(255) NULL,
    external_username VARCHAR(255) NULL,
    external_email VARCHAR(255) NULL,
    mapping_status VARCHAR(32) NOT NULL,
    verified_at DATETIME(6) NULL,
    disconnected_at DATETIME(6) NULL,
    reviewed_by_user_id CHAR(36) NULL,
    reviewed_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_identity_user_provider (user_account_id, provider),
    UNIQUE KEY uk_identity_provider_external (provider, external_account_id),
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
    actor_user_id CHAR(36) NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_identity_history_map (identity_map_id),
    CONSTRAINT fk_identity_history_map FOREIGN KEY (identity_map_id) REFERENCES identity_map (id),
    CONSTRAINT fk_identity_history_user FOREIGN KEY (user_account_id) REFERENCES user_account (id),
    CONSTRAINT fk_identity_history_actor FOREIGN KEY (actor_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE webhook_receipt (
    id CHAR(36) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    delivery_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
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

-- ---------------------------------------------------------------------------
-- ASSESSMENT / CONTRIBUTION
-- ---------------------------------------------------------------------------

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

-- ---------------------------------------------------------------------------
-- NOTIFICATION / EMAIL / FIREBASE
-- ---------------------------------------------------------------------------

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
    firebase_installation_id VARCHAR(255) NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    last_registered_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_firebase_installation_fid (firebase_installation_id),
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

-- ---------------------------------------------------------------------------
-- WARNING / AI / GRAPH / OUTBOX
-- ---------------------------------------------------------------------------

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
