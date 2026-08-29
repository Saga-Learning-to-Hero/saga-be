-- DEV preflight for V7 invitation identity unique keys.
-- SELECT only. Do not mutate. Do not run Flyway from this file.
-- email / student_code do not exist on student_course_invitation until V7,
-- so identity is resolved through student_profile + user_account.

-- Duplicate course + normalized email. Must return zero rows before apply.
SELECT i.course_id,
       LOWER(TRIM(ua.email)) AS email_norm,
       COUNT(*) AS invitation_count
FROM student_course_invitation i
INNER JOIN student_profile sp ON sp.id = i.student_profile_id
INNER JOIN user_account ua ON ua.id = sp.user_account_id
GROUP BY i.course_id, LOWER(TRIM(ua.email))
HAVING COUNT(*) > 1;

-- Duplicate course + normalized student_code. Must return zero rows before apply.
SELECT i.course_id,
       UPPER(TRIM(sp.student_code)) AS student_code_norm,
       COUNT(*) AS invitation_count
FROM student_course_invitation i
INNER JOIN student_profile sp ON sp.id = i.student_profile_id
WHERE sp.student_code IS NOT NULL
  AND TRIM(sp.student_code) <> ''
GROUP BY i.course_id, UPPER(TRIM(sp.student_code))
HAVING COUNT(*) > 1;
