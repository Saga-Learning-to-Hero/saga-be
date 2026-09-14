-- NOT a Flyway versioned migration.
-- Filename does not match V{version}__{description}.sql, so Flyway V1-V17 never applied it.
-- Do not run this against production/dev: fk_rubric_subject still exists on drifted
-- databases, and rubric_template.weight was dropped out-of-band already.
-- RubricTemplate.weight was removed from the JPA entity to match live schema.
ALTER TABLE rubric_template
    DROP FOREIGN KEY fk_rubric_subject,
    DROP COLUMN weight;
