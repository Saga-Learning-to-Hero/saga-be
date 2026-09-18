-- Nullable parent_count classifies Git commits without encoding UNKNOWN as 1.
-- NULL = UNKNOWN (historical / webhook-only until full sync)
-- 0    = NON_MERGE root
-- 1    = NON_MERGE normal
-- >1   = MERGE
-- No default, no backfill, no parent_count-only index.

ALTER TABLE git_commit
    ADD COLUMN parent_count INT NULL;
