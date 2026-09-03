# SAGA Backend — Tích hợp API cho Frontend

File này là **contract tích hợp Backend ↔ Frontend có hiệu lực** khi public API đã được implement.

> **Trạng thái hiện tại:** Auth V1 + V1.1 public contract đã chốt bên dưới. Admin Subject + versioned syllabus catalog V1 đã chốt. Admin Semester / Academic Class / Course runtime V1 đã chốt. Admin Course Roster V1 (template → preview → confirm + auto-claim) đã chốt. Lecturer Team Management V1 (assigned courses, ACTIVE roster, team XLSX preview/confirm, student my-team) đã chốt. Graph/SSE vẫn TBD. Email ownership verification for personal registration is a possible future enhancement (not in this contract).

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
**Creates:** `STUDENT` only. Never LECTURER/ADMIN. Never creates Team membership. Matching PENDING `student_course_invitation` rows for this email + StudentCode are claimed automatically into ACTIVE `course_enrollment`. No phantom user is created for invitees who have not registered yet.

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
| STUDENT | `ROLE_STUDENT` | `/api/student/**` and other authenticated APIs except `/api/lecturer/**`, `/api/admin/**` |
| LECTURER | `ROLE_LECTURER` | `/api/lecturer/**` |
| ADMIN | `ROLE_ADMIN` | `/api/admin/**` and lecturer paths (explicit support bypass). Not `/api/student/**` |

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
| POST | `/api/admin/subjects` | Session + CSRF | ADMIN | Academic foundation V1 | `AdminSubjectController` |
| GET | `/api/admin/subjects` | Session | ADMIN | Academic foundation V1 | `AdminSubjectController` |
| GET | `/api/admin/subjects/{subjectId}` | Session | ADMIN | Academic foundation V1 | `AdminSubjectController` |
| PATCH | `/api/admin/subjects/{subjectId}` | Session + CSRF | ADMIN | Academic foundation V1 | `AdminSubjectController` |
| POST | `/api/admin/subjects/{subjectId}/syllabi` | Session + CSRF | ADMIN | Academic foundation V1 | `AdminSubjectController` |
| GET | `/api/admin/subjects/{subjectId}/syllabi` | Session | ADMIN | Academic foundation V1 | `AdminSubjectController` |
| GET | `/api/admin/subjects/{subjectId}/syllabi/{syllabusVersionId}` | Session | ADMIN | Academic foundation V1 | `AdminSubjectController` |
| PATCH | `/api/admin/subjects/{subjectId}/syllabi/{syllabusVersionId}` | Session + CSRF | ADMIN | Academic foundation V1 | `AdminSubjectController` |
| PUT | `/api/admin/subjects/{subjectId}/syllabi/{syllabusVersionId}/structure` | Session + CSRF | ADMIN | Academic foundation V1 | `AdminSubjectController` |
| POST | `/api/admin/subjects/{subjectId}/syllabi/{syllabusVersionId}/publish` | Session + CSRF | ADMIN | Academic foundation V1 | `AdminSubjectController` |
| POST | `/api/admin/subjects/{subjectId}/syllabi/{syllabusVersionId}/archive` | Session + CSRF | ADMIN | Academic foundation V1 | `AdminSubjectController` |
| POST | `/api/admin/semesters` | Session + CSRF | ADMIN | Academic runtime V1 | `AdminSemesterController` |
| GET | `/api/admin/semesters` | Session | ADMIN | Academic runtime V1 | `AdminSemesterController` |
| GET | `/api/admin/semesters/active` | Session | ADMIN | Academic runtime V1 | `AdminSemesterController` |
| PUT | `/api/admin/semesters/active` | Session + CSRF | ADMIN | Academic runtime V1 | `AdminSemesterController` |
| GET | `/api/admin/semesters/{semesterId}` | Session | ADMIN | Academic runtime V1 | `AdminSemesterController` |
| PATCH | `/api/admin/semesters/{semesterId}` | Session + CSRF | ADMIN | Academic runtime V1 | `AdminSemesterController` |
| POST | `/api/admin/classes` | Session + CSRF | ADMIN | Academic runtime V1 | `AdminAcademicClassController` |
| GET | `/api/admin/classes` | Session | ADMIN | Academic runtime V1 | `AdminAcademicClassController` |
| GET | `/api/admin/classes/{classId}` | Session | ADMIN | Academic runtime V1 | `AdminAcademicClassController` |
| PATCH | `/api/admin/classes/{classId}` | Session + CSRF | ADMIN | Academic runtime V1 | `AdminAcademicClassController` |
| POST | `/api/admin/courses` | Session + CSRF | ADMIN | Academic runtime V1 | `AdminCourseController` |
| GET | `/api/admin/courses` | Session | ADMIN | Academic runtime V1 | `AdminCourseController` |
| GET | `/api/admin/courses/{courseId}` | Session | ADMIN | Academic runtime V1 | `AdminCourseController` |
| PATCH | `/api/admin/courses/{courseId}` | Session + CSRF | ADMIN | Academic runtime V1 | `AdminCourseController` |
| GET | `/api/admin/courses/{courseId}/roster/template` | Session | ADMIN | Course roster V1 | `AdminCourseRosterController` |
| GET | `/api/admin/courses/{courseId}/roster` | Session | ADMIN | Course roster V1 | `AdminCourseRosterController` |
| POST | `/api/admin/courses/{courseId}/roster/import/preview` | Session + CSRF | ADMIN | Course roster V1 | `AdminCourseRosterController` |
| POST | `/api/admin/courses/{courseId}/roster/import/confirm` | Session + CSRF | ADMIN | Course roster V1 | `AdminCourseRosterController` |
| POST | `/api/admin/dev/email-test` | Session + CSRF | ADMIN | Email delivery V1, **local/dev only** | `AdminDevEmailController` |
| GET | `/api/lecturer/courses` | Session | LECTURER or ADMIN | Lecturer Team V1 | `LecturerCourseController` |
| GET | `/api/lecturer/courses/{courseId}` | Session | LECTURER (assigned) or ADMIN | Lecturer Team V1 | `LecturerCourseController` |
| GET | `/api/lecturer/courses/{courseId}/roster` | Session | LECTURER (assigned) or ADMIN | Lecturer Team V1 | `LecturerCourseController` |
| GET | `/api/lecturer/courses/{courseId}/teams/template` | Session | LECTURER (assigned) or ADMIN | Lecturer Team V1 | `LecturerTeamController` |
| POST | `/api/lecturer/courses/{courseId}/teams/import/preview` | Session + CSRF | LECTURER (assigned) or ADMIN | Lecturer Team V1 | `LecturerTeamController` |
| POST | `/api/lecturer/courses/{courseId}/teams/import/confirm` | Session + CSRF | LECTURER (assigned) or ADMIN | Lecturer Team V1 | `LecturerTeamController` |
| GET | `/api/lecturer/courses/{courseId}/teams` | Session | LECTURER (assigned) or ADMIN | Lecturer Team V1 | `LecturerTeamController` |
| GET | `/api/student/courses/{courseId}/team` | Session | STUDENT | Lecturer Team V1 | `StudentCourseTeamController` |

---

## 11. Admin Subject + versioned syllabus (Academic Foundation V1)

ADMIN-only. Session cookie + CSRF on writes. Subject is catalog master data, **not** a course offering and **not** bound to a semester.

Create subject:

```json
POST /api/admin/subjects
{
  "code": "SWP391",
  "nameEnglish": "Software Development Project",
  "nameVietnamese": "Dự án phát triển phần mềm"
}
```

`code` is trimmed and uppercased. Duplicate code → `SUBJECT_CODE_DUPLICATE` (409). Status is `ACTIVE` / `INACTIVE` catalog lifecycle (not deletion). Legacy `deleted_at` is separate; a deleted subject cannot be `ACTIVE`. List query params: `code`, `status`, `q`.

Create syllabus version (always `DRAFT`):

```json
POST /api/admin/subjects/{subjectId}/syllabi
{
  "externalSyllabusId": "14177",
  "versionLabel": "2026-v1",
  "credits": 3,
  "level": "Bachelor",
  "description": "...",
  "textbooks": "Sommerville, Software Engineering",
  "referenceMaterials": "IEEE 829"
}
```

`versionLabel` is unique per subject. `PATCH` metadata and `PUT .../structure` are allowed only while `DRAFT`. `PUT .../structure` replaces learning outcomes, learning units, phases, activities, deliverables, and outcome mappings in one transaction. Invalid rows roll back the whole update. `textbooks` and `referenceMaterials` are optional snapshot text on the syllabus version, not a materials catalog.

Replace structure (atomic):

```json
PUT /api/admin/subjects/{subjectId}/syllabi/{syllabusVersionId}/structure
{
  "learningOutcomes": [
    { "code": "LO2", "name": "Design appropriate test cases", "orderIndex": 2 }
  ],
  "learningUnits": [
    {
      "code": "BLACK_BOX_TESTING",
      "name": "Black-box Testing",
      "description": "Design test cases without internals",
      "orderIndex": 1,
      "learningOutcomeCodes": ["LO2"]
    }
  ],
  "phases": [
    {
      "code": "TEST_DESIGN",
      "name": "Test Design",
      "orderIndex": 1,
      "learningOutcomeCodes": ["LO2"],
      "activities": [],
      "deliverables": []
    }
  ]
}
```

Learning outcomes are **what the learner is expected to achieve**. Learning units are **what is taught**. Phases are **how/when project or learning work progresses**. They are independent. Unknown `learningOutcomeCodes` on a unit, phase, or deliverable reject the entire transaction (`LEARNING_OUTCOME_REFERENCE_INVALID`).

Publish (`POST .../publish`) requires at least one learning outcome and one phase, unique codes, `orderIndex > 0`, and valid outcome references. Learning units are optional at publish. After publish the version is immutable (`SYLLABUS_PUBLISHED_IMMUTABLE`). Archive (`POST .../archive`) is for `PUBLISHED` only; archived versions stay readable.

GET syllabus detail returns ordered outcomes, learning units, phases, per-phase activities/deliverables, and learning-outcome code mappings. Course pins a published syllabus version; Graph/Neo4j is not part of this contract.

Typed errors use `{ "code", "message" }`. Common codes: `SUBJECT_NOT_FOUND`, `SUBJECT_CODE_DUPLICATE`, `SUBJECT_STATUS_INVALID`, `SYLLABUS_NOT_FOUND`, `SYLLABUS_NOT_DRAFT`, `SYLLABUS_PUBLISHED_IMMUTABLE`, `SYLLABUS_PUBLISH_INVALID`, `ACADEMIC_CODE_DUPLICATE`, `LEARNING_OUTCOME_REFERENCE_INVALID`, `ORDER_INDEX_INVALID`.

---

## 12. Admin academic runtime (Semester / Class / Course)

ADMIN-only. Session cookie + CSRF on writes. Subject and syllabus remain semester-independent catalog. Academic class belongs to a semester. Course is the teaching offering.

Active semester is a platform singleton setting (`GET`/`PUT /api/admin/semesters/active`), not a status on the semester row. `PUT` body: `{ "semesterId": "<uuid>" }`. Missing setting returns JSON `null`.

Create semester (`code` trim+uppercase, unique; `startDate` must be before `endDate`):

```json
POST /api/admin/semesters
{
  "code": "FA26",
  "name": "Fall 2026",
  "startDate": "2026-09-01",
  "endDate": "2026-12-31"
}
```

Create class (uniqueness is per semester: `(semesterId, classCode)`):

```json
POST /api/admin/classes
{
  "semesterId": "...",
  "classCode": "SE1705",
  "name": "SE1705"
}
```

`GET /api/admin/classes?semesterId=` filters by semester.

Create course. Pins a **PUBLISHED** syllabus of the same subject. Lecturer must be an active `lecturer_profile` with role `LECTURER`. Duplicate `(academicClassId, subjectId)` → `COURSE_DUPLICATE` (409).

```json
POST /api/admin/courses
{
  "academicClassId": "...",
  "subjectId": "...",
  "syllabusVersionId": "...",
  "lecturerId": "..."
}
```

Optional `courseCode`, `name` (default `{subjectCode} · {classCode}`). List filters: `semesterId`, `academicClassId`, `subjectId`, `lecturerId`. GET returns class, semester, subject, syllabus pin, and lecturer.

`PATCH /api/admin/courses/{courseId}` may change name, courseCode, lecturer (`COURSE_LECTURER_CHANGED`), or syllabus pin. Syllabus change is rejected with `COURSE_SYLLABUS_IMMUTABLE` if enrollments or projects exist. DRAFT → `COURSE_SYLLABUS_NOT_PUBLISHED`. ARCHIVED → `COURSE_SYLLABUS_ARCHIVED`. Wrong subject → `COURSE_SYLLABUS_SUBJECT_MISMATCH`. Inactive subject → `SUBJECT_STATUS_INVALID`.

No DELETE in this contract. Team and Graph are out of scope. Course roster import is section 14.

---

## 13. Email delivery smoke (local/dev only)

Transactional mail is enqueue-only. Business services must not call the mail provider. A scheduled worker claims `email_outbox` rows and sends through `EmailSender`. Runtime provider is `SAGA_MAIL_PROVIDER` (`smtp` or `gmail-api`); the same build works in every environment.

`POST /api/admin/dev/email-test` exists **only** when the `local` or `dev` profile is active. ADMIN + session + CSRF. It is not registered in test/production profiles.

```json
POST /api/admin/dev/email-test
{
  "to": "test@example.com"
}
```

The handler enqueues `DEV_SMOKE`, runs the worker once, and returns `{ outboxId, deliveryStatus, attemptCount, lastFailureCode, sentAt, mailEnabled }`.

`SENT` requires `SAGA_MAIL_ENABLED=true`, `SAGA_MAIL_FROM`, and credentials for the selected provider only. `SAGA_MAIL_PROVIDER=smtp` needs `MAIL_HOST` (and SMTP username/password). `SAGA_MAIL_PROVIDER=gmail-api` needs `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`, and `GMAIL_REFRESH_TOKEN` — SMTP connectivity is not required. If mail is disabled the row stays `PENDING` and `mailEnabled` is `false`.

Leave `SAGA_MAIL_SMTP_HEALTH_ENABLED` unset/false so `/actuator/health` does not depend on SMTP.

Do not use this endpoint as a public mail API. Forgot-password is not implemented. Team assignment mail uses template `team-assigned` / type `TEAM_ASSIGNED` via the same outbox worker.

---

## 14. Admin course roster V1

ADMIN-only. Session cookie + CSRF on writes. Target Course is always the `{courseId}` path — Excel cannot redirect import into another course.

Flow: download template → fill workbook → preview (no durable mutation) → confirm atomically.

### XLSX contract

`GET /api/admin/courses/{courseId}/roster/template` returns `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.

Import sheet name: `Danh_Sach_SV`.

Visible columns (exact):

| A | B | C | D | E | F |
| --- | --- | --- | --- | --- | --- |
| No | Class | FullName | StudentCode | Email | MemberCode |

`No` is display-only. `Class` must equal the Course `academic_class.classCode`. `MemberCode` is preview-only; there is no persistence column.

CSV and non-XLSX are rejected. Default size limit 2MB (`saga.roster.max-file-bytes`).

### Preview

`POST /api/admin/courses/{courseId}/roster/import/preview` multipart field `file`. Does not write enrollment/invitation rows.

Response includes `previewToken` (Redis/Valkey, ~15 minutes, bound to this Admin + courseId, consumed after successful confirm).

Row `action`: `READY_ENROLL` | `READY_INVITE` | `ALREADY_ENROLLED` | `ALREADY_INVITED` | `INVALID` | `CONFLICT`.

Confirm is rejected while any row is `INVALID` or `CONFLICT`.

### Confirm

```json
POST /api/admin/courses/{courseId}/roster/import/confirm
{ "previewToken": "..." }
```

Atomic MySQL transaction:

- existing STUDENT account → `course_enrollment` ACTIVE (reactivate WITHDRAWN/COMPLETED if the unique row already exists)
- no account → `student_course_invitation` PENDING (email + studentCode + fullName; **no** `user_account`)
- same course+email or course+studentCode never inserts a second invitation: PENDING/SENT stay outstanding; FAILED/CANCELLED reactivate the same row; CLAIMED + existing Student uses enrollment
- emails enqueued to `email_outbox` only when state actually changes: `COURSE_ENROLLED`, `COURSE_INVITATION`

Institutional FPT/FE invitation text tells the student to use Google onboarding, not local password registration. Personal emails use the public Student registration flow.

### Read

`GET /api/admin/courses/{courseId}/roster` returns enrollments and PENDING invitations. A pending invitee has `accountState=NOT_REGISTERED` without a fake account.

### Claim

After successful Student register, local login, or Google STUDENT onboarding, matching PENDING invitations (email + StudentCode, case-insensitive) become CLAIMED and create/activate ACTIVE enrollment. Idempotent. Claim failure must not fail login.

Manual Admin add-student HTTP API is deferred; the same `CourseRosterService` apply path is ready for it.

Errors: `ROSTER_FILE_INVALID`, `ROSTER_FILE_TOO_LARGE`, `ROSTER_PREVIEW_INVALID`, `ROSTER_PREVIEW_EXPIRED`, `ROSTER_PREVIEW_MISMATCH`, `ROSTER_CONFIRM_BLOCKED`.

---

## 15. Lecturer Team Management V1

Lecturer (or ADMIN support) session. CSRF on writes. A Lecturer may only operate on courses where `course.instructor.userAccount.id` equals the authenticated user. Unassigned course or another lecturer's course → `LECTURER_COURSE_FORBIDDEN` (403). ADMIN is an explicit bypass and is not resolved as `LecturerProfile`. `GET /api/lecturer/courses` returns assigned courses for a Lecturer, and **all** non-deleted courses for ADMIN (explicit support list, not a null-filter accident).

Lecturers cannot create courses or enroll students. Admin Course Roster remains the enrollment authority.

### Course + ACTIVE roster

`GET /api/lecturer/courses` and `GET /api/lecturer/courses/{courseId}` return the existing `CourseResponse` shape (course/subject/class/semester/syllabus/lecturer fields).

`GET /api/lecturer/courses/{courseId}/roster` returns **ACTIVE** `course_enrollment` rows only. No invitations, `WITHDRAWN`, or `COMPLETED`.

```json
{
  "courseId": "...",
  "classCode": "SE1705",
  "enrolledCount": 2,
  "entries": [
    {
      "courseEnrollmentId": "...",
      "studentProfileId": "...",
      "studentCode": "SE111111",
      "fullName": "Alpha Student",
      "email": "alpha@gmail.com",
      "classCode": "SE1705"
    }
  ]
}
```

### XLSX contract

`GET /api/lecturer/courses/{courseId}/teams/template` returns `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.

Sheet: `Team_Assignment`. Columns: `No, Class, FullName, StudentCode, Email, TeamNo, TeamName, TeamRole`.

Identity columns are generated from the ACTIVE roster. Existing memberships are pre-filled. `TeamRole` dropdown: `Leader`, `Member`. `MENTOR` is not valid Excel input. `TeamNo` is a positive integer and the canonical team identity in the course. Rows with the same `TeamNo` must share the same `TeamName`. Duplicate names on different `TeamNo` values are a V1 preview CONFLICT (not a DB unique).

The workbook is a complete desired state: every currently ACTIVE enrollment must appear exactly once. Omitted ACTIVE students block confirm. Identity fields are not authority — the backend resolves `StudentCode` to the ACTIVE enrollment and rejects edited name/email/class.

CSV / non-XLSX rejected. Size limit follows `saga.roster.max-file-bytes` (default 2MB). TTL follows `saga.roster.preview-ttl` (default 15m).

### Preview / confirm

Multipart field name: `file`.

`POST /api/lecturer/courses/{courseId}/teams/import/preview` does not write `team` / `team_member`. Redis key `saga:team:preview:{token}`, bound to actor user id + course id, consumed after successful confirm. Wrong actor/course → `TEAM_PREVIEW_MISMATCH` (403). Missing/expired → `TEAM_PREVIEW_EXPIRED`.

Row `action`: `READY_CREATE` | `READY_ASSIGN` | `READY_REASSIGN` | `ALREADY_ASSIGNED` | `INVALID` | `CONFLICT`.

`hasBlockingErrors` is true when any row is `INVALID`/`CONFLICT` or an ACTIVE student is omitted.

```json
POST /api/lecturer/courses/{courseId}/teams/import/confirm
{ "previewToken": "..." }
```

Atomic transaction: create/update `team` by `(course_id, team_no)` (no dummy `project` rows; `project_id` stays null), upsert exactly one `team_member` per enrollment (create / move / role update). Each participating team must have exactly one ACTIVE `LEADER`. Identical re-upload is idempotent (`unchanged`, no extra `TEAM_ASSIGNED` mail).

Confirm summary: `createdTeams`, `updatedTeams`, `assignedMembers`, `reassignedMembers`, `updatedRoles`, `unchanged`, `emailsEnqueued`.

`GET /api/lecturer/courses/{courseId}/teams` returns teams ordered by `teamNo`, members LEADER first then `studentCode`. `projectId` is nullable.

### Student my-team

`GET /api/student/courses/{courseId}/team` is STUDENT-only. Requires ACTIVE enrollment in that course, otherwise `STUDENT_COURSE_FORBIDDEN` (403). No membership → `TEAM_NOT_FOUND` (404). Identity always comes from the session. Member emails are not returned.

```json
{
  "teamId": "...",
  "teamNo": 1,
  "teamName": "Alpha",
  "myRole": "LEADER",
  "members": [
    { "studentCode": "SE111111", "fullName": "Alpha Student", "role": "LEADER" }
  ]
}
```

### Mail

Changed memberships (new assign, reassignment, role change) enqueue `email_type=TEAM_ASSIGNED` / `template_key=team-assigned` inside the confirm transaction. The outbox worker sends later through the runtime `EmailSender` (`SAGA_MAIL_PROVIDER`). Unchanged rows do not enqueue.

Errors: `LECTURER_COURSE_FORBIDDEN`, `STUDENT_COURSE_FORBIDDEN`, `TEAM_FILE_INVALID`, `TEAM_FILE_TOO_LARGE`, `TEAM_PREVIEW_INVALID`, `TEAM_PREVIEW_EXPIRED`, `TEAM_PREVIEW_MISMATCH`, `TEAM_CONFIRM_BLOCKED`, `TEAM_NOT_FOUND`, `TEAM_LEADER_INVALID`.

