

SET SQL_SAFE_UPDATES = 0;

START TRANSACTION;

UPDATE task tk
JOIN project p ON p.id = tk.project_id
JOIN course c ON c.id = p.course_id
SET tk.parent_task_id = NULL, tk.blocks_task_id = NULL
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

UPDATE comment cm
JOIN task tk ON tk.id = cm.task_id
JOIN project p ON p.id = tk.project_id
JOIN course c ON c.id = p.course_id
SET cm.parent_comment_id = NULL
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

UPDATE ai_academic_classification a
JOIN project p ON p.id = a.project_id
JOIN course c ON c.id = p.course_id
SET a.source_classification_id = NULL
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE r FROM ai_academic_classification_review r
JOIN ai_academic_classification a ON a.id = r.classification_id
JOIN project p ON p.id = a.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE a FROM ai_academic_classification a
JOIN project p ON p.id = a.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE d FROM ai_analysis_provider_decision d
JOIN ai_analysis_run r ON r.id = d.analysis_run_id
LEFT JOIN project p ON p.id = r.project_id
LEFT JOIN course c ON c.id = COALESCE(r.course_id, p.course_id)
WHERE c.id IS NOT NULL AND (c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802');

DELETE e FROM ai_analysis_evidence e
JOIN ai_analysis_run r ON r.id = e.analysis_run_id
LEFT JOIN project p ON p.id = r.project_id
LEFT JOIN course c ON c.id = COALESCE(r.course_id, p.course_id)
WHERE c.id IS NOT NULL AND (c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802');

DELETE a FROM ai_analysis_adjudication a
JOIN ai_analysis_run r ON r.id = a.analysis_run_id
LEFT JOIN project p ON p.id = r.project_id
LEFT JOIN course c ON c.id = COALESCE(r.course_id, p.course_id)
WHERE c.id IS NOT NULL AND (c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802');

DELETE n FROM ai_progress_narrative n
JOIN ai_analysis_run r ON r.id = n.analysis_run_id
LEFT JOIN project p ON p.id = r.project_id
LEFT JOIN course c ON c.id = COALESCE(r.course_id, p.course_id)
WHERE c.id IS NOT NULL AND (c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802');

DELETE a FROM ai_risk_analysis a
JOIN project p ON p.id = a.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE a FROM ai_task_intelligence a
JOIN project p ON p.id = a.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE r FROM ai_analysis_run r
LEFT JOIN project p ON p.id = r.project_id
LEFT JOIN course c ON c.id = COALESCE(r.course_id, p.course_id)
WHERE c.id IS NOT NULL AND (c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802');

DELETE m FROM assistant_message m
JOIN assistant_conversation cv ON cv.id = m.conversation_id
JOIN project p ON p.id = cv.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE cv FROM assistant_conversation cv
JOIN project p ON p.id = cv.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE ar FROM assessment_result ar
JOIN assessment_run r ON r.id = ar.assessment_run_id
JOIN course c ON c.id = r.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE r FROM assessment_run r
JOIN course c ON c.id = r.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE b FROM jira_task_failover_remote_issue_binding b
JOIN jira_task_failover_item i ON i.id = b.item_id
JOIN jira_task_failover_run r ON r.id = i.run_id
JOIN project p ON p.id = r.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE i FROM jira_task_failover_item i
JOIN jira_task_failover_run r ON r.id = i.run_id
JOIN project p ON p.id = r.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE r FROM jira_task_failover_run r
JOIN project p ON p.id = r.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE w FROM jira_write_operation w
JOIN project p ON p.id = w.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE d FROM peer_review_detail d
JOIN peer_review r ON r.id = d.peer_review_id
JOIN sprint s ON s.id = r.sprint_id
JOIN jira_integration j ON j.id = s.jira_integration_id
JOIN project p ON p.id = j.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE r FROM peer_review r
JOIN sprint s ON s.id = r.sprint_id
JOIN jira_integration j ON j.id = s.jira_integration_id
JOIN project p ON p.id = j.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM task_change_log x
JOIN task tk ON tk.id = x.task_id
JOIN project p ON p.id = tk.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM task_file x
JOIN task tk ON tk.id = x.task_id
JOIN project p ON p.id = tk.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM task_web_link x
JOIN task tk ON tk.id = x.task_id
JOIN project p ON p.id = tk.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM task_attachment x
JOIN task tk ON tk.id = x.task_id
JOIN project p ON p.id = tk.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM task_work_session x
JOIN project p ON p.id = x.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM task_delay_case x
JOIN project p ON p.id = x.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM contribution_confirmation x
JOIN project p ON p.id = x.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM task_git_commit_link x
JOIN task tk ON tk.id = x.task_id
JOIN project p ON p.id = tk.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM task_git_issue_link x
JOIN task tk ON tk.id = x.task_id
JOIN project p ON p.id = tk.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM task_pull_request_link x
JOIN task tk ON tk.id = x.task_id
JOIN project p ON p.id = tk.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE cm FROM comment cm
JOIN task tk ON tk.id = cm.task_id
JOIN project p ON p.id = tk.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE rr FROM commit_review_result rr
JOIN project p ON p.id = rr.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE ri FROM commit_review_intent ri
JOIN git_repo g ON g.id = ri.git_repo_id
JOIN project p ON p.id = g.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM git_commit_branch x
JOIN git_commit gc ON gc.id = x.git_commit_id
JOIN git_repo g ON g.id = gc.repo_id
JOIN project p ON p.id = g.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM git_issue_commit_link x
JOIN git_commit gc ON gc.id = x.git_commit_id
JOIN git_repo g ON g.id = gc.repo_id
JOIN project p ON p.id = g.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM git_issue_pull_request_link x
JOIN git_issue i ON i.id = x.git_issue_id
JOIN git_repo g ON g.id = i.repo_id
JOIN project p ON p.id = g.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM pr_review x
JOIN pull_request pr ON pr.id = x.pull_request_id
JOIN git_repo g ON g.id = pr.repo_id
JOIN project p ON p.id = g.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE cm FROM comment cm
JOIN git_issue i ON i.id = cm.git_issue_id
JOIN git_repo g ON g.id = i.repo_id
JOIN project p ON p.id = g.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE cm FROM comment cm
JOIN pull_request pr ON pr.id = cm.pull_request_id
JOIN git_repo g ON g.id = pr.repo_id
JOIN project p ON p.id = g.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE gc FROM git_commit gc
JOIN git_repo g ON g.id = gc.repo_id
JOIN project p ON p.id = g.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE i FROM git_issue i
JOIN git_repo g ON g.id = i.repo_id
JOIN project p ON p.id = g.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE pr FROM pull_request pr
JOIN git_repo g ON g.id = pr.repo_id
JOIN project p ON p.id = g.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE g FROM git_repo g
JOIN project p ON p.id = g.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM github_project_installation x
JOIN project p ON p.id = x.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM github_installation x
JOIN project p ON p.id = x.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE l FROM sync_job_log l
JOIN project p ON p.id = l.target_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE tk FROM task tk
JOIN project p ON p.id = tk.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE s FROM sprint s
JOIN jira_integration j ON j.id = s.jira_integration_id
JOIN project p ON p.id = j.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE j FROM jira_integration j
JOIN project p ON p.id = j.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE w FROM project_group_weight_config w
JOIN project p ON p.id = w.project_id
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE w FROM business_warning w
LEFT JOIN course c ON c.id = w.course_id
LEFT JOIN project p ON p.id = w.project_id
LEFT JOIN course cp ON cp.id = p.course_id
LEFT JOIN team t ON t.id = w.team_id
LEFT JOIN course ct ON ct.id = t.course_id
WHERE (c.id IS NOT NULL AND (c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802'))
   OR (cp.id IS NOT NULL AND (cp.course_code IS NULL OR cp.course_code <> 'SWR302-FA26-SE1802'))
   OR (ct.id IS NOT NULL AND (ct.course_code IS NULL OR ct.course_code <> 'SWR302-FA26-SE1802'));

DELETE o FROM contribution_override o
JOIN course c ON c.id = o.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE g FROM graph_processing_run g
LEFT JOIN course c ON c.id = g.course_id
LEFT JOIN team t ON t.id = g.team_id
LEFT JOIN course ct ON ct.id = t.course_id
WHERE (c.id IS NOT NULL AND (c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802'))
   OR (ct.id IS NOT NULL AND (ct.course_code IS NULL OR ct.course_code <> 'SWR302-FA26-SE1802'));

DELETE tm FROM team_member tm
JOIN course c ON c.id = tm.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE t FROM team t
JOIN course c ON c.id = t.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE p FROM project p
JOIN course c ON c.id = p.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE e FROM course_enrollment e
JOIN course c ON c.id = e.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE i FROM student_course_invitation i
JOIN course c ON c.id = i.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM ai_course_fallback_binding x
JOIN course c ON c.id = x.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM ai_course_provider_credential x
JOIN course c ON c.id = x.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM ai_course_settings x
JOIN course c ON c.id = x.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM ai_agent_conversation_scope x
JOIN course c ON c.id = x.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE x FROM ai_agent_delegation_context x
JOIN course c ON c.id = x.course_id
WHERE c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802';

DELETE a FROM audit_log a
LEFT JOIN course c ON c.id = a.context_course_id
LEFT JOIN project p ON p.id = a.context_project_id
LEFT JOIN course cp ON cp.id = p.course_id
WHERE (c.id IS NOT NULL AND (c.course_code IS NULL OR c.course_code <> 'SWR302-FA26-SE1802'))
   OR (cp.id IS NOT NULL AND (cp.course_code IS NULL OR cp.course_code <> 'SWR302-FA26-SE1802'));

DELETE FROM course
WHERE course_code IS NULL OR course_code <> 'SWR302-FA26-SE1802';

DELETE ac FROM academic_class ac
LEFT JOIN course c ON c.academic_class_id = ac.id
WHERE c.id IS NULL;

COMMIT;

SELECT id, course_code, name
FROM course
ORDER BY course_code;
