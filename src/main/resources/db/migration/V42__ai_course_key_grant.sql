-- A course's (lecturer's) AI key is no longer shared by every team: a team uses its own key, and
-- the course key only backs up the teams the lecturer picks here. One row = this team may use it.
CREATE TABLE ai_course_key_grant (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    granted_by_user_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_course_key_grant_project (project_id),
    CONSTRAINT fk_ai_course_key_grant_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_ai_course_key_grant_user FOREIGN KEY (granted_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
