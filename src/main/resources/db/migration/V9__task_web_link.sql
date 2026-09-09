-- Student-submitted evidence URLs on a Task.
-- DOCUMENT/RESEARCH contribution recognizes story points when the task has
-- at least one task_attachment or one task_web_link. Number of links does not
-- increase score. V1 excluded this table; V9 is the explicit follow-up.

CREATE TABLE task_web_link (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    url_hash CHAR(64) NOT NULL,
    title VARCHAR(255) NULL,
    created_by_user_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_web_link_hash (task_id, url_hash),
    KEY ix_task_web_link_task (task_id),
    CONSTRAINT fk_task_web_link_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_web_link_user FOREIGN KEY (created_by_user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
