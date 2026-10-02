-- Project assistant (chatbox): read-only questions about one project, answered from facts the
-- backend selects for the asker's permissions. Each conversation belongs to one user and one
-- project; nobody else (not even the lecturer) reads another person's conversation.
CREATE TABLE assistant_conversation (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    user_account_id CHAR(36) NOT NULL,
    title VARCHAR(200) NULL,
    last_message_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_assistant_conversation_owner (user_account_id, project_id, last_message_at),
    CONSTRAINT fk_assistant_conversation_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_assistant_conversation_user FOREIGN KEY (user_account_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One turn: the user's question, or the assistant's answer with its checked citations.
CREATE TABLE assistant_message (
    id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    answer_source VARCHAR(16) NULL,
    insufficient_data TINYINT(1) NULL,
    out_of_scope TINYINT(1) NULL,
    verified TINYINT(1) NULL,
    removed_citation_count INT NULL,
    citations_json TEXT NULL,
    follow_ups_json TEXT NULL,
    fallback_reason VARCHAR(64) NULL,
    credential_source VARCHAR(16) NULL,
    provider_key VARCHAR(64) NULL,
    model_id VARCHAR(128) NULL,
    latency_ms BIGINT NULL,
    feedback_helpful TINYINT(1) NULL,
    feedback_comment VARCHAR(500) NULL,
    feedback_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_assistant_message_conversation (conversation_id, created_at),
    KEY ix_assistant_message_role_created (role, created_at),
    CONSTRAINT fk_assistant_message_conversation FOREIGN KEY (conversation_id) REFERENCES assistant_conversation (id) ON DELETE CASCADE,
    CONSTRAINT ck_assistant_message_role CHECK (role IN ('USER','ASSISTANT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
