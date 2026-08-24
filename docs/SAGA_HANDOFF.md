# SAGA PROJECT HANDOFF

## 0. Handoff metadata

- Last updated: 2026-08-24
- Current phase: Database V2 foundation complete (Flyway v2 applied; UUID + Argon2id locked)
- Current focus: Auth V2 / first business vertical slice (session/token still TBD)
- Backend repo: `saga-be` (`com.saga.be`)
- Frontend repo: TBD — không được xác nhận trong docs của repo này

Đây là checkpoint ngắn. Chi tiết nằm ở các tài liệu trong section 14.

## 1. Project goal

SAGA = **Student Activity Graph Based Continuous Assessment** cho **Project-Based Learning**.

Backend kết hợp dữ liệu học thuật/đồ án với activity từ **GitHub** và **Jira**, xây Graph Read Model để giải thích quan hệ và đường đi **contribution**/activity, rồi cập nhật **Continuous Assessment** theo hướng near-realtime.

Graph phục vụ query/visualization, không chỉ UI decoration.

Luồng mục tiêu (DECIDED, chưa implement): GitHub/Jira Webhook → validation/idempotency → RabbitMQ → business processing → MySQL (Source of Truth) → Outbox/Projection → Neo4j; realtime change → SSE → Next.js.

## 2. Current architecture

### Frontend

Xác nhận trong docs `saga-be`:

- Next.js là FE consumer (README / architecture diagram)
- Backend → Browser realtime = SSE (DEC-007)
- TanStack Query: cache thuộc FE; BE không phụ thuộc thư viện này
- Public API contract: TBD / chưa chốt endpoint

Chưa có tài liệu trong `saga-be` xác nhận: Next.js App Router, TypeScript, Cytoscape.js, Zustand, Web Worker, hay quy tắc “không lưu toàn bộ Cytoscape graph vào React/Zustand”. Coi các mục đó là **TBD / ngoài repo này**.

### Backend

- Java 21, Spring Boot 4.1.0, Spring MVC, Maven
- Modular Monolith
- Virtual Threads khi phù hợp (DEC-008)
- Không dùng WebFlux làm baseline

### Infrastructure

| Component | Provider | Role | Current status |
| --- | --- | --- | --- |
| MySQL | Aiven | Source of Truth | CONFIGURED / CONNECTED (local) |
| Neo4j | AuraDB | Graph Read Model / Projection | CONFIGURED / CONNECTED (local). Projection **NOT IMPLEMENTED** |
| RabbitMQ | CloudAMQP | Async messaging | CONFIGURED / CONNECTED (local). Topology **NOT IMPLEMENTED** |
| Redis/Valkey | Aiven | Ephemeral / cache / realtime support | CONFIGURED / CONNECTED (local). Cache strategy **NOT IMPLEMENTED** |
| Backend hosting | Railway | DEV runtime | CONFIGURED / ONLINE. Health `GET /actuator/health` = UP. Auto deploy from GitHub = enabled |

## 3. Locked architecture decisions

Tất cả **ĐÃ CHỐT KIẾN TRÚC** trong `SAGA_DECISION_LOG.md`:

| ID | Decision |
| --- | --- |
| DEC-001 | Modular Monolith first |
| DEC-002 | MySQL = Source of Truth |
| DEC-003 | Neo4j = rebuildable Graph Read Model / Projection |
| DEC-004 | Không direct dual-write MySQL + Neo4j; dùng MySQL + outbox/projection |
| DEC-005 | RabbitMQ = async broker |
| DEC-006 | GitHub/Jira inbound = Webhook |
| DEC-007 | Backend → browser = SSE |
| DEC-008 | Spring MVC + Java 21; không WebFlux baseline |
| DEC-009 | Redis không phải durable truth |
| DEC-010 | SSE event phải hỗ trợ version/gap recovery |
| DEC-011 | Technical-layer root package structure |
| DEC-012 | Không dùng AWS Cognito làm primary auth platform |
| DEC-013 | MongoDB không thuộc initial baseline |
| DEC-014 | Flyway quản lý MySQL schema |
| DEC-015 | Public FE contract ghi cùng thay đổi controller |
| DEC-016 | SAGA-owned internal IDs = UUID CHAR(36) |
| DEC-017 | Local password storage = Argon2id hash only |

Transactional Outbox là hướng consistency (DEC-004). Implementation = **NOT IMPLEMENTED**.

## 4. Repository structure

Root package thực tế:

```text
com.saga.be
├── auth
├── config
├── controller
├── dto
├── entity
├── exception
├── graph
├── integration
├── mapper
├── messaging
├── realtime
├── repository
├── scheduler
├── security
└── service
```

Technical-layer root structure intentionally retained.

Ngoài `package-info.java` + `SagaBeApplication.java`: JPA entities (nested domain packages dưới `entity/`), foundation repositories, `JpaAuditingConfig`. Chưa có business services/controllers.

Profiles: `application.properties` (chung), `application-local.properties`, `application-dev.properties`, `application-test.properties`.

## 5. Infrastructure checkpoint

### Local

- MySQL: CONFIGURED / CONNECTED — PASS
- Neo4j: CONFIGURED / CONNECTED — PASS
- Redis/Valkey: CONFIGURED / CONNECTED — PASS
- RabbitMQ: CONFIGURED / CONNECTED — PASS
- Actuator: `GET /actuator/health` expose `health,info`; local `show-details=always`
- mvn test: BUILD SUCCESS (profile `test`, không nối cloud)

Đây là connectivity, không phải business feature.

### Railway DEV

- GitHub `saga-be` repository: CONNECTED
- Environment variables: CONFIGURED
- Spring profile: `dev`
- Deployment: ONLINE
- Public domain: CONFIGURED (không ghi URL trong file này)
- `GET /actuator/health`: HTTP 200 / status UP
- Railway healthcheck path: `/actuator/health`
- Auto deploy from GitHub: enabled
- DEV Actuator: `show-details=never` (không lộ host/database metadata)

## 6. Database V2 current status

Old database audit: **COMPLETE** — xem `docs/OLD_DATABASE_AUDIT.md`.

Tóm tắt old system:

- 61 MySQL tables
- 49 ACTIVE
- 2 LEGACY CANDIDATE
- 10 UNUSED CANDIDATE
- 1 Mongo collection
- 26 tables có Flyway CREATE
- 35 INFERRED FROM ENTITY
- missing Flyway `V1` và `V14`
- old schema có drift

**OLD SCHEMA = REFERENCE ONLY.**
**DO NOT COPY ALL 61 TABLES INTO V2.**

MySQL schema V2: **IMPLEMENTED** (empty-database baseline, 52 tables). Flyway `V1__initial_schema.sql` (immutable) + `V2__user_account_password_hash_and_comment_task.sql`. `local`/`dev`: `ddl-auto=validate`, `flyway.enabled=true`.

Classification KEEP / REDESIGN / MERGE / DROP-FROM-V1 / ADD: documented in `docs/SAGA_V2_SCHEMA_DECISIONS.md`. ERD: `docs/SAGA_V2_ERD.md`.

Current DB phase: **SCHEMA FOUNDATION COMPLETE / AWAITING REVIEW**.

Business layer on this schema: **NOT IMPLEMENTED**.

## 7. Database V2 preliminary direction

Schema V2 đã được tạo theo target 52 tables (không thêm DEC-xxx mới; DEC-001–015 giữ nguyên). Chi tiết trong `SAGA_V2_SCHEMA_DECISIONS.md`.

### Account model — SCHEMA PRESENT / AUTH TBD

`user_account` + `student_profile` + `lecturer_profile`. `password_hash` nullable (Flyway V2). Argon2id `PasswordEncoder` **configured** (DEC-017). Session/token **NOT FINALIZED** — no session tables. Không có `cognito_sub`. Login/registration **NOT IMPLEMENTED**.

### Course membership — IN SCHEMA

`course_enrollment` là authority Student ↔ Course. `team_member` gắn enrollment + composite FK cùng `course_id`.

Trước đây HANDOFF ghi PROPOSED / NOT LOCKED; task foundation đã yêu cầu table này.

### Assessment — SCHEMA PRESENT / ENGINE NOT IMPLEMENTED

`assessment_run` + `assessment_result` để lưu historical snapshot. Công thức/engine **NOT IMPLEMENTED**.

### Notification / Email — IN SCHEMA / WORKERS NOT IMPLEMENTED

Tables: `user_notification`, `notification_broadcast`, `notification_delivery`, `firebase_installation`, `student_course_invitation`, `email_outbox`.

Firebase/FCM runtime vẫn **OPTIONAL**; table `firebase_installation` nằm trong 52-table target.

## 8. Authentication status

- **DECIDED:** Backend sở hữu authentication/account state. Không dùng AWS Cognito làm primary platform (DEC-012). Google OAuth/OIDC có thể là một đường login.
- **NOT FINALIZED:** session vs access/refresh token, cookie, CSRF, logout, revocation.
- **NOT IMPLEMENTED:** registration, login, Google OAuth flow, role resolution, session/token handling.
- Role rule đã ghi: identity mơ hồ phải fail closed; không tự promote Lecturer.
- Không copy Cognito implementation cũ thành design hiện tại.

## 9. Implemented vs not implemented

### CONFIGURED / IMPLEMENTED

- Spring Boot skeleton + technical-layer packages
- Docs kiến trúc / Decision Log / FE contract placeholder
- Local connectivity: MySQL, Neo4j, Redis/Valkey, RabbitMQ
- Profile `local`, `dev`, `test`
- Actuator `health,info`
- Native `.env` import (`optional:file:.env[.properties]`)
- `server.port=${PORT:8080}`
- Test context độc lập cloud
- Railway DEV deployment ONLINE + healthcheck UP
- Old database audit COMPLETE (reference; `docs/OLD_DATABASE_AUDIT.md` may live outside this repo)
- MySQL V2 Flyway V1 (52 tables) + JPA entities + foundation repositories
- `docs/SAGA_V2_ERD.md`, `docs/SAGA_V2_SCHEMA_DECISIONS.md`

### NOT IMPLEMENTED YET

- Auth V2
- GitHub / Jira webhook pipeline
- Transactional Outbox / webhook inbox / idempotency store
- Graph projection / Neo4j schema / Graph API
- Redis cache / rate-limit strategy
- RabbitMQ topology
- SSE business events
- Notification / email provider
- Assessment engine

## 10. Current open questions

1. Auth session/token strategy (no session/refresh_token tables; `password_hash` + Argon2id encoder exist; login **NOT IMPLEMENTED**)
2. Unresolved list trong `docs/SAGA_V2_SCHEMA_DECISIONS.md` section 9
3. Flyway V1 is immutable; V2 is the applied foundation delta. Không chạy V1 lên old `be-clean` schema

## 11. Immediate next step

**Review schema foundation, then Auth V2 / first vertical slice.** Chưa port business services.

```text
SAGA V2 Flyway V1 + V2 + JPA entities = COMPLETE
        ↓
Review ERD / schema decisions
        ↓
Auth V2 (session/token still TBD)
        ↓
First account/academic vertical slice
        ↓
Webhook + identity map + outbox consumers
```

**DO NOT START full Jira/GitHub/assessment engines until ERD review is accepted.**
**DO NOT COPY ALL 61 TABLES INTO V2.**
**DO NOT run V1 against the old database.**

## 12. Do not accidentally do

- Không copy toàn bộ 61 old tables vào V2. Old schema = reference only.
- Không bật `ddl-auto=create` / `create-drop` / `update`.
- Không direct dual-write MySQL + Neo4j.
- Không dùng Redis làm Source of Truth.
- Không tự dựng lại Cognito làm primary auth.
- Không thêm MongoDB baseline nếu chưa có Decision mới.
- Không tạo webhook / SSE / topology / cache / graph model trước phase tương ứng.
- Không coi CONFIGURED / CONNECTED là business feature đã xong.

## 13. External DEV infrastructure

Chỉ provider + vai trò. Không ghi host, URI, username, password.

- Aiven: MySQL DEV, Redis/Valkey DEV
- Neo4j Aura: Graph Read Model DEV
- CloudAMQP: RabbitMQ DEV
- Railway: saga-be DEV runtime ONLINE (profile `dev`, auto deploy from GitHub)

Credentials sống trong environment variables / secret store của provider. **Never put them in this file.**

## 14. Source-of-truth documents

| Document | Purpose |
| --- | --- |
| `README.md` | onboarding |
| `docs/SAGA_CURRENT_STATE.md` | implemented / current state |
| `docs/SAGA_DECISION_LOG.md` | locked architecture decisions |
| `docs/SAGA_BACKEND_ARCHITECTURE.md` | architecture |
| `docs/SAGA_BACKEND_REQUIREMENTS_DEPENDENCIES_CONSTRAINTS.md` | constraints |
| `docs/FRONTEND_API_INTEGRATION.md` | FE/BE contract |
| `docs/OLD_DATABASE_AUDIT.md` | old schema reverse engineering (reference) |
| `docs/SAGA_V2_ERD.md` | V2 52-table ERD |
| `docs/SAGA_V2_SCHEMA_DECISIONS.md` | old→new mapping and exclusions |
| `docs/SAGA_HANDOFF.md` | resume checkpoint |

## 15. How to resume in a new AI conversation

```text
Tiếp tục project SAGA từ checkpoint hiện tại.

Hãy đọc:
1. docs/SAGA_HANDOFF.md
2. docs/SAGA_CURRENT_STATE.md
3. docs/SAGA_DECISION_LOG.md

Nếu task liên quan database, đọc thêm:
4. docs/OLD_DATABASE_AUDIT.md
   (REFERENCE ONLY. Không copy toàn bộ 61 tables vào V2.)

Không thiết kế lại từ đầu.
Không coi planned là implemented.
Hãy bắt đầu từ section 'Immediate next step' trong SAGA_HANDOFF.md.
```

## 16. Handoff maintenance rule

Update file này khi:

- hoàn thành một phase lớn
- architecture decision thay đổi
- current next step thay đổi
- infrastructure status thay đổi

Không update cho mọi commit nhỏ.

- `SAGA_CURRENT_STATE.md` = chi tiết trạng thái
- `SAGA_DECISION_LOG.md` = decisions
- `SAGA_HANDOFF.md` = checkpoint để resume nhanh
