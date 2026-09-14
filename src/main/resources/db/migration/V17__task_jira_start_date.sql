-- SAGA V17: Persist Jira's Start Date on a Task. V1-V16 remain immutable. Additive/safe. utf8mb4.
--
-- Same column type as the existing task.due_date (V1): DATETIME(6) NULL. Jira's Start Date is a
-- date-only "datepicker" value (yyyy-MM-dd, no time-of-day) -- identical payload shape to Jira's
-- standard "duedate" field, which SAGA already models as DATETIME(6) stored at local midnight and
-- truncates back to a plain date at the API boundary. Reusing that exact type keeps Task's business
-- date columns internally consistent (one representation, one truncation rule) instead of
-- introducing a second date type (e.g. DATE) for what is semantically the same kind of value.
--
-- Deliberately NOT derived from or coupled to sprint.start_date: a Sprint's start date is the
-- iteration's own schedule, unrelated to an individual Jira issue's own Start Date custom field.
--
-- No index is added: no current query filters, joins, or orders by start_date -- GET /tasks
-- already returns every Task row for a project in one query (same reasoning V16 applied to
-- parent_external_id/parent_external_key).
--
-- No existing row is modified or deleted by this migration. Every existing Task row gets
-- start_date = NULL until the next Jira sync populates it from the provider's actual current
-- value (dynamically-resolved Start Date custom field, per-site -- see JiraIssueWriteClient).

ALTER TABLE task
    ADD COLUMN start_date DATETIME(6) NULL AFTER due_date;
