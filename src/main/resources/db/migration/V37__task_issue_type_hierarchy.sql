-- Jira issue-type identity and hierarchy level, exactly as Jira reports them. All nullable:
-- existing rows stay NULL ("unknown") until the next Jira sync writes the real values -- nothing
-- is guessed from the type name. issue_type_level is SAGA's bucket of jira_hierarchy_level:
-- SUBTASK (-1), STANDARD (0), EPIC (1), ABOVE_EPIC (2+). The raw level is kept so Initiative /
-- higher custom levels are not lost.
ALTER TABLE task
    ADD COLUMN issue_type_id VARCHAR(64) NULL,
    ADD COLUMN issue_type_level VARCHAR(16) NULL,
    ADD COLUMN jira_hierarchy_level INT NULL,
    ADD CONSTRAINT ck_task_issue_type_level
        CHECK (issue_type_level IS NULL OR issue_type_level IN ('SUBTASK','STANDARD','EPIC','ABOVE_EPIC'));

-- Children of a Jira parent are looked up by (source, parent issue id): detail "subtasks",
-- delete guard, parent resolution.
CREATE INDEX ix_task_integration_parent_external ON task (jira_integration_id, parent_external_id);
