-- "Latest sync job of this Jira site / GitHub project" is read by the dashboards, the sync status
-- endpoint and the periodic reconcile. Without this index every such read scans the whole log.
CREATE INDEX ix_sync_job_target ON sync_job_log (target_system, target_id, started_at);
