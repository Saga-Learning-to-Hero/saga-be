-- SAGA V32: HYBRID AI CREDENTIAL MODEL, part 1 -- per-course AI automation/fallback settings and
-- per-(course, providerRole) BYOK provider credential storage. V1-V31 remain untouched.
-- Absence of a settings row means automation OFF and platform fallback OFF (safe default); a
-- course is never silently opted into either after this deploys.

CREATE TABLE ai_course_settings (
    id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    automation_enabled TINYINT(1) NOT NULL DEFAULT 0,
    allow_platform_fallback TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_course_settings_course (course_id),
    CONSTRAINT fk_ai_course_settings_course FOREIGN KEY (course_id) REFERENCES course (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row per (course, providerRole), never versioned: a key replacement overwrites
-- encrypted_secret/encryption_nonce in place and resets status to UNVERIFIED; a revoke clears the
-- secret material and sets status=REVOKED. This is the "at most one logical active credential per
-- (course, role)" invariant, enforced trivially by the unique key below rather than a partial
-- active-only index.
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
    UNIQUE KEY uk_ai_course_provider_credential_role (course_id, provider_role),
    KEY ix_ai_course_provider_credential_status (course_id, provider_role, status),
    CONSTRAINT fk_ai_course_provider_credential_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_ai_course_provider_credential_creator FOREIGN KEY (created_by_user_id) REFERENCES user_account (id),
    CONSTRAINT ck_ai_course_provider_credential_role CHECK (provider_role IN ('PRIMARY','SECONDARY')),
    CONSTRAINT ck_ai_course_provider_credential_status CHECK (status IN ('UNVERIFIED','ACTIVE','DEGRADED','INVALID','REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
