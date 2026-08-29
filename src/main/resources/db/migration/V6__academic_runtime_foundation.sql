-- SAGA V6: Academic runtime foundation (Semester, Class, Course, syllabus pin)
-- V1–V5 remain immutable. UUID CHAR(36). Additive/safe. utf8mb4.
-- Subject and SyllabusVersion stay semester-independent.
-- AcademicClass belongs to Semester. Course is the teaching offering.
-- Course pins a PUBLISHED syllabus version of its Subject (historical, not "latest").
-- course.semester_id is preserved (query/index compatibility) and proven consistent
-- with academic_class.semester_id via composite FK.

-- ---------------------------------------------------------------------------
-- SYLLABUS: candidate key so Course can prove syllabus belongs to subject
-- ---------------------------------------------------------------------------

ALTER TABLE subject_syllabus_version
    ADD UNIQUE KEY uk_syllabus_id_subject (id, subject_id);

-- ---------------------------------------------------------------------------
-- ACADEMIC CLASS belongs to a Semester
-- Uniqueness is scoped: the same class code may recur in a later semester.
-- semester_id is nullable only for legacy rows that predate this binding.
-- New classes from the API always set semester_id.
-- ---------------------------------------------------------------------------

ALTER TABLE academic_class
    ADD COLUMN semester_id CHAR(36) NULL AFTER id,
    ADD KEY ix_academic_class_semester (semester_id);

UPDATE academic_class ac
INNER JOIN (
    SELECT academic_class_id, MIN(semester_id) AS semester_id
    FROM course
    GROUP BY academic_class_id
    HAVING COUNT(DISTINCT semester_id) = 1
) c ON c.academic_class_id = ac.id
SET ac.semester_id = c.semester_id
WHERE ac.semester_id IS NULL;

ALTER TABLE academic_class
    ADD CONSTRAINT fk_academic_class_semester
        FOREIGN KEY (semester_id) REFERENCES semester (id);

ALTER TABLE academic_class
    DROP INDEX uk_academic_class_code,
    ADD UNIQUE KEY uk_academic_class_semester_code (semester_id, class_code),
    ADD UNIQUE KEY uk_academic_class_id_semester (id, semester_id);

-- ---------------------------------------------------------------------------
-- COURSE pins a syllabus version of the same subject
-- One offering per (academic_class, subject).
-- semester_id stays on course; composite FK keeps it aligned with the class.
-- ---------------------------------------------------------------------------

ALTER TABLE course
    ADD COLUMN syllabus_version_id CHAR(36) NULL AFTER subject_id,
    ADD UNIQUE KEY uk_course_class_subject (academic_class_id, subject_id),
    ADD CONSTRAINT fk_course_syllabus_subject
        FOREIGN KEY (syllabus_version_id, subject_id)
        REFERENCES subject_syllabus_version (id, subject_id),
    ADD CONSTRAINT fk_course_class_semester
        FOREIGN KEY (academic_class_id, semester_id)
        REFERENCES academic_class (id, semester_id);
