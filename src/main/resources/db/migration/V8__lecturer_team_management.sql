-- Lecturer Team Management V1.
-- Evolves existing team / team_member. Does not rewrite V1-V7.
-- Teams may exist before a Project. Dummy project rows must not be created.

ALTER TABLE team
    ADD COLUMN team_no INT NULL AFTER course_id;

UPDATE team t
INNER JOIN (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY course_id ORDER BY created_at, id) AS rn
    FROM team
) numbered ON numbered.id = t.id
SET t.team_no = numbered.rn
WHERE t.team_no IS NULL;

ALTER TABLE team
    MODIFY team_no INT NOT NULL;

ALTER TABLE team
    ADD UNIQUE KEY uk_team_course_team_no (course_id, team_no);

ALTER TABLE team
    MODIFY project_id CHAR(36) NULL;

ALTER TABLE team_member
    ADD UNIQUE KEY uk_team_member_enrollment_once (course_enrollment_id);
