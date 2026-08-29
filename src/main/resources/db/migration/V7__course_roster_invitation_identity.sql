-- SAGA V7: Course roster invitation identity without phantom accounts
-- V1–V6 remain immutable. UUID CHAR(36). Additive/safe. utf8mb4.
-- UNAPPLIED. Do not run against shared DEV until preflight is clean.
--
-- student_course_invitation currently requires student_profile_id NOT NULL,
-- so a no-account roster invite cannot be stored. This migration makes the
-- profile optional and persists verified email + student_code + full_name
-- for later automatic claim.
--
-- Identity CHECK (MySQL 8.4): a row must have student_profile_id
-- OR (nonblank email AND nonblank student_code). full_name is not an identity key.
--
-- Unique keys (course_id, email) and (course_id, student_code) require reuse of
-- the existing invitation row. Do not INSERT a second row for the same identity.
--
-- DEV PREFLIGHT (SELECT only, run before apply; email/student_code columns
-- do not exist yet — join student_profile + user_account):
--
-- SELECT i.course_id, LOWER(TRIM(ua.email)) AS email_norm, COUNT(*) AS n
-- FROM student_course_invitation i
-- INNER JOIN student_profile sp ON sp.id = i.student_profile_id
-- INNER JOIN user_account ua ON ua.id = sp.user_account_id
-- GROUP BY i.course_id, LOWER(TRIM(ua.email))
-- HAVING COUNT(*) > 1;
--
-- SELECT i.course_id, UPPER(TRIM(sp.student_code)) AS student_code_norm, COUNT(*) AS n
-- FROM student_course_invitation i
-- INNER JOIN student_profile sp ON sp.id = i.student_profile_id
-- WHERE sp.student_code IS NOT NULL AND TRIM(sp.student_code) <> ''
-- GROUP BY i.course_id, UPPER(TRIM(sp.student_code))
-- HAVING COUNT(*) > 1;
--
-- See also scripts/migration/V7_preflight_invitation_duplicates.sql

ALTER TABLE student_course_invitation
    MODIFY student_profile_id CHAR(36) NULL,
    ADD COLUMN email VARCHAR(255) NULL AFTER student_profile_id,
    ADD COLUMN student_code VARCHAR(64) NULL AFTER email,
    ADD COLUMN full_name VARCHAR(255) NULL AFTER student_code;

UPDATE student_course_invitation i
INNER JOIN student_profile sp ON sp.id = i.student_profile_id
INNER JOIN user_account ua ON ua.id = sp.user_account_id
SET i.email = ua.email,
    i.student_code = sp.student_code,
    i.full_name = ua.full_name
WHERE i.email IS NULL;

ALTER TABLE student_course_invitation
    ADD UNIQUE KEY uk_invitation_course_email (course_id, email),
    ADD UNIQUE KEY uk_invitation_course_student_code (course_id, student_code),
    ADD KEY ix_invitation_email (email),
    ADD KEY ix_invitation_student_code (student_code),
    ADD CONSTRAINT chk_invitation_identity CHECK (
        student_profile_id IS NOT NULL
        OR (
            email IS NOT NULL
            AND TRIM(email) <> ''
            AND student_code IS NOT NULL
            AND TRIM(student_code) <> ''
        )
    );
