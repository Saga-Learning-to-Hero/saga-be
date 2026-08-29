# SAGA Backend Decision Log

File này ghi các architecture Decision. Không được thay đổi thầm lặng.

Giá trị trạng thái:

- **ĐÃ CHỐT KIẾN TRÚC** — baseline hiện tại.
- **ĐANG ĐỀ XUẤT** — đang thảo luận, chưa ràng buộc.
- **ĐÃ BỊ THAY THẾ** — bị một Decision sau thay thế.

Định dạng ngày: `YYYY-MM-DD`.

---

## Mục lục Decision

| ID | Decision | Trạng thái | Ngày |
| --- | --- | --- | --- |
| DEC-001 | Modular Monolith trước | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-002 | MySQL là Source of Truth | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-003 | Neo4j là Graph Read Model có thể rebuild | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-004 | Không dual-write trực tiếp MySQL + Neo4j | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-005 | RabbitMQ là broker bất đồng bộ | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-006 | GitHub/Jira inbound realtime dùng Webhook | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-007 | Backend → Browser realtime dùng SSE | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-008 | Baseline Spring MVC + Java 21; không dùng WebFlux làm baseline | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-009 | Redis là hạ tầng ephemeral, không phải durable truth | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-010 | SSE event phải hỗ trợ version/gap recovery | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-011 | Root package theo cấu trúc technical layer của SAGA | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-012 | Backend mới không dùng AWS Cognito làm primary auth platform | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-013 | MongoDB không thuộc initial backend baseline | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-014 | Thay đổi schema database dùng Flyway | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-015 | Public contract với FE được ghi cùng thay đổi controller | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-016 | SAGA-owned internal IDs = UUID CHAR(36) | ĐÃ CHỐT KIẾN TRÚC | 2026-08-24 |
| DEC-017 | Local password storage = Argon2id hash only | ĐÃ CHỐT KIẾN TRÚC | 2026-08-24 |
| DEC-018 | Auth V1 = Spring Security session + Valkey/Redis | ĐÃ CHỐT KIẾN TRÚC | 2026-08-24 |
| DEC-019 | Google OIDC `sub` is provider identity; DB role wins | ĐÃ CHỐT KIẾN TRÚC | 2026-08-24 |
| DEC-020 | FPT Google role classification for new accounts | ĐÃ CHỐT KIẾN TRÚC | 2026-08-24 |
| DEC-021 | FPT Student first Google login requires local password | ĐÃ CHỐT KIẾN TRÚC | 2026-08-24 |
| DEC-022 | K19+ personal-email Students use local login only | ĐÃ CHỐT KIẾN TRÚC | 2026-08-24 |
| DEC-023 | Bootstrap Admin via env username + Argon2id password | ĐÃ CHỐT KIẾN TRÚC | 2026-08-24 |
| DEC-024 | K19+ personal-email Students may self-register as STUDENT | ĐÃ CHỐT KIẾN TRÚC | 2026-08-25 |
| DEC-025 | Google STUDENT and LECTURER must set a local SAGA password | ĐÃ CHỐT KIẾN TRÚC | 2026-08-25 |
| DEC-026 | Global provider identities belong to the SAGA user | ĐÃ CHỐT KIẾN TRÚC | 2026-08-25 |
| DEC-027 | Team resource integrations are Leader-only | ĐÃ CHỐT KIẾN TRÚC | 2026-08-25 |
| DEC-028 | Evidence-based attribution, not commit counting | ĐÃ CHỐT KIẾN TRÚC | 2026-08-25 |
| DEC-029 | Append-only audit log with actor snapshots | ĐÃ CHỐT KIẾN TRÚC | 2026-08-25 |
| DEC-030 | GitHub App installation for team repositories | ĐÃ CHỐT KIẾN TRÚC | 2026-08-25 |
| DEC-031 | Jira Cloud OAuth 2.0 3LO for team connection | ĐÃ CHỐT KIẾN TRÚC | 2026-08-25 |
| DEC-032 | Account-sharing threat model is explicit | ĐÃ CHỐT KIẾN TRÚC | 2026-08-25 |
| DEC-033 | Subject + versioned syllabus are academic specification, independent of Semester | ĐÃ CHỐT KIẾN TRÚC | 2026-08-29 |
| DEC-034 | Academic runtime is Semester / Class / Course | ĐÃ CHỐT KIẾN TRÚC | 2026-08-29 |
| DEC-035 | Email delivery is outbox then worker | ĐÃ CHỐT KIẾN TRÚC | 2026-08-29 |
| DEC-036 | Course roster uses invitation identity without phantom accounts | ĐÃ CHỐT KIẾN TRÚC | 2026-08-29 |

---

# DEC-001 — Modular Monolith trước

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Xây SAGA Backend theo Modular Monolith trước khi cân nhắc microservices.

## Lý do

Hệ thống đã chứa nhiều concern phức tạp:

- authentication;
- tích hợp GitHub/Jira;
- messaging bất đồng bộ;
- graph projection;
- realtime delivery;
- logic assessment.

Tách các phần này thành service deploy độc lập trước khi biết ranh giới scale thật sẽ tăng độ phức tạp vận hành và consistency mà chưa có nhu cầu được chứng minh.

## Hệ quả

- một Spring Boot deployable vẫn là baseline;
- ranh giới package/module vẫn phải được tôn trọng;
- worker/thành phần realtime có thể tách sau nếu số liệu tải chứng minh được nhu cầu.

---

# DEC-002 — MySQL là Source of Truth

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Trạng thái nghiệp vụ authoritative được lưu trong MySQL.

## Hệ quả

Neo4j, Redis và RabbitMQ không được trở thành kho authoritative cạnh tranh.

---

# DEC-003 — Neo4j là Graph Read Model có thể rebuild

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Neo4j dùng cho workload traversal/query/read trên graph và lưu Projection của trạng thái authoritative.

## Hệ quả

- thao tác projection phải idempotent;
- dữ liệu Neo4j phải recover/rebuild được;
- payload lớn hoặc metadata nghiệp vụ không liên quan không nên copy vào Neo4j nếu không có lý do graph-query.

---

# DEC-004 — Không dual-write trực tiếp MySQL + Neo4j

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Không implement thao tác nghiệp vụ theo kiểu:

```text
write MySQL
then
write Neo4j
```

rồi giả định cả hai thành công một cách atomic.

Dùng thay đổi MySQL authoritative cộng với pipeline outbox/projection.

## Lý do

Fail sau khi MySQL đã commit nhưng trước khi Neo4j được cập nhật sẽ tạo trạng thái inconsistent, không có bảo đảm ACID xuyên database.

---

# DEC-005 — RabbitMQ là broker bất đồng bộ

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Dùng RabbitMQ cho workload xử lý event/queue hiện tại.

Kafka không thuộc baseline.

## Hệ quả

Khi implement phải tính tới:

- durable topology;
- Publisher Confirm;
- Manual ACK phía consumer;
- Retry bounded;
- xử lý dead-letter;
- Idempotency phía consumer.

Nếu replay event dài hạn, throughput rất cao, hoặc nhiều consumer stream độc lập trở thành yêu cầu đã được chứng minh, việc chọn broker có thể xem lại qua một Decision mới.

---

# DEC-006 — GitHub/Jira inbound realtime dùng Webhook

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Ingest event GitHub/Jira dùng Webhook của provider, không lấy periodic polling làm cơ chế event chính.

## Hệ quả

Webhook ingress phải implement validation authenticity, ràng buộc payload, xử lý provider delivery ID, và Idempotency.

API của provider vẫn có thể được gọi khi ứng dụng cần enrichment/reconciliation.

---

# DEC-007 — Backend → Browser realtime dùng SSE

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Dùng Server-Sent Events (SSE) làm kênh realtime chính Backend → Browser.

## Lý do

Yêu cầu chủ đạo là phát event từ server tới client cho thay đổi graph, assessment, task và notification.

## Hệ quả

Nếu feature tương lai cần messaging hai chiều, latency thấp, khối lượng lớn, WebSocket có thể được xem lại qua một Decision mới.

---

# DEC-008 — Baseline Spring MVC + Java 21; không dùng WebFlux làm baseline

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Dùng Spring MVC với Java 21 và Virtual Threads khi phù hợp.

Không đưa WebFlux vào làm programming model mặc định.

## Lý do

Stack persistence và messaging hiện tại chủ yếu là imperative/blocking.

## Hệ quả

Reactive dependency như WebFlux, R2DBC và Reactive Redis không thuộc baseline.

---

# DEC-009 — Redis là hạ tầng ephemeral

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Redis có thể dùng cho:

- Rate Limiting;
- Cache;
- phối hợp tạm thời;
- Realtime Fan-out khi nhiều instance cần.

Redis không phải kho event/history authoritative.

## Hệ quả

Restart/disconnect Redis không được hủy trạng thái nghiệp vụ authoritative.

---

# DEC-010 — SSE event phải hỗ trợ version/gap recovery

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Realtime event làm thay đổi trạng thái client nhìn thấy phải hỗ trợ thứ tự version và recovery.

## Hệ quả

Frontend phải phát hiện được:

- event duplicate/stale;
- version bị thiếu;
- tình huống reconnect.

Chi tiết recovery endpoint/contract vẫn **TBD / Chưa chốt** cho đến khi realtime API đầu tiên được implement.

---

# DEC-011 — Root package theo cấu trúc technical layer của SAGA

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Giữ các root package theo technical layer kiểu SAGA đã thống nhất:

```text
controller
security
auth
service
integration
messaging
realtime
graph
repository
entity
dto
config
mapper
scheduler
exception
```

Không bắt mỗi feature phải theo `api/application/domain/infrastructure`.

## Lý do

Cấu trúc quen với team và giúp FE/API discovery thẳng, đồng thời vẫn cho phép domain subpackage bên trong từng technical layer.

---

# DEC-012 — Không dùng AWS Cognito làm primary auth platform

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

SAGA backend mới sẽ tự sở hữu trạng thái authentication/tài khoản, không dùng AWS Cognito làm primary authentication platform.

Google OAuth/OIDC có thể dùng làm cơ chế xác minh identity/login bên ngoài.

## Lưu ý

Điều này **không** có nghĩa availability của Google không bao giờ ảnh hưởng tới lần login bằng Google.

Thiết kế session/token nội bộ chính xác vẫn là **ĐANG ĐỀ XUẤT / TBD / Chưa chốt** và không được invent ngầm.

---

# DEC-013 — MongoDB không thuộc initial baseline

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Không thêm MongoDB trong giai đoạn kiến trúc ban đầu.

Yêu cầu audit/history nên được thiết kế trước trên mô hình lưu trữ authoritative hiện có.

## Hệ quả

Thêm MongoDB sau này đòi hỏi yêu cầu đã được chứng minh và một Decision entry mới.

---

# DEC-014 — Thay đổi schema database dùng Flyway

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Thay đổi schema MySQL được version qua Flyway migration.

## Hệ quả

Không đổi schema shared/prod thủ công nếu chưa có migration.

---

# DEC-015 — Thay đổi contract FE phải được ghi tài liệu

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Khi public endpoint, auth contract, SSE event, error model, hoặc request/response payload được implement hoặc thay đổi, cập nhật:

```text
docs/FRONTEND_API_INTEGRATION.md
```

trong cùng thay đổi đó.

## Hệ quả

Chỉ có controller code thì chưa đủ làm tài liệu contract cho Frontend.

---

# DEC-016 — SAGA-owned internal IDs = UUID CHAR(36)

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-24

## Bối cảnh

SAGA cần identity ổn định, không đoán được, không phụ thuộc AUTO_INCREMENT, và tách biệt khỏi identifier của GitHub/Jira.

## Quyết định

Mọi identifier do SAGA sinh và sở hữu (PK, FK nội bộ, join/link id, outbox, notification, assessment, AI, graph processing) là UUID:

- Java: `java.util.UUID`
- MySQL: `CHAR(36)`
- Generation: `GenerationType.UUID` (random UUID / UUID v4)

Provider identifiers giữ semantics gốc (`repository_id`, `sha_hash`, `external_id`, `external_key`, …) và **không** là PK.

## Ngoại lệ

`active_semester_setting.singleton_id` (`TINYINT` = 1) là singleton technical key. Đây là **ngoại lệ có chủ đích duy nhất**.

## Hệ quả

Không dùng BIGINT/INT AUTO_INCREMENT, sequential numeric PK, hay provider ID làm PK cho entity SAGA thông thường.

---

# DEC-017 — Local password storage = Argon2id hash only

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-24

## Bối cảnh

SAGA tự sở hữu account security (DEC-012). Cần lưu credential local mà không lưu plaintext hay hash tự viết.

## Quyết định

- Cột `user_account.password_hash VARCHAR(255) NULL`
- Chỉ lưu output của Spring Security `PasswordEncoder`
- Thuật toán **Argon2id** (OWASP: memory 19456 KiB, iterations 2, parallelism 1, salt 16, hash 32)
- Runtime: `Argon2PasswordEncoder` + Bouncy Castle `bcprov-jdk18on` (required by Spring Security’s Argon2 implementation)
- Nullable: tài khoản OIDC-only có thể không có mật khẩu local
- Cấm plaintext, reversible encryption, `raw_password`, `password_salt` thủ công, MD5/SHA-family password hashing, field Cognito

## Không thuộc Decision này

Registration, login, JWT, refresh token, session, logout, password reset, OAuth/OIDC **chưa implement**. Session/token architecture vẫn TBD.

## Hệ quả

Auth V2 sau này inject `PasswordEncoder` đã cấu hình. BCrypt không phải lựa chọn chính; chỉ cân nhắc sau nếu có compatibility constraint được ghi rõ.

---

# DEC-018 — Auth V1 uses server-side Spring Security sessions backed by Valkey/Redis

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-24

## Bối cảnh

SAGA sở hữu authentication (DEC-012). Browser auth cần session an toàn cho SPA mà không đưa access/refresh token vào `localStorage`.

## Quyết định

Auth V1 dùng:

- server-side Spring Security session (`SessionCreationPolicy.IF_REQUIRED`)
- HttpOnly session cookie `SAGA_SESSION`
- Spring Session backed by existing Redis/Valkey (ephemeral)
- MySQL `user_account` là durable account/role Source of Truth

Không dùng JWT access/refresh, Cognito, hay STATELESS auth cho browser Auth V1.

## Hệ quả

Session không lưu trong MySQL. Test profile không kết nối Aiven Redis. CSRF được bật cho cookie session (CookieCsrfTokenRepository + `X-XSRF-TOKEN`).

---

# DEC-019 — Google OIDC uses `sub` as provider identity; existing role always wins

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-24

## Quyết định

- Google identity ổn định = OIDC `sub`, persist `user_account.google_subject`
- Email không phải long-term Google identity key sau khi đã link
- Account đã tồn tại: `account_role` trong MySQL là authority; không re-classify bằng regex Google
- ADMIN không bao giờ được auto-provision từ Google

## Hệ quả

Linking: tìm theo `google_subject`, rồi email; conflict nếu `google_subject` khác. Không có bảng `auth_identity` trong Auth V1.

---

# DEC-020 — FPT Google role classification for new accounts only

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-24

## Quyết định

Chỉ khi OIDC hợp lệ, `email_verified=true`, account chưa tồn tại, và hosted-domain policy pass:

- email kết thúc `@fe.edu.vn` → `LECTURER`
- `^[A-Za-z]+\d{6}@fpt\.edu\.vn$` → `STUDENT`
- verified institutional `@fpt.edu.vn` không khớp student regex → `LECTURER`
- còn lại → không self-provision Google

Allowed hosted domains cấu hình qua `SAGA_AUTH_GOOGLE_ALLOWED_HOSTED_DOMAINS` (mặc định `fpt.edu.vn,fe.edu.vn`). Thiếu/conflict `hd` → fail closed.

---

# DEC-021 — FPT Student first Google login must set a SAGA local password

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-24

## Quyết định

Student Google-authenticated với `password_hash IS NULL` nhận session hạn chế. Backend chặn business API cho đến khi `POST /api/auth/password/setup` thành công. Không tin frontend redirect.

Lecturer/Admin **không** bị forced password-setup trong Decision này. **DEC-025** mở rộng yêu cầu này cho LECTURER Google.

---

# DEC-022 — K19+ personal-email Students use local email/password only

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-24

## Quyết định

Personal Gmail/Yahoo/Outlook không authenticate qua Google SAGA Login. Không self-provision Google từ gmail.com.

Auth V1 **không** implement public registration. **DEC-024 (Auth V1.1)** khóa self-registration STUDENT cho email cá nhân.

---

# DEC-023 — Bootstrap Admin authenticates by username; credential from environment

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-24

## Quyết định

Một bootstrap Admin: username cấu hình (mặc định `admin`), password từ env, hash Argon2id. Idempotent: không reset password mỗi startup. Không hardcode `saga123` trong source/migration/committed config. Non-local từ chối password `saga123`. Google không tạo ADMIN.

---

# DEC-024 — K19+ personal-email Students may self-register as STUDENT

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-25

## Bối cảnh

K19+ Students dùng email cá nhân không đi Google OIDC (DEC-022). Auth V1.1 cần một đường onboarding public, không role selector, không cấp membership học thuật.

## Quyết định

`POST /api/auth/register` (public, CSRF-protected) luôn tạo `account_role = STUDENT`. Client không được gửi/chọn role. Email institutional (`@fpt.edu.vn`, `@fe.edu.vn`, và Google hosted domains đã cấu hình) bị từ chối (`INSTITUTIONAL_EMAIL_USE_GOOGLE`). Registration tạo `user_account` + `student_profile` only — **không** `course_enrollment` / `team_member`. Không auto-login; gọi `POST /api/auth/login` sau. Google login cho Gmail cá nhân vẫn bị reject.

## Các lựa chọn khác

Provisioning bởi Lecturer/Admin; Lecturer/Admin public registration; role selector — loại.

## Hệ quả

K19+ self-serve được. Isolation học thuật phải do Course/Team authorization, không phải vì có role STUDENT. Email ownership verification là enhancement tương lai, không thuộc Auth V1.1.

## Migration

Bổ sung DEC-022. Không thay schema (V1–V3 đủ). Không overwrite DEC-022 phần “không Google cho personal email”.

---

# DEC-025 — Google STUDENT and LECTURER must set a local SAGA password

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-25

## Bối cảnh

Auth V1 chỉ bắt Student Google set password (DEC-021). Lecturer cần cùng local email+password sau first Google login.

## Quyết định

Nếu account Google-linked (`google_subject != NULL`) là STUDENT hoặc LECTURER và `password_hash IS NULL` thì `passwordSetupRequired=true` cho đến `POST /api/auth/password/setup`. ADMIN không áp dụng. Personal-email Student từ DEC-024 đã có password lúc register. Setup không overwrite hash hiện có.

## Các lựa chọn khác

Chỉ Google cho Lecturer; plaintext temp password — loại.

## Hệ quả

Cùng account hỗ trợ Google và local email+password. `PasswordSetupEnforcementFilter` chặn business API cho cả STUDENT và LECTURER restricted sessions.

## Migration

Mở rộng DEC-021 sang LECTURER. Không đổi Flyway.

---

# DEC-026 — GLOBAL_PROVIDER_IDENTITIES

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-25

## Bối cảnh

SAGA phải gán GitHub/Jira activity cho user, không cho team. Một user có thể có nhiều account GitHub/Jira.

## Quyết định

`identity_map` thuộc SAGA user. Unique active owner là `(provider, stable_provider_subject)`. Không unique `(user, provider)`. GitHub identity = numeric user id. Jira identity = Atlassian accountId. Username/login là display data.

## Hệ quả

V4 drop `uk_identity_user_provider`. Conflict `EXTERNAL_IDENTITY_ALREADY_LINKED` không tiết lộ user đang giữ identity.

---

# DEC-027 — TEAM_RESOURCE_INTEGRATIONS

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-25

## Quyết định

Personal identity link ≠ team resource connection. Chỉ `RoleInTeam.LEADER` (hoặc ADMIN recovery) được connect GitHub App installation / Jira site-project-board. Team Leader = `team_member.role_in_team = LEADER`.

---

# DEC-028 — EVIDENCE_BASED_ATTRIBUTION

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-25

## Quyết định

GitHub/Jira activity là evidence, không phải proof of physical authorship. Không đếm commit = contribution. Không gắn nhãn CHEATER/FRAUD. Confidence và risk signal tách khỏi academic score. Jira vẫn là source of truth cho workflow status.

---

# DEC-029 — APPEND_ONLY_AUDIT

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-25

## Quyết định

`audit_log` append-only, actor snapshot (name/role/email/student code), academic context khi unambiguous. Không API update/delete. Secrets đi qua `AuditRedactor`.

---

# DEC-030 — GITHUB_APP_INTEGRATION

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-25

## Quyết định

Team repos dùng GitHub App installation, không PAT. Installation access token mint từ App JWT, cache Valkey TTL < expiry, không persist domain. Webhook HMAC-SHA256 `X-Hub-Signature-256`. Không tin `installation_id` trên query nếu chưa verify App API.

---

# DEC-031 — JIRA_CLOUD_3LO

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-25

## Quyết định

Jira Cloud OAuth 2.0 3LO. Personal link không cần offline refresh. Team connect cần `offline_access`. Refresh token AES-256-GCM versioned envelope. Dynamic webhooks + scheduler refresh trước hạn. Connecting Jira account phải đã link trên profile SAGA.

---

# DEC-032 — ACCOUNT_SHARING_THREAT_MODEL

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-25

## Quyết định

Student A có thể đưa credential GitHub/Jira cho Student B. Provider events vẫn mang identity A. SAGA không claim physical authorship. Mitigation: provider identity + SAGA work session + step-up + optional passkey + task confirmation + audit + anomaly + lecturer review.

---

# DEC-033 — SUBJECT_AND_VERSIONED_SYLLABUS_ARE_ACADEMIC_SPECIFICATION

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-29

## Bối cảnh

Subject Graph Blueprint và Runtime Learning Graph cần một academic definition ổn định. GitHub/Jira không phải nền của graph. Rubric/weight đã tồn tại nhưng là assessment configuration.

## Quyết định

- Subject là academic catalog độc lập với Semester/Class/Course. Không có `semester_id` trên Subject hay Syllabus.
- Một Subject có 1..N syllabus versions. Version không được tạo tự động theo semester.
- `DRAFT` được sửa. `PUBLISHED` là immutable academic snapshot. Muốn sửa → version mới. `ARCHIVED` vẫn readable.
- Academic structure (Learning Outcome, Learning Unit/topic, Phase, Expected Activity, Expected Deliverable, mappings) sống trên syllabus version, không nhét vào `rubric_template`.
- Learning Outcome là **what the learner is expected to achieve** (không phải assessment). Learning Unit là **what is taught**. Phase là **how/when** work progresses. Không overload `syllabus_phase` thành course content.
- Composite unique `(id, syllabus_version_id)` + composite FKs ngăn cross-syllabus phase/outcome/unit/deliverable references (MySQL SoT cho future graph).
- Phase/activity/deliverable/unit codes là dữ liệu syllabus, không hard-code Java enum, không gắn GitHub/Jira.
- Future Course sẽ bind Subject + pinned SyllabusVersion. Future graph sẽ project academic structure từ MySQL; Graph/Neo4j không implement trong decision này.
- Assessment weights/formulas vẫn thuộc rubric / `project_group_weight_config` / assessment pipeline.

## Hệ quả

Admin catalog APIs quản lý Subject/Syllabus. Course offering, roster, graph projection, và scoring là milestone riêng.

---

# DEC-034 — ACADEMIC_RUNTIME_IS_SEMESTER_CLASS_COURSE

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-29

## Bối cảnh

Subject/Syllabus (DEC-033) là catalog. Runtime teaching cần Semester → AcademicClass → Course, với Course pin một PUBLISHED syllabus version và một lecturer, trước roster import.

## Quyết định

- Subject và SyllabusVersion **không** thuộc Semester. Không có `semester_id` trên `subject` hay `subject_syllabus_version`.
- AcademicClass thuộc đúng một Semester. Class code uniqueness is `(semester_id, class_code)` — cùng mã lớp có thể lặp ở kỳ sau.
- Course là teaching/runtime offering, không phải Subject. Course bind: AcademicClass + Subject + PUBLISHED SyllabusVersion + Lecturer (`lecturer_profile`).
- Course pin syllabus historically. Không follow "latest". Composite FK `(syllabus_version_id, subject_id)` chứng minh syllabus thuộc đúng subject.
- `course.semester_id` được giữ (index/query V1) và được chứng minh khớp class qua composite FK `(academic_class_id, semester_id)`.
- Uniqueness Course: `(academic_class_id, subject_id)` — một lớp chỉ có một offering cho một subject.
- Active Semester là singleton `active_semester_setting`, không phải status column trên semester, không phải ownership của Subject.
- Syllabus pin chỉ đổi khi Course chưa có enrollment/project. DRAFT/ARCHIVED không dùng cho course mới.
- Roster, Team, Graph/Neo4j, GitHub/Jira không thuộc decision này.

## Hệ quả

Admin runtime APIs quản lý Semester/Class/Course. Student roster import và graph projection là milestone riêng.

---

# DEC-035 — EMAIL_DELIVERY_IS_OUTBOX_THEN_WORKER

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-29

## Bối cảnh

`email_outbox` đã có từ V1 nhưng chưa có provider hay worker. Roster/invitation sẽ cần gửi mail mà không gắn SMTP vào transaction nghiệp vụ.

## Quyết định

- Không tạo V7. Schema V1 đủ: `delivery_status`, `attempt_count`, `scheduled_at`, `sent_at`, `last_failure_code`, `last_attempt_at`. `delivery_status` là VARCHAR(32) không CHECK.
- Business service chỉ `EmailOutboxService.enqueue`. Worker riêng claim/send.
- Status: `PENDING` → `PROCESSING` (claim) → `SENT` hoặc retry `PENDING` với backoff, hoặc `FAILED` khi hết attempt. `CANCELLED` không được worker xử lý.
- `PROCESSING` là application status; không cần migration.
- Concurrency: atomic claim `PENDING`/`stale PROCESSING` → `PROCESSING` bằng `last_attempt_at` lease. Không cần `version` column cho V1.
- Provider: `EmailSender`. SMTP implementation qua Spring Mail. Config từ env. Tắt được (`SAGA_MAIL_ENABLED=false`).
- `last_failure_code` chỉ lưu mã ngắn; không lưu password/API key/exception message.
- Subject/body nằm trong `payload_json` (`subject`, `textBody`, `htmlBody`).
- Smoke: `POST /api/admin/dev/email-test` chỉ `local`/`dev`, ADMIN.
- Roster, invitation lifecycle, enrollment, team, forgot-password **không** thuộc decision này.

## Hệ quả

Invitation/roster có thể enqueue mà không đụng SMTP. Worker độc lập với transaction gốc.

---

# DEC-036 — COURSE_ROSTER_USES_INVITATION_IDENTITY_WITHOUT_PHANTOM_ACCOUNTS

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-29

## Bối cảnh

V1 `student_course_invitation.student_profile_id` is NOT NULL and has no email/student_code columns, so a no-account roster invite cannot be stored without creating a fake Student. MemberCode has no canonical persistence field.

## Quyết định

- Three concepts stay separate: user account, course enrollment, team membership. Roster implements account + enrollment only. No Team.
- Existing STUDENT → ACTIVE `course_enrollment` immediately. No phantom `user_account` / `student_profile` for unknown emails.
- No-account rows persist PENDING `student_course_invitation` with email + student_code + full_name after V7 makes `student_profile_id` nullable.
- `MemberCode` is Excel/preview-only. Do not add a DB column.
- Preview is Redis (`previewToken`, ~15m, bound to Admin + courseId). Confirm is one MySQL transaction; token is deleted only after success so a failed confirm can retry.
- Excel `Class` must match the path Course's AcademicClass. `No` is not identity. Course comes from the URL.
- Institutional FPT/FE invitation copy uses Google onboarding (`InstitutionalEmailPolicy`). Personal emails use public Student registration.
- Emails `COURSE_ENROLLED` / `COURSE_INVITATION` go through `EmailOutboxService` only.
- First successful verified Student register/login/Google onboarding claims matching PENDING invitations (email + StudentCode). Idempotent. Claim must not fail login.
- Manual Admin add-student HTTP API is deferred; shared service apply path is ready.
- V7 is required for invitation identity. V1–V6 stay immutable. Do not apply V7 to shared DEV until reviewed.
- V7 CHECK `chk_invitation_identity`: `student_profile_id IS NOT NULL` OR (nonblank `email` AND nonblank `student_code`). `full_name` is not an identity key.
- Unique `(course_id, email)` and `(course_id, student_code)`: reuse the existing invitation row. PENDING/SENT = outstanding (idempotent). FAILED/CANCELLED may be reactivated to PENDING on the same row. CLAIMED + existing Student follows enrollment, not a second invite. No `EXPIRED` status exists.
- Before apply, run SELECT-only preflight in `scripts/migration/V7_preflight_invitation_duplicates.sql` (joins `student_profile` / `user_account`; those identity columns do not exist on invitation until V7).

## Hệ quả

Admin roster APIs: template, preview, confirm, read. No fake users. No Team. No Graph. No direct SMTP from the business transaction.

---

## Thêm Decision mới

Dùng template:

```text
# DEC-XXX — Title

Trạng thái:
Ngày:

## Bối cảnh

Vì sao cần một Decision?

## Quyết định

Chúng ta chọn gì?

## Các lựa chọn khác

Chúng ta loại phương án nào?

## Hệ quả

Điều gì trở nên dễ hơn / khó hơn?

## Migration

Cần đổi gì nếu Decision này thay thế Decision cũ?
```
