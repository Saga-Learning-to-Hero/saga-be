# Integration + Identity + Audit + Attribution Foundation V1

Date: 2026-08-25  
Migration: `V4__integration_identity_audit_attribution_foundation.sql`  
V1/V2/V3: untouched (checksums locked).

## What existed

Auth V1 (session cookie `SAGA_SESSION`, CSRF, Google OIDC, Argon2id) was complete. V1 already had 52 tables including `identity_map`, `jira_integration`, `github_installation`, `git_repo`, `task`, `webhook_receipt`, `business_warning`, `email_outbox`, `outbox_event`. There was **no** integration implementation: `integration/` was package-info only. `identity_map` uniquely constrained `(user, provider)`, which blocked multiple GitHub/Jira accounts per user.

Team Leader is `team_member.role_in_team = LEADER`. Team↔Project is 1:1 (`uk_team_project`).

## Architecture summary

- **Personal identity** = global SAGA user ↔ GitHub numeric user id / Jira `accountId`.
- **Team resource** = Leader-selected GitHub installation repos and Jira Cloud site/project/board.
- MySQL + transactional outbox remain Source of Truth. Neo4j is not written in these transactions.
- GitHub/Jira events are **evidence**. SAGA does not auto-complete Jira tasks from commits and does not label anyone a cheater.

## V4 tables

**Altered:** `identity_map`, `identity_mapping_history`, `jira_integration`, `github_installation`, `git_repo`, `git_commit`, `pull_request`, `task`, `webhook_receipt`.

**Added (6):** `audit_log`, `task_work_session`, `contribution_confirmation`, `task_git_commit_link`, `task_pull_request_link`, `webauthn_credential`.

Final domain table count after V4 = **58** created tables in Flyway (52 from V1 + 6 from V4). Unofficial file `v4_delete_fk_subjectid_from_rubric_table.sql` is **not** a Flyway version and is not applied.

`identity_map` unique active owner: generated column `active_provider_subject` + `uk_identity_active_provider_subject (provider, active_provider_subject)`.

## Identity semantics

- Multiple ACTIVE identities per provider per user.
- First ACTIVE identity for a provider is primary.
- Unlink of primary promotes the oldest remaining ACTIVE identity.
- Relink of a REVOKED identity owned by the same user is `RELINKED`.
- If another SAGA user holds the ACTIVE subject: `EXTERNAL_IDENTITY_ALREADY_LINKED` (no owner leak) + HIGH warning.

## API endpoints

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | `/api/integrations/me` | Session | Personal identities |
| POST | `/api/integrations/github/link` | Session+CSRF | Start GitHub user OAuth + PKCE |
| GET | `/api/integrations/github/oauth/callback` | Session | State-bound callback |
| PATCH | `/api/integrations/github/{id}/primary` | Session+CSRF | |
| DELETE | `/api/integrations/github/{id}` | Session+CSRF | |
| POST | `/api/integrations/jira/link` | Session+CSRF | Start Jira 3LO (no offline_access) |
| GET | `/api/integrations/jira/oauth/callback` | Session | |
| PATCH/DELETE | `/api/integrations/jira/{id}` | Session+CSRF | |
| GET | `/api/projects/{id}/integrations` | Team member | GitHub installation + Jira connection summary |
| POST | `/api/projects/{id}/integrations/github/connect` | Leader | GitHub App install URL |
| GET | `/api/integrations/github/setup/callback` | Leader | Verify installation via GitHub App API (state holds projectId) |
| GET | `/api/projects/{id}/integrations/github/setup/callback` | Leader | Same flow, project-scoped alias |
| GET/PUT | `/api/projects/{id}/integrations/github/repositories` | Leader | List/select repos by numeric id (must belong to the project's verified installation) |
| DELETE | `/api/projects/{id}/integrations/github` | Leader | Revoke team GitHub connection |
| POST | `/api/projects/{id}/integrations/jira/connect` | Leader | Team 3LO + offline_access |
| GET | `/api/integrations/jira/team/callback` | Leader | Exchange code, require linked Jira identity, cache pending tokens |
| GET | `/api/projects/{id}/integrations/jira/sites\|projects\|boards` | Leader | List from pending team OAuth (IDs verified against Jira) |
| PUT | `/api/projects/{id}/integrations/jira` | Leader | Persist verified site/project/board |
| DELETE | `/api/projects/{id}/integrations/jira` | Leader | Revoke; encrypted refresh token cleared |
| POST | `/api/webhooks/github` | HMAC | Public, CSRF ignored |
| POST | `/api/webhooks/jira` | Provider signature | Public, CSRF ignored |
| POST | `/api/tasks/{id}/work-sessions/start\|stop` | Team member | SAGA-side evidence |
| POST | `/api/tasks/{id}/contribution-confirmations` | Team member + recent step-up | Append-only |
| POST | `/api/auth/reauth/password` | Session+CSRF | Sets session `saga.stepUpAuthenticatedAt` |

No audit update/delete API.

## Authorization matrix

| Action | Student member | Team Leader | Lecturer | Admin |
| --- | --- | --- | --- | --- |
| Link personal GitHub/Jira | Yes | Yes | Yes | Yes |
| Connect team GitHub/Jira | No (`NOT_TEAM_LEADER`) | Yes | No | Yes (recovery) |
| Start task work session | If team member | Yes | No | Yes |
| Confirm contribution | Member + fresh step-up | Same | No | Yes |

## GitHub App flow

1. User `POST /api/integrations/github/link` → authorize with PKCE S256 + `prompt=select_account`.
2. Callback exchanges code, reads `/user`, links numeric `id`, **discards user token**.
3. Leader `POST .../github/connect` → GitHub App install URL with server-side state in Valkey (`saga:oauth:state:{state}`, TTL 10m, single-use).
4. Setup callback (`/api/integrations/github/setup/callback`) verifies installation with GitHub App JWT, optionally checks `/user/installations`, and **binds `github_installation.project_id`**. Do not trust `installation_id` from the query string alone.
5. Leader lists/selects repos that belong to **that project's** verified installation. Repo identity = GitHub repository numeric id. Roles: FRONTEND / BACKEND / OTHER (multiple allowed).
6. Installation access tokens are minted from App JWT and are **not** domain columns. Cache TTL ~50 minutes when Redis is present.

Webhook: verify `X-Hub-Signature-256` **before** parsing. Idempotent on `X-GitHub-Delivery` via `webhook_receipt`. Duplicate delivery returns 202 without reprocessing.

**Initial sync boundary:** `COALESCE(semester.start_date, project.created_at, now() - 90 days)`. Sync is paginated and keyed by native ids / SHA.

Minimum GitHub App repo permissions (read): Metadata, Contents, Issues, Pull requests. Events: `push`, `issues`, `pull_request`, `pull_request_review`, `issue_comment`, `installation`, `installation_repositories`.

## Jira OAuth flow

Personal: authorize without `offline_access`, resolve `accountId` via accessible resource + `/myself`, discard tokens.

Team: Leader 3LO with `offline_access` to `/api/integrations/jira/team/callback`. Connecting Atlassian account **must already be a linked Jira identity** of the current SAGA user. Access/refresh tokens are cached briefly (encrypted in Valkey when Redis is present) so the Leader can list sites/projects/boards. Site/project/board IDs are fetched from Jira, not trusted from the client alone. PUT persists the selection and consumes the pending cache.

**Token encryption:** AES-256-GCM envelope `v1:<base64(nonce||ciphertext+tag)>`. AAD binds `integrationId|provider|connectedUserId`. Master key `SAGA_INTEGRATION_TOKEN_ENCRYPTION_KEY` (32-byte Base64). Refresh rotation is per-integration synchronized.

**Webhook refresh:** scheduler daily 03:00, refresh when expiry < `SAGA_JIRA_WEBHOOK_REFRESH_BEFORE_EXPIRY` (default 7 days, i.e. well before day 30). Repeated failure → warning/log.

Scopes (centralized in `IntegrationProperties.Jira`): `read:me`, `read:jira-user`, `read:jira-work`, `read:project:jira`, `read:issue-details:jira`, `read:jql:jira`, `read:board-scope:jira-software`, `read:sprint:jira-software`, `manage:jira-webhook`, plus `offline_access` for team connect only.

## Traceability

Jira keys (`ABC-123`) extracted from commit message, branch, PR title, PR body. Direct tables `task_git_commit_link` / `task_pull_request_link`. GitHub Issue is **not** required. Duplicate pairs are idempotent.

## Task completion derivation

Jira status remains authoritative. Derived `saga_completion_state`:

| Jira | Due date | Result |
| --- | --- | --- |
| terminal, completed ≤ due | set | `COMPLETED_ON_TIME` |
| terminal, completed > due | set | `COMPLETED_LATE` |
| non-terminal, now > due | set | `OVERDUE` |
| terminal | none | `COMPLETED_NO_DUE_DATE` |
| non-terminal | not due | `NOT_STARTED` / `IN_PROGRESS` |

A commit never writes Jira workflow status.

## Work session / confirmation / step-up

Work sessions are SAGA-authenticated presence on a task (pair programming allowed even if not Jira assignee). Confirmation is task-level, append-only, hashed evidence snapshot, requires recent password step-up (10 minutes) or WebAuthn when enabled. Passkeys: `SAGA_WEBAUTHN_ENABLED=false` by default; RP id/origins required when enabled. SAGA session ids are not stored on work sessions.

## Contribution confidence

Output shape: `{ contributionScore unchanged, attributionConfidence: HIGH|MEDIUM|LOW|FLAGGED, riskSignals: [...] }`. Missing commit signature is **neutral**. Assignee ≠ GitHub actor is a **signal**. Identity change near deadline (`SAGA_ATTRIBUTION_IDENTITY_CHANGE_DEADLINE_WINDOW_HOURS`, default 24) → FLAGGED for review.

## Account-sharing threat model

Student A can give real GitHub/Jira credentials to Student B. Provider events still look like A. SAGA cannot prove physical authorship. Mitigation stack: provider identity + SAGA activity + work session + step-up + optional passkey + confirmation + audit + warnings + lecturer review.

## Warning / email

Reuse `business_warning`, `user_notification`, `email_outbox`. Lecturer is `course.instructor` → `lecturer_profile.user_account`. Language: “Potential contribution attribution issue”. Never “Student cheated”. HIGH/CRITICAL email; MEDIUM in-app only.

## Required environment

See `.env.example`. Do not commit real secrets. App starts without GitHub/Jira secrets while those features remain disabled.

### GitHub App console

- Callback URL: `SAGA_GITHUB_OAUTH_CALLBACK_URL`
- Setup URL: `SAGA_GITHUB_SETUP_CALLBACK_URL`
- Webhook URL: `https://<public>/api/webhooks/github`
- Webhook secret → `SAGA_GITHUB_WEBHOOK_SECRET`
- Private key → Base64 PEM → `SAGA_GITHUB_PRIVATE_KEY_BASE64`

### Atlassian Developer console

- Callback: `SAGA_JIRA_OAUTH_CALLBACK_URL`
- Permissions: scopes listed above
- Webhook endpoint: `SAGA_JIRA_WEBHOOK_URL`

### Railway

Set `SAGA_PUBLIC_BASE_URL` to the public HTTPS origin. Do not construct callbacks from internal `http://localhost:8080`.

## Tests

`mvn test`: **131** tests, 0 failures, 0 skipped.

Existing Auth/schema tests remain. New unit tests cover identity uniqueness, OAuth state (invalid/expired/replay/wrong user/wrong flow/PKCE), pending Jira team connect single-use + redacted toString, HMAC webhooks + duplicate delivery, team Leader vs member, AES-GCM + concurrent refresh, traceability, completion states, audit redaction + snapshots, work-session/step-up/confirmation, confidence signals, webhook refresh scheduler, WebAuthn disabled/enabled validation, V1–V3 checksum lock, V4 schema, no audit delete API, installation token not persisted.

## Unresolved limitations

- RabbitMQ outbox **relay** is still not implemented (events are written to `outbox_event` when publisher is used; webhook ingest currently acknowledges after receipt persist).
- Full paginated GitHub/Jira history sync workers are scaffolded via `sync_job_log` INITIAL jobs; provider pagination loops should be completed when live credentials exist.
- Spring Security WebAuthn filter chain is not wired until `SAGA_WEBAUTHN_ENABLED=true` and RP is configured.
- GitHub App PKCS#1 key wrap is best-effort; prefer PKCS#8 PEM.
- Hibernate `ddl-auto=validate` against a populated DEV DB requires applying V4 with Flyway before restart.

## Files added/changed (high level)

- Flyway V4
- Entities/enums/repositories for identity, audit, traceability, evidence, WebAuthn
- `integration/github`, `integration/jira`, `integration/oauth`, `service/identity`, `service/audit`, `service/attribution`, `security/webauthn`
- Controllers for personal/project integrations, webhooks, task evidence, reauth
- Docs: this report, DEC-026–032, `.env.example`
