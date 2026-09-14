-- One-time audit for leftover OPEN task_work_session duplicates
-- (same task_id + user_id) created by pre-idempotent START.
--
-- SELECT only unless you uncomment the UPDATE after reviewing the result.
-- Do NOT run this as Flyway. Not V18. Do not delete rows. Do not merge durations.
--
-- Canonical OPEN (matches GET/START): earliest started_at, then id.
-- Non-canonical OPEN rows have no recorded stop. NOW() would invent overlapping
-- elapsed time. The only timestamp on those rows is started_at, so the optional
-- UPDATE sets ended_at = started_at (zero duration) and status = STOPPED.
-- A single OPEN on a different task is legitimate and is left alone.

-- Groups with more than one OPEN row.
SELECT task_id,
       user_id,
       COUNT(*) AS open_count,
       MIN(started_at) AS earliest_started_at,
       MAX(started_at) AS latest_started_at
FROM task_work_session
WHERE status = 'OPEN'
GROUP BY task_id, user_id
HAVING COUNT(*) > 1
ORDER BY open_count DESC;

-- Every OPEN row with the proposed action. KEEP_OPEN includes legitimate
-- single-OPEN sessions on other tasks.
SELECT s.id,
       s.task_id,
       s.user_id,
       s.project_id,
       s.started_at,
       s.ended_at,
       s.status,
       FIRST_VALUE(s.id) OVER (
           PARTITION BY s.task_id, s.user_id
           ORDER BY s.started_at ASC, s.id ASC
       ) AS canonical_open_id,
       CASE
           WHEN s.id = FIRST_VALUE(s.id) OVER (
                   PARTITION BY s.task_id, s.user_id
                   ORDER BY s.started_at ASC, s.id ASC
               )
               THEN 'KEEP_OPEN'
           ELSE 'PROPOSED_STOP_ENDED_AT_EQ_STARTED_AT'
       END AS proposed_action
FROM task_work_session s
WHERE s.status = 'OPEN'
ORDER BY s.task_id, s.user_id, s.started_at, s.id;

-- Optional cleanup. Review the SELECTs first. Then uncomment and run once.
-- UPDATE task_work_session s
-- INNER JOIN (
--     SELECT ranked.id
--     FROM (
--         SELECT id,
--                FIRST_VALUE(id) OVER (
--                    PARTITION BY task_id, user_id
--                    ORDER BY started_at ASC, id ASC
--                ) AS canonical_id
--         FROM task_work_session
--         WHERE status = 'OPEN'
--     ) ranked
--     WHERE ranked.id <> ranked.canonical_id
-- ) extra ON extra.id = s.id
-- SET s.status = 'STOPPED',
--     s.ended_at = s.started_at
-- WHERE s.status = 'OPEN';
