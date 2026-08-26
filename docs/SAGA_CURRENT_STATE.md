# SAGA Backend — Trạng thái hiện tại

**Cập nhật lần cuối:** 2026-08-25

File này mô tả những gì **thực sự đang có / đã implement** tại thời điểm hiện tại.

Không lấy kế hoạch kiến trúc tương lai làm bằng chứng rằng feature đã tồn tại.

---

## 1. Giai đoạn hiện tại

**Phase: Auth V1.1 + Integration/Identity/Audit/Attribution Foundation V1**

Architecture skeleton đã có. Flyway V1 + V2 + V3 (immutable) + V4 integration/identity/audit/attribution foundation (58 tables). Auth V1 complete. Integration V1 foundation (identity link, team GitHub App / Jira 3LO, webhooks, audit, attribution evidence) is implemented. Course/Task/Assessment scoring **chưa** shipped.

---

## 2. Technical baseline hiện tại

| Hạng mục | Baseline hiện tại |
| --- | --- |
| Project | Maven |
| Language | Java 21 |
| Spring Boot | 4.1.0 |
| Root package | `com.saga.be` |
| Packaging | JAR |
| Configuration format | `.properties` |
| Web | Spring Web / Spring MVC |
| Security dependency | Spring Security |
| External login dependency | OAuth2 Client |
| Relational persistence | Spring Data JPA + MySQL Driver |
| Validation | Bean Validation |
| Graph persistence | Spring Data Neo4j |
| Messaging | Spring for RabbitMQ |
| Redis | Spring Data Redis + Spring Session Data Redis |
| Observability | Spring Boot Actuator |
| Migration | Flyway |
| Boilerplate reduction | Lombok |

---

## 3. Repository skeleton đã hoàn thành

Root Java package hiện tại:

```text
src/main/java/com/saga/be/
├── SagaBeApplication.java
├── auth/
├── config/
├── controller/
├── dto/
├── entity/
├── exception/
├── graph/
├── integration/
├── mapper/
├── messaging/
├── realtime/
├── repository/
├── scheduler/
├── security/
└── service/
```

Trong các architecture package hiện chỉ có placeholder `package-info.java`.

Chưa tạo nested feature package.

Placeholder vận hành nằm tại:

```text
infra/
├── docker/
├── neo4j/
├── rabbitmq/
└── redis/

scripts/
├── local/
├── migration/
└── seed/
```

---

## 4. Tài liệu hiện có

Repository chứa:

```text
docs/
├── FRONTEND_API_INTEGRATION.md
├── SAGA_BACKEND_ARCHITECTURE.md
├── SAGA_BACKEND_REQUIREMENTS_DEPENDENCIES_CONSTRAINTS.md
├── SAGA_CURRENT_STATE.md
├── SAGA_DECISION_LOG.md
├── SAGA_HANDOFF.md
├── SAGA_V2_ERD.md
└── SAGA_V2_SCHEMA_DECISIONS.md
```

`README.md` là điểm vào cho người dùng repository.

---

## 5. Infrastructure connectivity

### Local

Profile `local` đã cấu hình kết nối tới các service DEV qua environment variables (file `.env`).

**Local infrastructure connectivity = PASS**

| Service | Vai trò | Trạng thái |
| --- | --- | --- |
| MySQL | Source of Truth | CONFIGURED / CONNECTED |
| Neo4j | Graph Read Model / Projection | CONFIGURED / CONNECTED |
| Redis / Valkey | Cache / Rate Limit / Ephemeral Realtime Support | CONFIGURED / CONNECTED |
| RabbitMQ | Async Message Broker | CONFIGURED / CONNECTED |

Đây là **CONFIGURED / CONNECTED**, không phải business feature đã implement.

### Railway DEV

Profile `dev` (`application-dev.properties`) đã được chuẩn bị cho Railway.

- **Railway DEV profile = CONFIGURED**
- **Railway deployment = ONLINE** (health UP). Chi tiết trong `SAGA_HANDOFF.md`.

Railway không dùng file `.env`. Credential được inject bằng Environment Variables. Railway tự inject `PORT`; ứng dụng bind `server.port=${PORT:8080}`. Healthcheck: `GET /actuator/health`.

MySQL schema V1+V2+V3: V1/V2 immutable; V3 adds `user_account.username` + `google_subject` (no new tables; 52-table count unchanged). Redis/Valkey is used for Spring Session (ephemeral). Neo4j/RabbitMQ topology vẫn chưa implement.

---

## 6. Feature đã triển khai

**Schema foundation:**

- Flyway `V1__initial_schema.sql` — 52 tables (immutable)
- Flyway `V2__user_account_password_hash_and_comment_task.sql` (immutable)
- Flyway `V4__integration_identity_audit_attribution_foundation.sql` — identity multi-account, audit_log, direct task↔commit/PR links, work sessions, confirmation, WebAuthn table
- Flyway `V4__integration_identity_audit_attribution_foundation.sql` — identity multi-account, audit_log, direct task↔commit/PR links, work sessions, confirmation, WebAuthn table
- JPA entities + `BaseEntity` + enums
- Foundation repositories
- `local`/`dev`: Flyway enabled, `ddl-auto=validate`

**Auth V1 + V1.1 (IMPLEMENTED / unit+slice TESTED):**

- Local login `POST /api/auth/login` (email or username)
- Google OIDC authorization-code (`/oauth2/authorization/google`) when `GOOGLE_CLIENT_ID` is configured
- Role resolution for **new** Google accounts only (DEC-020); existing DB role wins (DEC-019)
- Student and Lecturer first-Google-login password setup (DEC-021, DEC-025), backend-enforced
- Public Student registration `POST /api/auth/register` (DEC-024): personal email only, always STUDENT, no course/team membership
- Bootstrap Admin from environment (DEC-023)
- Server-side Spring Security session + HttpOnly `SAGA_SESSION` cookie + Spring Session Redis/Valkey (DEC-018)
- CSRF cookie `XSRF-TOKEN` / header `X-XSRF-TOKEN`; CORS credentials with configured origins
- `GET /api/auth/me`, `POST /api/auth/logout`, `GET /api/auth/csrf`, `POST /api/auth/password/setup`
- Developer landing `GET /` + Swagger UI `/swagger-ui.html` + OpenAPI `/v3/api-docs` (springdoc 3.1.0). Docs paths are permitAll; business APIs are not. CSRF remains enabled (Swagger copies `XSRF-TOKEN` → `X-XSRF-TOKEN`).

Cụ thể, các phần sau **CHƯA TRIỂN KHAI**:

- forgot password / password reset email;
- email ownership verification for personal registration (future enhancement);
- MFA as a full login replacement (WebAuthn is feature-flagged step-up only);
- JWT / refresh tokens;
- RabbitMQ topology / outbox relay to graph;
- Neo4j schema / graph projection;
- assessment algorithm / contributionScore weights;
- SSE endpoint;
- live paginated GitHub/Jira history sync against production credentials.

---

## 7. Decision kiến trúc đã chốt

Xem `SAGA_DECISION_LOG.md`.

Các Decision quan trọng hiện tại:

- Modular Monolith trước.
- MySQL là Source of Truth.
- Neo4j là Graph Read Model có thể rebuild.
- Không dual-write trực tiếp MySQL + Neo4j.
- RabbitMQ cho xử lý event bất đồng bộ.
- Webhook GitHub/Jira cho inbound event.
- SSE cho Backend → Browser realtime.
- Baseline Spring MVC + Java 21.
- Redis là ephemeral, không phải durable truth (session store OK).
- Auth V1 = Spring Security session + Valkey/Redis; không JWT (DEC-018).
- Google OIDC `sub` + DB role wins; ADMIN never from Google (DEC-019–023).
- K19+ personal-email Students may self-register; public registration always STUDENT; no Course/Team membership (DEC-024).
- Google STUDENT and LECTURER must set a local SAGA password (DEC-025).
- MongoDB không thuộc initial baseline.
- Backend mới không dùng Cognito làm primary authentication platform.

---

## 8. Decision còn mở / chưa chốt

### Chiến lược authentication session/token

**ĐÃ CHỐT cho Auth V1:** server-side Spring Security session + HttpOnly cookie + Spring Session Redis (DEC-018). Không JWT access/refresh trong Auth V1.

Còn lại (không thuộc Auth V1.1):

- forgot/reset password;
- email ownership verification for personal registration;
- MFA;
- session/device inventory UI.

### Định danh FPT và role resolution

**ĐÃ CHỐT** DEC-020 / DEC-022. Identity unknown → fail closed; không default Lecturer. Personal Gmail không self-provision qua Google SAGA Login.

### Database schema

SAGA V2 MySQL schema + Auth V1 identity columns = **IMPLEMENTED**.

- Flyway `V1` + `V2` immutable
- Flyway `V3__auth_v1_account_identity.sql`: `username`, `google_subject`
- Argon2id `PasswordEncoder` (DEC-017) used by local login / password setup / admin bootstrap
- Chi tiết: `docs/SAGA_V2_ERD.md`, `docs/SAGA_V2_SCHEMA_DECISIONS.md`

Business Course/Task/Assessment services = **NOT IMPLEMENTED**.

### Graph schema

Node label, relationship type, uniqueness constraint, projection version, giới hạn traversal, và chiến lược rebuild **chưa chốt**.

### Assessment model

Template assessment theo subject/course, trọng số, và công thức đóng góp **chưa chốt**.

### Public API contract

Auth V1 endpoints are documented in `docs/FRONTEND_API_INTEGRATION.md` and in Swagger UI at `/swagger-ui.html` (OpenAPI `/v3/api-docs`). Graph/SSE contracts remain TBD.

### Realtime contract

SSE đã được chốt về mặt kiến trúc, nhưng endpoint name, event type, version field, recovery API, heartbeat, và connection policy vẫn **TBD / Chưa chốt**.

---

## 9. Milestone tiếp theo

Thứ tự khuyến nghị:

```text
1. Configure GOOGLE_CLIENT_ID / SECRET and smoke-test Google browser login (PENDING_CONFIGURATION if unset)
2. Implement Jira/GitHub webhook ingress + identity mapping
4. Introduce RabbitMQ topology
5. Implement transactional outbox publisher/consumers
6. Design Neo4j graph model/projection
7. Add versioned SSE delivery
8. Implement Continuous Assessment pipeline
```

Thứ tự chỉ được đổi khi có nhu cầu dự án tường minh.

---

## 10. Definition of Done cho giai đoạn Bootstrap

Bootstrap được coi là xong khi:

- architecture skeleton đã thống nhất;
- README và tài liệu cốt lõi đã được điền;
- dependency baseline đã được review;
- file generate của project vẫn sạch;
- repository không chứa secret;
- repository có thể push an toàn lên GitHub;
- thành viên team hiểu ownership của từng package trước khi viết business code.

---

## 11. Quy tắc cập nhật

Mỗi khi một feature có ý nghĩa được shipped, cập nhật file này trong cùng PR.

Ví dụ:

```text
Auth local login implemented
Google OAuth implemented
Flyway V1 shipped
RabbitMQ topology shipped
Webhook idempotency shipped
Neo4j projection shipped
SSE v1 contract shipped
```

Không để file này vẫn ghi “chưa implement” sau khi feature đã shipped, và không đánh dấu đã implement chỉ dựa trên planning/documentation.
