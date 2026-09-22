# AI-3 academic classification

Academic classification is advisory: an AI proposal is never academic truth and has no contribution, ProjectType, Neo4j, or workflow effect.

`POST /api/projects/{projectId}/ai/tasks/{taskId}/academic-analyses` uses the deterministic task snapshot hash as its revision. Historical rows remain visible, while authoritative current-task mappings are scoped to the current snapshot revision. Commit submissions use the persisted immutable Git SHA, never a branch head.

Each run uses the exact Course-pinned syllabus version and supplies all PHASE and EXPECTED_DELIVERABLE candidates. The model may return PROPOSED, UNCLASSIFIED, or INSUFFICIENT_EVIDENCE; the latter two are successful completed runs with no proposal rows. Candidate context that is partial requires INSUFFICIENT_EVIDENCE.

Validated proposals are immutable AI rows. An assigned lecturer may CONFIRM, REJECT, or CORRECT. CORRECT preserves the original AI proposal and appends a HUMAN mapping linked through `sourceClassificationId`; authoritative mappings include confirmed AI rows and HUMAN corrections, excluding proposed, rejected, and corrected originals.

Task and commit classification read endpoints return bounded classification summaries only; they do not fetch provider bodies or call OpenAI, GitHub, Jira, Neo4j, or Redis. Failover lineage remains isolated to immutable submission evidence. Python AI services, secondary/self-learning systems, alerts, reports, and graph writes are not part of AI-3.
