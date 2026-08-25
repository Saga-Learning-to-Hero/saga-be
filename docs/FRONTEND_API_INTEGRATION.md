# SAGA Backend — Tích hợp API cho Frontend

File này là **contract tích hợp Backend ↔ Frontend có hiệu lực** khi public API đã được implement.

> **Trạng thái hiện tại:** Auth V1 + V1.1 public contract đã chốt bên dưới. Graph/SSE vẫn TBD. Email ownership verification for personal registration is a possible future enhancement (not in this contract).

---

## 1. Frontend developer nên đọc gì

Frontend developer thông thường cần:

```text
http://localhost:8080/                 (developer landing)
http://localhost:8080/swagger-ui.html  (Try it out + CSRF)
http://localhost:8080/v3/api-docs      (OpenAPI JSON)
docs/FRONTEND_API_INTEGRATION.md
```

OpenAPI YAML: `http://localhost:8080/v3/api-docs.yaml`.

Swagger UI documents request/response schemas for Auth V1. Inspect those instead of reading backend source for field names.

Không cần soi các phần sau:

```text
integration/provider internals
RabbitMQ consumers
Neo4j projection workers
Redis infrastructure
JPA entities
database migrations
```

trừ khi đang debug lỗi implement phía Backend.

---

## 2. Trạng thái contract hiện tại

| Hạng mục contract | Trạng thái |
| --- | --- |
| Base API path | `/api` |
| API versioning | Không version trong path (Auth V1) |
| Login endpoint | `POST /api/auth/login` |
| Logout endpoint | `POST /api/auth/logout` |
| Current-user endpoint | `GET /api/auth/me` |
| CSRF contract | `GET /api/auth/csrf` + cookie `XSRF-TOKEN` + header `X-XSRF-TOKEN` |
| Session/token transport | HttpOnly cookie `SAGA_SESSION` (server-side session; **không JWT**) |
| Error response format | `{ "code": "...", "message": "..." }` |
| Pagination format | TBD / Chưa chốt |
| Graph snapshot endpoint | TBD / Chưa chốt |
| Graph delta/recovery endpoint | TBD / Chưa chốt |
| SSE endpoint | TBD / Chưa chốt |
| SSE event envelope | Đã lên kế hoạch kiến trúc, schema chính xác TBD / Chưa chốt |
| CORS credential policy | Allow-list origins + `credentials`; never `*` with credentials |

---

## 3. Authentication contract (Auth V1)

SAGA owns authentication. Transport is a **server-side Spring Security session** stored in Redis/Valkey and sent as cookie `SAGA_SESSION`.

Frontend **must**:

- use `credentials: "include"` on all auth and API calls;
- **not** store access/refresh tokens in `localStorage`;
- **not** send Bearer SAGA tokens;
- **not** send/select `role` in login JSON;
- obtain CSRF before state-changing requests.

### Cookie

| Name | HttpOnly | Purpose |
| --- | --- | --- |
| `SAGA_SESSION` | true | Server session id |
| `XSRF-TOKEN` | false | CSRF token readable by SPA |

`Secure`: false for local HTTP; true on deployed HTTPS (`dev` profile default).  
`SameSite`: `Lax` local; `None` when FE/BE are different HTTPS sites (`dev` default, overridable).

### CSRF

1. `GET /api/auth/csrf` (or any GET that issues the CSRF cookie).
2. Read cookie `XSRF-TOKEN` **or** the JSON `token` field from that response (they are the same raw value).
3. Send header `X-XSRF-TOKEN` on POST/PUT/PATCH/DELETE.
4. Include credentials.

Swagger UI (`/swagger-ui.html`) is configured to copy cookie `XSRF-TOKEN` into header `X-XSRF-TOKEN`. Session cookie `SAGA_SESSION` is HttpOnly; the browser keeps it after `POST /api/auth/login`.

Developer smoke in Swagger:

1. `GET /api/auth/csrf` → Execute
2. `POST /api/auth/login` with identifier/password → Execute
3. `GET /api/auth/me` → `authenticated: true`
4. `POST /api/auth/logout` → session cleared

OAuth2 start/callback (`/oauth2/**`, `/login/oauth2/**`) are CSRF-ignored because they are browser redirects. Google login is `GET /oauth2/authorization/google` in the browser when `GOOGLE_CLIENT_ID` is configured — not a Swagger JSON call.

### CORS

Allowed origins: `SAGA_AUTH_FRONTEND_ORIGINS` (comma-separated). Credentials allowed. Wildcard origin is not used.

### Password policy

Centralized in backend (`saga.auth.password`):

- reject blank
- minimum length **10**
- maximum length **128** (not truncated)
- `newPassword` must equal `confirmPassword`
- no extra composition rules (no mandatory symbol/digit mix)

### Error envelope

```json
{ "code": "INVALID_CREDENTIALS", "message": "Authentication failed." }
```

| HTTP | Code | When |
| --- | --- | --- |
| 401 | `INVALID_CREDENTIALS` | Local login failed (generic; no user enumeration) or unauthenticated |
| 403 | `ACCOUNT_DISABLED` | `account_status` is not `ACTIVE` |
| 403 | `GOOGLE_EMAIL_NOT_VERIFIED` | Google email not verified |
| 403 | `GOOGLE_DOMAIN_NOT_ALLOWED` | Personal/non-institutional Google, or `hd` policy fail-closed |
| 409 | `GOOGLE_IDENTITY_CONFLICT` | Email already linked to a different Google `sub` |
| 403 | `GOOGLE_ACCOUNT_NOT_ELIGIBLE` | Cannot auto-provision this Google identity |
| 403 | `PASSWORD_SETUP_REQUIRED` | Google STUDENT or LECTURER session must set local password before business APIs |
| 409 | `PASSWORD_ALREADY_SET` | Setup endpoint will not overwrite an existing hash |
| 400 | `PASSWORD_POLICY_VIOLATION` | Password policy failed |
| 400 | `PASSWORD_CONFIRMATION_MISMATCH` | Password and confirmation do not match |
| 400 | `INSTITUTIONAL_EMAIL_USE_GOOGLE` | Public register rejected; use Google for `@fpt.edu.vn` / `@fe.edu.vn` |
| 409 | `EMAIL_ALREADY_REGISTERED` | Email already has a `user_account` |
| 409 | `STUDENT_CODE_ALREADY_EXISTS` | `student_profile.student_code` unique conflict |
| 400 | `INVALID_REGISTRATION_DATA` | Registration fields invalid |
| 403 | `ACCESS_DENIED` | Authenticated but role/path not allowed |
| 400 | `REQUEST_INVALID` | Bean validation on request body |

Google redirect failures append `?error=<CODE>` (or `&error=`) to `SAGA_AUTH_GOOGLE_FAILURE_URL`.

### Login page (FE)

- Email / Username
- Password
- **Sign in** → `POST /api/auth/login`
- **Continue with Google** → browser `GET /oauth2/authorization/google`
- For personal-email Students: **Don't have a SAGA account? Register** → registration form (no role selector)

Registration form fields: full name, personal email, student code, password, confirm password.

Do **not** show Choose role: Student / Lecturer / Admin. There is no role selector.

### GET /api/auth/csrf

**Auth:** Optional  

No request parameters and no body. Returns:

```json
{ "parameterName": "_csrf", "token": "<csrf>", "headerName": "X-XSRF-TOKEN" }
```

Also sets cookie `XSRF-TOKEN` (same raw `token` value).

### GET /api/auth/me

**Auth:** Optional (returns `authenticated: false` if anonymous)

```json
{
  "authenticated": true,
  "passwordSetupRequired": false,
  "user": {
    "id": "UUID",
    "email": "...",
    "username": null,
    "fullName": "...",
    "avatarUrl": "...",
    "role": "STUDENT"
  }
}
```

Never includes `passwordHash`, `googleSubject`, session id, or provider tokens.

### POST /api/auth/login

**Auth:** Public (CSRF required)

```json
{ "identifier": "anvse170102@fpt.edu.vn", "password": "..." }
```

- identifier contains `@` → lookup by normalized email
- otherwise → lookup by username (bootstrap Admin: `admin`)
- Role is loaded from MySQL only

Response: same as `GET /api/auth/me` (200). Failures: `INVALID_CREDENTIALS` / `ACCOUNT_DISABLED`.

### POST /api/auth/register

**Auth:** Public (CSRF required)  
**Creates:** `STUDENT` only. Never LECTURER/ADMIN. Never auto-enrolls in a course or team.

```json
{
  "email": "student.personal@example.com",
  "fullName": "Example Student",
  "studentCode": "SE123456",
  "password": "example-password",
  "confirmPassword": "example-password"
}
```

- Personal/non-institutional email only
- `@fpt.edu.vn` / `@fe.edu.vn` (and other configured Google hosted domains) → `INSTITUTIONAL_EMAIL_USE_GOOGLE`
- `studentCode` is required and stored on `student_profile`; it is **not** derived from the email local-part
- Password hashed with Argon2id; registration does **not** open a session — call `POST /api/auth/login` next
- HTTP **201**: `{ "registered": true, "user": { "id", "email", "fullName", "role": "STUDENT" } }`

### POST /api/auth/password/setup

**Auth:** Required (restricted Google STUDENT or LECTURER session)

```json
{ "newPassword": "...", "confirmPassword": "..." }
```

Only when `google_subject` is set and `password_hash` is currently NULL. Success clears `passwordSetupRequired`. Does not overwrite an existing hash (`PASSWORD_ALREADY_SET`).

### POST /api/auth/logout

**Auth:** Public endpoint; CSRF required. Invalidates Redis session, clears `SAGA_SESSION` and `XSRF-TOKEN`. **204**.

### Google OIDC

Start: navigate browser to `{API_ORIGIN}/oauth2/authorization/google`  
Callback (register in Google Cloud): `{API_ORIGIN}/login/oauth2/code/google`  
Scopes: `openid profile email`

Success redirect: `SAGA_AUTH_GOOGLE_SUCCESS_URL` (dashboard) or `SAGA_AUTH_GOOGLE_PASSWORD_SETUP_URL` (`/auth/setup-password`) when a Google STUDENT or LECTURER needs password setup.

Requires configured `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`. If unset, Google login is not enabled (**PENDING_CONFIGURATION**).

### Flows

**A — FPT Student first Google login:** Google → backend creates/links STUDENT → `passwordSetupRequired=true` → FE `/auth/setup-password` → `POST /api/auth/password/setup` → dashboard. Later: Google **or** email + SAGA password.

**B — FPT Student later Google login:** linked by `google_subject` → DB role → dashboard.

**C — FPT Student local login:** email + SAGA password → session → dashboard.

**D — K19+ personal email:** `POST /api/auth/register` (STUDENT only, no course/team membership) then `POST /api/auth/login` with email + password. Google SAGA login **rejected**.

**E — Lecturer Google:** `@fe.edu.vn` or verified `@fpt.edu.vn` not matching student regex → new account LECTURER; existing account keeps DB role. First Google login with `password_hash` NULL → `passwordSetupRequired=true` then local email + password also works.

**F — Admin:** username `admin` + env password. Google cannot provision ADMIN. No public registration.

### Authorization

| DB role | Spring authority | Path example |
| --- | --- | --- |
| STUDENT | `ROLE_STUDENT` | authenticated APIs except `/api/lecturer/**`, `/api/admin/**` |
| LECTURER | `ROLE_LECTURER` | `/api/lecturer/**` |
| ADMIN | `ROLE_ADMIN` | `/api/admin/**` and lecturer paths |

While `passwordSetupRequired=true`, only `/api/auth/me`, `/api/auth/csrf`, `POST /api/auth/password/setup`, `POST /api/auth/logout` (plus docs/OAuth/login/register) are allowed.

---

## 4. Quy tắc REST API

Khi endpoint đầu tiên được implement, mỗi public endpoint phải được ghi tại đây với:

```text
Method
Path
Authentication requirement
Authorization requirement
Request body/query
Response body
Error cases
Idempotency behavior (nếu liên quan)
Pagination behavior (nếu liên quan)
```

Template:

```markdown
### GET /example

**Auth:** Required  
**Roles:** STUDENT, LECTURER

#### Request

Query:

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| ... | ... | ... | ... |

#### Response — 200

```json
{}
```

#### Errors

| Status | Code | Meaning |
| --- | --- | --- |
| 400 | ... | ... |
```

Không expose controller cho FE nếu chưa cập nhật file này.

---

## 5. Error contract

Auth V1 uses:

```json
{ "code": "INVALID_CREDENTIALS", "message": "Authentication failed." }
```

See the table in section 3. Do not parse Java exception messages. Do not expect password or Google token fields in errors.

---

## 6. Realtime / SSE contract

Quyết định kiến trúc: Backend → Browser realtime dùng **Server-Sent Events (SSE)**.

Endpoint và event schema chính xác **chưa được implement**.

Envelope event mang tính khái niệm:

```json
{
  "eventId": "...",
  "scopeId": "...",
  "version": 1,
  "type": "...",
  "occurredAt": "...",
  "data": {}
}
```

Ví dụ này **chưa phải API schema đã cam kết**.

### Hành vi bắt buộc khi đã implement

Frontend cuối cùng cần hỗ trợ:

```text
version == currentVersion + 1
    -> apply delta

version <= currentVersion
    -> ignore duplicate/stale event

version > currentVersion + 1
    -> gap detected
    -> recover from backend snapshot/delta API
```

Backend phải ghi rõ:

- SSE URL;
- hành vi authentication;
- heartbeat;
- hành vi reconnect;
- event type;
- event envelope;
- phạm vi version;
- recovery endpoint;
- ngữ nghĩa snapshot;
- kích thước event tối đa;
- hành vi khi client quá chậm.

---

## 7. Graph API contract

Backend không được lấy “trả về toàn bộ graph” làm pattern tích hợp mặc định.

Graph endpoint sau này nên expose scope tường minh, ví dụ:

```text
course
class
project
team
sprint
status
depth
maxNodes
```

Query parameter chính xác: **TBD / Chưa chốt**.

Frontend không được dựa vào kích thước graph mà Backend chưa cam kết trong contract.

Lần load graph ban đầu nên được xem là snapshot/read query; realtime chủ yếu phát delta.

---

## 8. Ranh giới TanStack Query

Cache server-state thuộc về Frontend.

Contract Backend nên làm caching/revalidation khả thi nhờ ngữ nghĩa endpoint ổn định, nhưng không được phụ thuộc hành vi đặc thù của TanStack Query.

Trách nhiệm Backend:

- response contract deterministic;
- HTTP status code phù hợp;
- metadata pagination/version khi cần;
- identifier ổn định.

Trách nhiệm Frontend:

- query key;
- stale time / cache time;
- hành vi refetch;
- UI loading/error state.

---

## 9. Quy tắc thay đổi public API

Một thay đổi public contract gồm:

- route path;
- HTTP method;
- field request;
- field response;
- ngữ nghĩa field;
- yêu cầu auth;
- yêu cầu role;
- error code;
- pagination;
- SSE event name/schema;
- hành vi version của SSE.

Mọi thay đổi như vậy phải cập nhật file này trong cùng PR.

Breaking change phải được nêu rõ.

---

## 10. Endpoint registry

| Method | Path | Auth | Roles | Status | Owner |
| --- | --- | --- | --- | --- | --- |
| GET | `/` | Public | — | Developer landing | static `index.html` |
| GET | `/swagger-ui.html` | Public | — | Swagger UI | springdoc |
| GET | `/v3/api-docs` | Public | — | OpenAPI JSON | springdoc |
| GET | `/v3/api-docs.yaml` | Public | — | OpenAPI YAML | springdoc |
| GET | `/api/auth/csrf` | Optional | — | Auth V1 | `AuthController` |
| GET | `/api/auth/me` | Optional | — | Auth V1 | `AuthController` |
| POST | `/api/auth/login` | Public + CSRF | — | Auth V1 | `AuthController` |
| POST | `/api/auth/register` | Public + CSRF | STUDENT created | Auth V1.1 | `AuthController` |
| POST | `/api/auth/password/setup` | Session + CSRF | STUDENT or LECTURER (Google first password) | Auth V1.1 | `AuthController` |
| POST | `/api/auth/logout` | CSRF | — | Auth V1 | `AuthController` |
| GET | `/oauth2/authorization/google` | Public | — | Auth V1 (if Google configured) | Spring Security OAuth2 |
| GET | `/login/oauth2/code/google` | Public (callback) | — | Auth V1 (if Google configured) | Spring Security OAuth2 |
