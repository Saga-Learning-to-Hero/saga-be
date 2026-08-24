# SAGA V2 schema decisions

Greenfield MySQL schema. Old `be-clean` database is **reference only**. This file records old → new mapping, exclusions, redesigns, new concepts, and items that still need review before business-layer work.

Architecture decisions DEC-001–DEC-015 remain. Additional locks: **DEC-016** (internal UUID) and **DEC-017** (Argon2id password hash).

---

## 1. Old → new mapping

| Old | New | Notes |
| --- | --- | --- |
| `admin` / `student` / `lecturer` | `user_account` + `student_profile` / `lecturer_profile` | Common identity on `user_account`. Role-specific fields on profiles. |
| `class` | `academic_class` | Java entity `AcademicClass`. Never `Class`. |
| `jira_board` | `jira_integration` | Connection + board/project/sync metadata. No Cognito coupling. |
| `commit_data` | `git_commit` | Repo + SHA unique. No competing task/issue/PR FKs. |
| `policy_override_request` | `contribution_override` | Immediate override record. No fake REQUEST → APPROVED workflow. |
| `warning_email_outbox` | `email_outbox` | Generic transactional mail, not warning-only. |
| Course membership via `team_member` only | `course_enrollment` authority + `team_member` | Student may enroll before team assignment. |
| Dual traceability (direct FKs + link tables) | Link tables only | `task_git_issue_link`, `git_issue_commit_link`, `git_issue_pull_request_link`. |
| `identity_map` tied to mixed identities / Cognito | `identity_map.user_account_id` + `provider` | JIRA and GITHUB mappings on the same user. |
| Polymorphic notification recipients | `user_notification.recipient_user_id` | Always `user_account`. |
| Runtime-only contribution | `assessment_run` + `assessment_result` | Historical snapshots. |
| (none) | `outbox_event` | MySQL → RabbitMQ → projection. No MySQL+Neo4j dual-write. |

Useful old semantics preserved (cleaned):

- Academic offering: Subject + AcademicClass + Semester → Course + lecturer instructor
- `active_semester_setting` singleton
- Course contribution weights + `contribution_config_mode` (COURSE vs PROJECT_GROUP)
- `project_group_weight_config`
- Project type catalog (4 canonical codes)
- Team 1:1 Project (`uk_team_project`)
- Jira sprint/task/attachment/write-operation
- GitHub installation/repo/issue/PR/commit/review/comment
- Commit review intent/result
- Peer review + rubric detail
- Webhook receipt + sync job log
- Business warning (not `risk_alert`)
- AI delegation context + conversation scope (no `ai_interaction_log`)
- Graph processing run metrics (not graph nodes/edges)
- Notification broadcast / delivery / Firebase installation rows

---

## 2. Excluded old concepts

These tables **must not exist** in V2. They were not created. This is not DROP TABLE against the old database.

| Old concept | Why excluded |
| --- | --- |
| `assessment`, `assessment_evidence` | Replaced by `assessment_run` / `assessment_result`. Old assessment persistence was not the historical model we need. |
| `cam_config` | Legacy contribution architecture. |
| `peer_review_config` | Not the active config authority; course/project-group weights remain. |
| `meeting_log`, `meeting_attendee` | Dead / out of V2 scope. |
| `commit_file`, `file_module` | File-level graph detail belongs to rebuildable Neo4j if needed, not MySQL SoT. |
| `ai_interaction_log` | Verbose log, not required for current AI tables. |
| `risk_alert` | Superseded by `business_warning`. |
| `document` | Not an active SoT entity for V2. |
| `task_weight_config` | Dead / replaced by course and project-group weights. |
| `task_web_link` | Explicitly out of target. Related columns not ported onto `task`. |
| Mongo `system_audit_log` | Mongo is not baseline (DEC-013). |
| `student_uuid_binary_*` | Technical backup tables. |
| Cognito `cognito_sub` and similar | Auth V2 is provider-neutral (DEC-012). |

Also not recreated as first-class tables: `ai_conversation`, `ai_message`. Add later only if product persistence is required.

---

## 3. Redesign decisions

### Identity

- One `user_account` per person.
- Student/lecturer are profiles, not competing user tables.
- Admins are `user_account.account_role = ADMIN` without a separate admin table.
- `user_account.password_hash` (`VARCHAR(255) NULL`) stores Spring Security `PasswordEncoder` output only. Algorithm is **Argon2id** (DEC-017): memory 19456 KiB, iterations 2, parallelism 1. `PasswordEncoder` bean is configured; registration/login/session are **not** implemented. OIDC-only accounts may leave the column NULL. No plaintext, salt column, reversible encryption, or Cognito fields.
- Session / access-token / refresh-token tables are **not** created. That architecture is still not finalized.
- No Cognito fields.

### Academic model

Kept as four concepts. Course is the offering. `active_semester_setting` remains a singleton with `updated_at` / `updated_by_user_id`.

Soft-delete is used on academic catalogs (`subject`, `academic_class`, `semester`, `course`) plus synced Jira/GitHub-ish rows that need recoverability (`sprint`, `task`, `rubric_template`). It is **not** applied globally.

### Course enrollment authority

`course_enrollment` is the Student ↔ Course membership SoT.

`team_member` requires:

- a `course_enrollment`
- `course_id` matching both `team.course_id` and `course_enrollment.course_id` via composite FKs

A student can belong to a course with zero team memberships.

### Traceability

Removed competing FKs:

- `pull_request.task_id`, `pull_request.git_issue_id`
- `git_commit.task_id`, `git_commit.git_issue_id`, `git_commit.pr_id`

Canonical path: Task → Git issue → Commit and/or PR.

`comment.task_id` is a nullable comment target FK (restored in V2). It does **not** reintroduce dual traceability on `pull_request` or `git_commit`.

### Jira vs GitHub

Both connect to SAGA through `project`. Neither integration table FKs the other. Identity linking is `identity_map` on `user_account`.

Encrypted Jira tokens are stored as opaque TEXT. No Cognito fields.

### Contribution override

Modeled as an applied override: old/new value, reason, actor, course, optional team/student. No status machine.

### Notifications

Recipient is `user_account`. Delivery goes to `firebase_installation` (many devices per user). Retry/status columns preserved.

`firebase_installation` is in the 52-table target even though FCM enablement remains optional at runtime.

### Email

`email_outbox` is generic (`email_type` / `template_key` / JSON payload). Invitation, warning, assessment, and integration mail share one table.

### Outbox

`outbox_event` is infrastructure for reliable async propagation. Consumers (RabbitMQ, Neo4j projection) are **not** implemented in this pass.

### UUID

Consistent `CHAR(36)` + Hibernate `preferred_uuid_jdbc_type=CHAR`, matching the old backend UUID style without mixing BINARY(16).

### JPA mapping rules applied

- `BaseEntity` for normal tables
- `EnumType.STRING`
- `FetchType.LAZY`
- no `CascadeType.ALL`
- no bidirectional collections
- Flyway owns DDL; `ddl-auto=validate` on `local` and `dev`

---

## 4. New concepts (not in old SoT)

| Table | Purpose |
| --- | --- |
| `course_enrollment` | Student ↔ Course membership authority |
| `assessment_run` | Identifies a historical calculation (course / optional project / sprint, version, status) |
| `assessment_result` | Per-student snapshot for that run |
| `outbox_event` | Transactional outbox |
| `user_account` | Unified account (replaces three user tables) |

`contribution_override` and `email_outbox` / `jira_integration` / `git_commit` are redesigns of existing old tables rather than net-new business ideas.

---

## 5. Important FK decisions

| Relationship | Delete behavior | Why |
| --- | --- | --- |
| Most business FKs | RESTRICT | Do not cascade-delete courses, users, or projects accidentally |
| `task_attachment` → task | CASCADE | Attachment is owned evidence of the task |
| GitHub children → `git_repo` | CASCADE | Issue/PR/commit are owned sync rows of the repo |
| `pr_review` → PR | CASCADE | Review is owned by the PR |
| `comment` → issue/PR/task | CASCADE | Comment is owned evidence of the target |
| Traceability links | CASCADE | Links die with either endpoint |
| `peer_review_detail` → peer_review | CASCADE | Detail rows are owned by the review |
| `notification_delivery` → user_notification | CASCADE | Delivery rows are owned by the inbox item |
| `team_member` composite FKs | RESTRICT | Invalid cross-course membership must fail closed |
| `graph_processing_run` course/team/student | **no FK** | Metrics row must not block academic deletes; ids are informational |
| `webhook_receipt.target_id`, `sync_job_log.target_id` | **no FK** | Polymorphic integration targets |
| `outbox_event.aggregate_id` | **no FK** | Aggregate may be any type |

---

## 6. Important unique / index decisions

Idempotent external identities:

- Jira task: `(project_id, external_id)`
- Sprint: `(jira_integration_id, external_sprint_id)`
- GitHub repo: `(provider, repository_id)`, `(project_id, full_name)`
- Issue: `(repo_id, github_issue_id)`, `(repo_id, issue_number)`
- PR: `(repo_id, github_pull_request_id)`, `(repo_id, pull_number)`
- Commit: `(repo_id, sha_hash)`
- Review: `(pull_request_id, github_review_id)`
- Installation: `installation_id`
- Webhook: `(provider, delivery_id)`
- Identity: `(user_account_id, provider)`, `(provider, external_account_id)`

Query indexes: course semester/instructor, enrollment course, team course, task project/sprint/assignee/due date, commit SHA, notification inbox, delivery status, email outbox status, webhook processing, outbox `(status, available_at)`.

MySQL unique indexes allow multiple NULLs. External id uniqueness therefore applies once the provider id is known.

---

## 7. Conflicts with earlier HANDOFF language

These were **PROPOSED / NOT LOCKED** or **OPTIONAL** in `SAGA_HANDOFF.md`. This foundation implements them because the V2 database task required all 52 tables:

1. **`course_enrollment`** is now in the schema as membership authority.
2. **`assessment_run` / `assessment_result`** are now in the schema for historical continuous assessment.
3. **`firebase_installation`** is in the schema; enabling FCM at runtime is still optional.

They are schema-foundation facts, not new architecture DEC-xxx locks for Auth, FCM operations, or the assessment engine.

ERD review follow-up (V2 migration, V1 immutable):

- Team ↔ Project remains **1:1**; `uk_team_project` stays.
- `comment.task_id` restored as nullable FK → `task.id`.
- Table count remains **52**. No session/refresh-token table was added.

---

## 8. Internal ID policy (UUID)

SAGA-owned primary keys are UUID `CHAR(36)` (**DEC-016**):

- All 51 non-singleton tables, including join/link tables and `outbox_event`.
- JPA: `BaseEntity.id` (`GenerationType.UUID`, random UUID / UUID v4) or equivalent on `GraphProcessingRun`.
- Internal FKs use the same `CHAR(36)` type as the referenced PK.

**Only intentional exception:** `active_semester_setting.singleton_id` TINYINT CHECK `= 1`.

External identifiers stay native and are **never** used as PKs: GitHub `repository_id` / issue / PR / review ids, commit SHA, Jira `external_id` / `external_key`.

Audit of 52 tables: **no UUID PK violations**. No ALTER was required for IDs. Flyway V2 (already applied) added `password_hash` and `comment.task_id` only. V1 is immutable; V2 was not renamed because it is already on DEV.

---

## 9. Unresolved items (review before business logic)

1. **Auth session/token architecture** is still TBD. `password_hash` exists; Argon2id `PasswordEncoder` is configured; no refresh_token/session tables; login/registration **NOT IMPLEMENTED**.
2. **`business_warning` has no lifecycle status** (open/ack/resolved). Old runtime used event-key uniqueness. Add status later if UI needs it.
3. **`contribution_override.override_type`** is VARCHAR, not an enum, until calculation code freezes the type set.
4. **`git_repo.provider`** is `GitProvider.GITHUB` in JPA. Column remains VARCHAR for possible future providers.
5. **Nullable unique GitHub ids**: rows synced before ids are known can duplicate NULLs. Sync code must persist provider ids before relying on uniqueness.
6. **Flyway V1** is the empty-database baseline and is immutable. V2 is the applied foundation delta (`password_hash`, `comment.task_id`). Do not run V1 against the old `be-clean` schema.
7. **`OLD_DATABASE_AUDIT.md`** is referenced by HANDOFF but was not present in this repository at implementation time.
8. **Assessment score fields** are intentionally small (`contribution_score`, `peer_review_score`, `final_score`, `breakdown_json`). Exact formulas stay with the future assessment engine.
9. **Encrypted token storage** for Jira is opaque TEXT. Key-management is an integration task.
10. **No CHECK** that `comment` has exactly one of issue/PR/task. Enforce in service when comments are implemented.

Locked and removed from unresolved: Team ↔ Project **1:1** (`uk_team_project`); `comment.task_id`; internal UUID policy (DEC-016); Argon2id password storage (DEC-017).

If old active runtime later proves a required cardinality or field that this schema omitted, do not silently “fix” it in business code only — add an explicit schema follow-up.
