# SAGA — Hướng dẫn tích hợp API cho Frontend

> Tài liệu này được viết lại từ đầu, kiểm chứng trực tiếp trên source code backend hiện tại (không viết theo trí nhớ). Mọi route, DTO, mã lỗi đều có thể tra ngược lại file/class tương ứng trong repo `saga-be`. Nếu một mục không thể xác minh từ source, tài liệu sẽ ghi rõ: *"Không tìm thấy contract này trong backend hiện tại."*

## Mục lục

1. [Mục đích tài liệu](#1-mục-đích-tài-liệu)
2. [Environment / Base URL](#2-environment--base-url)
3. [Session + Cookie + CSRF — RẤT QUAN TRỌNG](#3-session--cookie--csrf--rất-quan-trọng)
4. [Current User / Auth State](#4-current-user--auth-state)
5. [Local Login / Logout](#5-local-login--logout)
6. [Google Login](#6-google-login)
7. [Forgot Password / Reset Password](#7-forgot-password--reset-password)
8. [Current User Profile](#8-current-user-profile)
9. [Role Model](#9-role-model)
10. [Admin — Subject / Syllabus / Semester / Class / Course](#10-admin--subject--syllabus--semester--class--course)
11. [Admin — Course Roster](#11-admin--course-roster)
12. [Remove Student — Chi tiết FE Flow](#12-remove-student--chi-tiết-fe-flow)
13. [Lecturer — Course / Team Management](#13-lecturer--course--team-management)
14. [Student — Course / Team](#14-student--course--team)
15. [Project Creation / Project Access](#15-project-creation--project-access)
16. [Jira Integration — FE Flow đầy đủ](#16-jira-integration--fe-flow-đầy-đủ)
17. [GitHub Integration — FE Flow đầy đủ](#17-github-integration--fe-flow-đầy-đủ)
18. [Project Integration Status](#18-project-integration-status)
19. [Task API — CRUD đầy đủ](#19-task-api--crud-đầy-đủ)
20. [Task Options — Form Building](#20-task-options--form-building)
21. [Task Status / Transitions](#21-task-status--transitions)
22. [Story Point](#22-story-point)
23. [Assignee](#23-assignee)
24. [Sprint CRUD](#24-sprint-crud)
25. [GitHub Commits / Task-Commit Links](#25-github-commits--task-commit-links)
26. [Project Sync](#26-project-sync)
27. [SSE Realtime](#27-sse-realtime)
28. [Contribution / Evidence APIs](#28-contribution--evidence-apis)
29. [Error Handling](#29-error-handling)
30. [Frontend Query Invalidation Strategy](#30-frontend-query-invalidation-strategy)
31. [Complete User Flows](#31-complete-user-flows)
32. [Các lỗi FE dễ mắc](#32-các-lỗi-fe-dễ-mắc)
33. [Copy-paste Code Examples](#33-copy-paste-code-examples)
34. [Swagger / Manual Testing](#34-swagger--manual-testing)
35. [Final Endpoint Index](#35-final-endpoint-index)

---

## 1. Mục đích tài liệu

Tài liệu này là **nguồn sự thật (source of truth)** để đội Frontend tích hợp với backend SAGA. Nó được tạo lại bằng cách đọc trực tiếp source code hiện tại (controllers, DTOs, enum lỗi, `SecurityConfig`), **không** viết theo trí nhớ hay đoán.

Swagger UI (`/swagger-ui.html`) vẫn hữu ích để thử nghiệm thủ công từng endpoint, nhưng nó **không** giải thích được:
- Thứ tự gọi API nào trước, API nào sau trong một luồng nghiệp vụ (vd: phải tạo Semester trước AcademicClass, phải chọn Team Leader trước khi tạo Project...).
- Khi nào FE cần refetch, khi nào có SSE tự báo thay đổi.
- Ý nghĩa nghiệp vụ của một mã lỗi 409.

Tài liệu này giải quyết đúng phần đó — **luồng ứng dụng và thứ tự gọi API**, bổ sung cho Swagger chứ không thay thế.

---

## 2. Environment / Base URL

Backend không hard-code base URL trong source đã commit — mọi giá trị đều đọc từ biến môi trường (`${VAR:default}`), khớp với cách triển khai trên Railway.

**Giá trị mặc định khi chạy local** (từ `application.properties`, dùng khi không có biến môi trường override):
- Backend: `http://localhost:8080` (cổng mặc định Spring Boot)
- Frontend (dùng làm đích redirect mặc định của Google OAuth, Jira/GitHub OAuth): `http://localhost:3000`

**Về URL production**: `https://saga-be-production.up.railway.app` **chỉ** xuất hiện trong file `.env` cục bộ (bị `.gitignore` bỏ qua, không nằm trong repo) và trong vài file test làm giá trị giả lập. **Không tìm thấy URL production này trong bất kỳ file cấu hình nào đã commit vào repo.** → FE **không nên hard-code** URL này; hãy cấu hình base URL qua biến môi trường của FE (`VITE_API_BASE_URL`, `NEXT_PUBLIC_API_BASE_URL`, ... tuỳ framework) và xác nhận giá trị chính xác với người phụ trách deploy/Railway.

**Bắt buộc**: mọi request gọi API phải gửi kèm cookie (session-based auth, không dùng Bearer token):

```ts
// fetch
fetch(url, { credentials: "include", ... })

// axios
axios.create({ baseURL: API_BASE_URL, withCredentials: true })
```

Thiếu `credentials: "include"` / `withCredentials: true` → cookie `SAGA_SESSION` và `XSRF-TOKEN` sẽ không được gửi/nhận đúng cách, dẫn đến lỗi 401/403 khó hiểu.

---

## 3. Session + Cookie + CSRF — RẤT QUAN TRỌNG

Backend dùng **session cookie**, không dùng JWT/Bearer token.

### 3.1. Hai cookie quan trọng

| Cookie | HttpOnly | Mục đích | FE có được đọc không? |
|---|---|---|---|
| `SAGA_SESSION` | `true` | Cookie session (Spring Session), server tự quản lý | **KHÔNG** — trình duyệt tự đính kèm, JS không đọc được và cũng không cần đọc |
| `XSRF-TOKEN` | `false` | Token chống CSRF | **CÓ** — JS phải đọc giá trị này để gửi lại trong header |

Cờ `Secure`/`SameSite` của cả hai cookie đi theo cấu hình `saga.auth.cookie.*`: môi trường local mặc định `Secure=false, SameSite=Lax`; môi trường deploy (`dev` profile) là `Secure=true, SameSite=None` (vì FE/BE khác origin qua HTTPS).

### 3.2. Cơ chế CSRF

- Cookie CSRF: **`XSRF-TOKEN`**
- Header CSRF phải gửi lại: **`X-XSRF-TOKEN`**
- Endpoint cấp token: `GET /api/auth/csrf` → trả về `{ "parameterName": ..., "token": "...", "headerName": "X-XSRF-TOKEN" }` và set cookie `XSRF-TOKEN`.

**CSRF được bỏ qua (ignore) chỉ cho các path sau** (theo `SecurityConfig`):
```
/login/oauth2/**
/oauth2/**
/api/webhooks/github
/api/webhooks/jira
```
Tất cả các API khác — **kể cả các API "public" như `/api/auth/login`, `/api/auth/register`, `/api/auth/logout`** — đều yêu cầu CSRF nếu là method thay đổi dữ liệu (POST/PUT/PATCH/DELETE). Các method GET không cần CSRF.

### 3.3. Trình tự chuẩn cho một request có thay đổi dữ liệu

```
1. GET /api/auth/csrf                     → trình duyệt nhận cookie XSRF-TOKEN
2. Đọc giá trị cookie XSRF-TOKEN bằng JS
3. POST/PATCH/PUT/DELETE ... 
   headers: { "X-XSRF-TOKEN": "<giá trị cookie>" }
   credentials: "include"
```

Trong thực tế, FE có thể gọi `GET /api/auth/csrf` một lần khi app khởi động (cùng lúc với `GET /api/auth/me`) — cookie sẽ tồn tại suốt phiên làm việc, không cần gọi lại trước mỗi request trừ khi cookie bị xoá (vd sau `logout`, xem mục 5).

### 3.4. Xử lý lỗi liên quan auth ở tầng FE

Mọi lỗi domain trả về theo format cố định:
```json
{ "code": "MÃ_LỖI", "message": "Mô tả" }
```

| Tình huống | HTTP status | Body ví dụ | FE nên làm gì |
|---|---|---|---|
| Chưa đăng nhập, gọi API cần `authenticated()` (vd `/api/users/me/profile`) | 401 | `{"code":"INVALID_CREDENTIALS","message":"Authentication failed."}` | Điều hướng về trang login |
| Sai vai trò (vd STUDENT gọi `/api/admin/**`) | 403 | `{"code":"ACCESS_DENIED","message":"Access denied."}` | Hiển thị "Không có quyền truy cập" |
| Thiếu/sai CSRF token | 403 | `{"code":"ACCESS_DENIED","message":"Access denied."}` | Gọi lại `GET /api/auth/csrf` rồi thử lại — **không tự động retry vô hạn** |
| Tài khoản Google/Local chưa đặt mật khẩu lần đầu | 403 | `{"code":"PASSWORD_SETUP_REQUIRED","message":"Password setup is required."}` | Điều hướng sang trang đặt mật khẩu — **lưu ý**: khi cờ này bật, backend chặn gần như MỌI API khác (xem mục 6) |

**Lưu ý quan trọng**: khi tài khoản đang ở trạng thái `passwordSetupRequired=true`, một bộ lọc toàn cục (`PasswordSetupEnforcementFilter`) sẽ trả `403 PASSWORD_SETUP_REQUIRED` cho **hầu hết mọi API**, chỉ trừ: `/api/auth/me`, `/api/auth/csrf`, `POST /api/auth/login`, `/register`, `/password/setup`, `/logout`, các path OAuth/webhook, và các path tĩnh (swagger, index.html). Vì vậy sau khi đăng nhập Google lần đầu, FE **phải** hoàn tất `POST /api/auth/password/setup` trước khi gọi bất kỳ API nghiệp vụ nào khác.

---

## 4. Current User / Auth State

### `GET /api/auth/me`
- **Public** (không cần đăng nhập để gọi — dùng để kiểm tra trạng thái).
- Không cần CSRF (GET).

Response — `AuthMeResponse`:
```json
{
  "authenticated": true,
  "passwordSetupRequired": false,
  "user": {
    "id": "uuid",
    "email": "...",
    "username": "...",
    "fullName": "...",
    "avatarUrl": "...",
    "role": "ADMIN | LECTURER | STUDENT"
  }
}
```

Khi chưa đăng nhập:
```json
{ "authenticated": false, "passwordSetupRequired": false, "user": null }
```

### Luồng khởi động app (boot flow) khuyến nghị

```
App load
  → GET /api/auth/csrf     (lấy cookie XSRF-TOKEN, dùng cho các POST sau này)
  → GET /api/auth/me       (biết ngay: đã đăng nhập chưa, role gì, có cần setup password không)
  → nếu authenticated=false        → route sang màn hình Login
  → nếu passwordSetupRequired=true → route sang màn hình đặt mật khẩu (bỏ qua mọi route khác)
  → ngược lại: route theo user.role (ADMIN/LECTURER/STUDENT) vào dashboard tương ứng
```

`GET /api/auth/me` cũng nên được gọi lại (refetch) mỗi khi có khả năng session/thông tin user đã đổi — vd sau khi cập nhật profile (mục 8), sau login/logout.

---

## 5. Local Login / Logout

### `POST /api/auth/login`
- **CSRF bắt buộc.**
- Request:
```json
{ "identifier": "email hoặc username", "password": "..." }
```
`identifier` chứa `@` → backend hiểu là email; ngược lại hiểu là username. **Không** gửi field `role`.
- Response thành công: `AuthMeResponse` giống hệt `GET /api/auth/me` (luôn `authenticated:true`).
- Lỗi:

| Status | code | Ý nghĩa |
|---|---|---|
| 401 | `INVALID_CREDENTIALS` | Sai tài khoản/mật khẩu |
| 403 | `ACCOUNT_DISABLED` | Tài khoản bị khoá |
| 403 | `ACCESS_DENIED` | CSRF sai/thiếu |

### Luồng màn hình Login

```
1. GET /api/auth/csrf (nếu chưa có cookie XSRF-TOKEN)
2. POST /api/auth/login { identifier, password }
3. Thành công → dùng luôn response (đã có user.role) để điều hướng,
   HOẶC gọi lại GET /api/auth/me để chắc chắn có dữ liệu mới nhất.
4. Điều hướng theo role → dashboard ADMIN / LECTURER / STUDENT
```

### `POST /api/auth/logout`
- **CSRF bắt buộc.** Không có request body.
- Response: **204 No Content**.
- Sau khi gọi: session bị huỷ, cookie `SAGA_SESSION` bị xoá, và **backend tự cấp lại một `XSRF-TOKEN` mới ở trạng thái ẩn danh** (để lần đăng nhập kế tiếp không cần gọi lại `GET /api/auth/csrf`).
- FE sau khi logout: xoá state user cục bộ, điều hướng về Login. `GET /api/auth/me` lúc này sẽ trả `authenticated:false`.

---

## 6. Google Login

**Đây là điều hướng trình duyệt (browser navigation), KHÔNG được gọi qua `axios`/`fetch`.**

```
window.location.href = `${API_BASE_URL}/oauth2/authorization/google`
```

Lý do: luồng này thực hiện một chuỗi redirect 302 sang Google rồi quay lại backend — một request XHR/axios không thể "đi theo" chuỗi điều hướng top-level này.

### Luồng đầy đủ

```
1. FE điều hướng trình duyệt: window.location.href = ".../oauth2/authorization/google"
2. Trình duyệt sang trang đăng nhập Google
3. Google redirect về backend (route nội bộ Spring Security, FE không cần biết chi tiết)
4. Backend tạo/khớp tài khoản, thiết lập session, rồi REDIRECT trình duyệt về FE:
   - Thành công, cần đặt mật khẩu lần đầu → passwordSetupUrl (mặc định local: http://localhost:3000/auth/setup-password)
   - Thành công, không cần setup       → successUrl (mặc định local: http://localhost:3000/dashboard)
   - Thất bại                          → failureUrl + query "?error=<GOOGLE_XXX_CODE>"
     (mặc định local: http://localhost:3000/login?error=...)
5. FE tại trang đích gọi GET /api/auth/me để lấy thông tin user/role và điều hướng tiếp
```

Các mã lỗi có thể xuất hiện ở `?error=`: `GOOGLE_EMAIL_NOT_VERIFIED`, `GOOGLE_DOMAIN_NOT_ALLOWED`, `GOOGLE_IDENTITY_CONFLICT`, `GOOGLE_ACCOUNT_NOT_ELIGIBLE` (tất cả thuộc enum `AuthErrorCode`, mục 29).

Đăng ký chỉ dùng domain email cá nhân (Gmail...) — domain thuộc tổ chức (`fpt.edu.vn`, `fe.edu.vn`) **bắt buộc** đăng nhập Google, không cho đăng ký local (`POST /api/auth/register` sẽ trả `INSTITUTIONAL_EMAIL_USE_GOOGLE`).

### Đặt mật khẩu lần đầu (chỉ áp dụng tài khoản Google-linked)

`POST /api/auth/password/setup` — **cần session đã đăng nhập + CSRF**.

Request:
```json
{ "newPassword": "...", "confirmPassword": "..." }
```
Response thành công: `AuthMeResponse` (session được làm mới, `passwordSetupRequired` chuyển thành `false`).

Điều kiện/lỗi:
- Chỉ dành cho `STUDENT`/`LECTURER` có liên kết Google và **chưa** có mật khẩu local — ADMIN không dùng được endpoint này.
- Nếu tài khoản đã có mật khẩu → `409 PASSWORD_ALREADY_SET` (không ghi đè).
- Nếu không đủ điều kiện (không phải Google-linked, hoặc là ADMIN) → `403 GOOGLE_ACCOUNT_NOT_ELIGIBLE`.

---

## 7. Forgot Password / Reset Password

### `POST /api/auth/password/forgot`
- **CSRF bắt buộc.**
- Request: `{ "email": "..." }`
- Response: **luôn** một message chung, **không tiết lộ** email có tồn tại hay không:
```json
{ "message": "Nếu email tồn tại, hướng dẫn đặt lại mật khẩu đã được gửi." }
```

### `POST /api/auth/password/reset`
- **CSRF bắt buộc.**
- Request: `{ "token": "...", "newPassword": "..." }`
- Response thành công:
```json
{ "message": "Mật khẩu đã được cập nhật. Vui lòng đăng nhập lại." }
```
- Lỗi: `400 PASSWORD_RESET_TOKEN_INVALID` (token sai/đã dùng), `400 PASSWORD_RESET_TOKEN_EXPIRED` (hết hạn, mặc định 30 phút), `400 PASSWORD_POLICY_VIOLATION`/`PASSWORD_CONFIRMATION_MISMATCH` (chính sách mật khẩu).

### Luồng FE hoàn chỉnh

```
Trang "Quên mật khẩu"
  → người dùng nhập email
  → POST /api/auth/password/forgot { email }
  → LUÔN hiển thị thông báo chung ("nếu email tồn tại...") — không suy luận tài khoản có tồn tại hay không

Email được gửi chứa link dạng: <FE_URL>/reset-password?token=xxxxx

Trang "Đặt lại mật khẩu"
  → đọc token từ query string
  → nên xoá token khỏi URL/history ngay khi đọc xong (tránh lộ qua lịch sử trình duyệt/log):
      window.history.replaceState({}, "", "/reset-password")
  → người dùng nhập mật khẩu mới
  → POST /api/auth/password/reset { token, newPassword }
  → thành công → điều hướng về trang Login
```

**Không bao giờ** log token ra console/analytics/Sentry ở FE.

---

## 8. Current User Profile

### `GET /api/users/me/profile`
Yêu cầu đã đăng nhập (bất kỳ role). Response — `UserProfileResponse`:
```json
{
  "id": "uuid",
  "email": "...",
  "username": "...",
  "fullName": "...",
  "avatarUrl": "...",
  "role": "STUDENT",
  "accountStatus": "ACTIVE",
  "studentCode": "SE123456"
}
```
`studentCode` chỉ xuất hiện (khác `null`) khi `role == "STUDENT"`.

### `PATCH /api/users/me/profile`
Request — `UpdateProfileRequest` (**chỉ 2 field, không có field nào khác**):
```json
{ "fullName": "Nguyễn Văn A", "avatarUrl": "https://example.com/avatar.png" }
```
- `fullName`: tối đa 255 ký tự. **Field không gửi lên (omit)** = giữ nguyên. Gửi lên nhưng rỗng/toàn khoảng trắng → lỗi `400 PROFILE_FULL_NAME_INVALID`.
- `avatarUrl`: tối đa 500 ký tự. Không gửi = giữ nguyên. Gửi chuỗi rỗng `""` = **xoá avatar** (set về `null`). Gửi giá trị không bắt đầu bằng `http://`/`https://` → lỗi `400 PROFILE_AVATAR_URL_INVALID`.

**Field chỉ đọc (read-only), không có trong request DTO nên không thể sửa bằng bất kỳ cách nào**: `id`, `email`, `username`, `role`, `accountStatus`, `studentCode`. Muốn đổi email/role/mã số sinh viên phải qua nghiệp vụ khác (hiện chưa có API đổi email đã xác thực).

Response: **luôn trả về `UserProfileResponse` đầy đủ, mới nhất** — FE **không cần** gọi thêm `GET` sau khi `PATCH` thành công.

**Rất quan trọng**: sau khi `PATCH` thành công, backend đã tự làm mới session phía server — gọi lại `GET /api/auth/me` ngay sau đó sẽ thấy `fullName`/`avatarUrl` mới ngay lập tức (không bị cache cũ).

---

## 9. Role Model

Có **hai khái niệm vai trò hoàn toàn khác nhau, đừng nhầm lẫn**:

1. **Account Role** (`AccountRole`) — vai trò toàn hệ thống, gắn với tài khoản: `ADMIN`, `LECTURER`, `STUDENT`. Field `role` trong `AuthMeResponse`/`UserProfileResponse`.
2. **Team Role** (`RoleInTeam`) — vai trò trong MỘT nhóm cụ thể của một Course: `LEADER`, `MEMBER`, `MENTOR`. Chỉ áp dụng cho STUDENT, field `myRole` trong response team của Student (mục 14), hoặc `role`/`roleInTeam` trong response team của Lecturer (mục 13).

Một sinh viên (Account Role = STUDENT) có thể là `LEADER` ở team này nhưng chỉ là `MEMBER` ở một nhóm khác (nếu có nhiều course). Việc phân quyền "ai được tạo Project", "ai được sửa Task" dựa vào **Team Role**, không phải Account Role.

---

## 10. Admin — Subject / Syllabus / Semester / Class / Course

Tất cả dưới `/api/admin/**` — chỉ role `ADMIN`.

### Thứ tự tạo dữ liệu bắt buộc (đã xác minh trong `AcademicRuntimeService`)

```
1. Semester           (POST /api/admin/semesters)
2. AcademicClass       (POST /api/admin/classes  — cần semesterId)
3. Subject             (POST /api/admin/subjects — set status ACTIVE)
4. Syllabus            (POST /api/admin/subjects/{subjectId}/syllabi
                         → PUT .../structure (learning outcomes/units/phases)
                         → POST .../publish   — BẮT BUỘC publish trước khi gắn vào Course)
5. Lecturer directory  (đã có sẵn qua GET /api/admin/lecturers — không tạo mới ở đây)
6. Course              (POST /api/admin/courses — cần academicClassId, subjectId,
                         syllabusVersionId ĐÃ PUBLISHED, lecturerId)
```

Nếu gọi tạo Course khi Syllabus chưa `PUBLISHED` → `COURSE_SYLLABUS_NOT_PUBLISHED`. Nếu Syllabus đã `ARCHIVED` → `COURSE_SYLLABUS_ARCHIVED`. Nếu offering (academicClassId + subjectId) đã tồn tại → `409 COURSE_DUPLICATE`.

### Bảng endpoint chính

| Method | Path | Ghi chú |
|---|---|---|
| POST | `/api/admin/subjects` | 201, body: `code, nameEnglish, nameVietnamese` |
| GET | `/api/admin/subjects?code&status&q` | |
| GET/PATCH | `/api/admin/subjects/{subjectId}` | PATCH: partial patch |
| POST | `/api/admin/subjects/{subjectId}/syllabi` | 201 |
| GET/PATCH | `/api/admin/subjects/{subjectId}/syllabi/{syllabusVersionId}` | |
| PUT | `.../syllabi/{syllabusVersionId}/structure` | body: `learningOutcomes[], learningUnits[], phases[]` |
| POST | `.../syllabi/{syllabusVersionId}/publish` | Chuyển `DRAFT` → `PUBLISHED` |
| POST | `.../syllabi/{syllabusVersionId}/archive` | |
| POST | `/api/admin/semesters` | 201, body: `code, name, startDate, endDate` |
| GET | `/api/admin/semesters` | |
| GET/PUT | `/api/admin/semesters/active` | PUT body: `{ semesterId }` — chọn kỳ hiện hành |
| GET/PATCH | `/api/admin/semesters/{semesterId}` | |
| POST | `/api/admin/classes` | 201, body: `semesterId, classCode, name` |
| GET | `/api/admin/classes?semesterId` | |
| GET/PATCH | `/api/admin/classes/{classId}` | |
| POST | `/api/admin/courses` | 201, body: `academicClassId, subjectId, syllabusVersionId, lecturerId, courseCode, name` |
| GET | `/api/admin/courses?semesterId&academicClassId&subjectId&lecturerId` | |
| GET/PATCH | `/api/admin/courses/{courseId}` | PATCH không cho đổi `syllabusVersionId` nếu course đã có enrollment/project (`409 COURSE_SYLLABUS_IMMUTABLE`) |
| GET | `/api/admin/lecturers?active&search` | trả `lecturerProfileId` + `userId` **riêng biệt** — dùng **`lecturerProfileId`** khi gửi `CreateCourseRequest.lecturerId`, KHÔNG dùng `userId` |

---

## 11. Admin — Course Roster

Base: `/api/admin/courses/{courseId}/roster` — ADMIN only.

| Method | Path | Mục đích |
|---|---|---|
| GET | `/template` | Tải file XLSX mẫu |
| GET | `` (gốc) | Danh sách roster (enrollment + invitation) |
| POST | `/import/preview` (multipart `file`) | Đọc & validate file XLSX, **chưa** ghi dữ liệu |
| POST | `/import/confirm` | Áp dụng preview (dùng `previewToken` từ bước trên) |
| POST | `/students` | Thêm/mời 1 sinh viên |
| DELETE | `/enrollments/{enrollmentId}` | Xoá 1 sinh viên đã có tài khoản khỏi lớp |
| DELETE | `/invitations/{invitationId}` | Huỷ lời mời cho sinh viên chưa có tài khoản |

### Phân biệt ENROLLMENT vs INVITATION

Mỗi dòng trong `GET .../roster` là **một trong hai loại**, phân biệt bằng field `kind`:

- **`"ENROLLMENT"`** = sinh viên **đã có tài khoản** trong hệ thống, có `enrollmentId` (không có `invitationId`). Trạng thái nằm ở field `enrollmentStatus`: `ACTIVE | WITHDRAWN | COMPLETED`.
- **`"INVITATION"`** = sinh viên **chưa có tài khoản** — hệ thống chỉ lưu lời mời chờ họ đăng ký, có `invitationId` (không có `enrollmentId`). Trạng thái ở field `invitationStatus`: `PENDING | SENT | FAILED | CANCELLED | CLAIMED`.

```json
// CourseRosterEntryResponse
{
  "kind": "ENROLLMENT",
  "enrollmentId": "uuid-hoặc-null",
  "invitationId": "uuid-hoặc-null",
  "studentUserId": "uuid-hoặc-null",
  "studentCode": "...", "fullName": "...", "email": "...",
  "enrollmentStatus": "ACTIVE",
  "invitationStatus": null,
  "accountState": "REGISTERED"
}
```

**Dùng đúng ID theo `kind` khi xoá** — dùng nhầm sẽ được xử lý như "không tìm thấy" (không có endpoint dùng chung một ID cho cả hai loại).

---

## 12. Remove Student — Chi tiết FE Flow

### Bước 1: xác định loại dòng cần xoá

```
GET /api/admin/courses/{courseId}/roster
→ mỗi dòng: nếu entry.kind === "ENROLLMENT" → dùng entry.enrollmentId
            nếu entry.kind === "INVITATION" → dùng entry.invitationId
```

### Bước 2a: xoá ENROLLMENT (sinh viên đã có tài khoản)

```
DELETE /api/admin/courses/{courseId}/roster/enrollments/{enrollmentId}
```
- **Thành công (200)**: trả về `CourseRosterEntryResponse` với `enrollmentStatus: "WITHDRAWN"`. Đây **không phải** xoá cứng — bản ghi vẫn còn, chỉ đổi trạng thái. Tài khoản (`UserAccount`)/hồ sơ (`StudentProfile`) và toàn bộ lịch sử (task, commit, work session, contribution confirmation...) **không bị ảnh hưởng**.
- **409 `TEAM_LEADER_REMOVAL_REQUIRES_REASSIGNMENT`**: sinh viên đang là **Trưởng nhóm (LEADER)** đang hoạt động của một Team trong course này. Backend **từ chối hoàn toàn thao tác** (không có gì bị thay đổi). FE nên hiển thị:
  > "Sinh viên đang là trưởng nhóm. Hãy chuyển quyền trưởng nhóm cho thành viên khác trước khi xóa sinh viên khỏi lớp."

  **Không tự động retry.** Quy trình đúng: Lecturer vào màn hình quản lý Team (mục 13) → dùng chức năng "Đổi trưởng nhóm" (`PUT /api/lecturer/courses/{courseId}/teams/{teamId}/leader`) để chuyển leader sang người khác → Admin quay lại bấm xoá lần nữa.
- **409 `ROSTER_STUDENT_ALREADY_REMOVED`**: đã xoá trước đó rồi (enrollment không còn `ACTIVE`). FE nên refetch danh sách roster để đồng bộ UI thay vì báo lỗi khó hiểu.
- **404 `ROSTER_STUDENT_NOT_FOUND`**: sai ID hoặc ID không thuộc `courseId` này.

Sau khi xoá thành công: sinh viên **mất quyền truy cập Team/Project** của course đó ngay lập tức (kiểm tra quyền phía backend dựa trên `enrollmentStatus == ACTIVE`), nhưng **lịch sử/bằng chứng đóng góp vẫn giữ nguyên** ở backend cho mục đích chấm điểm/kiểm toán.

### Bước 2b: huỷ INVITATION (sinh viên chưa có tài khoản)

```
DELETE /api/admin/courses/{courseId}/roster/invitations/{invitationId}
```
- Thành công (200): `invitationStatus: "CANCELLED"`. Dòng invitation vẫn giữ trong DB nhưng **không còn tính là thành viên đang chờ** (`GET roster` sẽ không hiển thị nữa vì chỉ hiển thị `PENDING`/`SENT`).
- 409 `ROSTER_STUDENT_ALREADY_REMOVED`: đã huỷ/đã claim trước đó.
- Nếu người này sau đó tự đăng ký tài khoản (register) bằng đúng email/mã số sinh viên đã bị huỷ mời → **KHÔNG** tự động kích hoạt lại lời mời đã huỷ (invitation `CANCELLED` không được coi là hợp lệ để tự động claim).

### Thêm lại sinh viên (re-add) sau khi đã xoá

Gọi lại **API thêm sinh viên bình thường**:
```
POST /api/admin/courses/{courseId}/roster/students
{ "fullName": "...", "studentCode": "...", "email": "...", "memberCode": "..." (tuỳ chọn) }
```
- Nếu trước đó là **ENROLLMENT bị WITHDRAWN**: backend tái sử dụng lại đúng bản ghi enrollment cũ, chuyển về `ACTIVE` — không tạo bản ghi trùng (ràng buộc unique `student_profile_id + course_id` không cho phép trùng).
- Nếu trước đó là **INVITATION bị CANCELLED**: backend tái sử dụng lại đúng bản ghi invitation cũ, chuyển về `PENDING` và gửi lại email mời.
- **Về vai trò/nhóm cũ**: việc thêm lại chỉ khôi phục **trạng thái ghi danh (enrollment)**, sinh viên sẽ **không** tự động quay lại nhóm/Team cũ mà họ từng ở trước khi bị xoá — Lecturer cần gán lại nhóm cho họ thủ công nếu cần (qua màn hình quản lý Team, mục 13).

---

## 13. Lecturer — Course / Team Management

Base: `/api/lecturer/courses/{courseId}` — role `LECTURER` hoặc `ADMIN`.

**Quan trọng: Lecturer KHÔNG tạo Project.** Việc tạo Project chỉ do STUDENT giữ vai trò Team Leader thực hiện (mục 15).

| Method | Path | Mục đích |
|---|---|---|
| GET | `/api/lecturer/courses` | Danh sách course được phân công cho lecturer đang đăng nhập |
| GET | `/api/lecturer/courses/{courseId}` | Chi tiết 1 course |
| GET | `/api/lecturer/courses/{courseId}/roster` | Danh sách sinh viên **ACTIVE** (chỉ đọc, không thao tác được ở đây) |
| GET | `/api/lecturer/courses/{courseId}/teams` | Danh sách team + thành viên |
| GET | `/api/lecturer/courses/{courseId}/teams/template` | Tải XLSX mẫu chia nhóm |
| POST | `/api/lecturer/courses/{courseId}/teams/import/preview` (multipart `file`) | Xem trước kết quả chia nhóm từ file |
| POST | `/api/lecturer/courses/{courseId}/teams/import/confirm` | Áp dụng kết quả chia nhóm |
| PUT | `/api/lecturer/courses/{courseId}/teams/{teamId}/leader` | **Đổi trưởng nhóm** — body: `{ "teamMemberId": "uuid" }` |
| PATCH | `/api/lecturer/courses/{courseId}/team-members/{teamMemberId}/team` | Chuyển 1 thành viên sang team khác — body: `{ "targetTeamId": "uuid" }` |

### Bất biến bắt buộc: mỗi team đang hoạt động phải có đúng 1 LEADER

Backend enforce nghiêm ngặt (`assertLeadershipForOccupiedTeams`): nếu một team có ≥1 thành viên `ACTIVE` mà số lượng `LEADER` khác 1 → `409 TEAM_LEADER_INVALID`. Cụ thể:
- Không cho di chuyển Leader duy nhất sang team khác nếu chưa chỉ định người thay thế trước.
- Không cho chuyển thành viên vào một team đang không có Leader active nào.

FE nên thiết kế UI "Đổi trưởng nhóm"/"Chuyển thành viên" xử lý rõ lỗi 409 này thay vì coi là lỗi hệ thống chung chung.

---

## 14. Student — Course / Team

Base: `/api/student/**` — role `STUDENT` only.

### `GET /api/student/courses`
Danh sách các course sinh viên **đang** ghi danh ACTIVE (course đã bị withdraw sẽ **không** hiện ở đây nữa — khớp đúng với hành vi xoá ở mục 12).

Mỗi phần tử:
```json
{
  "courseId": "...", "courseCode": "...", "subjectCode": "...", "subjectName": "...",
  "classCode": "...", "semesterCode": "...", "semesterName": "...",
  "enrollmentStatus": "ACTIVE",
  "teamId": null,       // null cho tới khi Lecturer gán nhóm
  "teamNo": null,
  "teamName": null,
  "projectId": null     // null cho tới khi Team Leader tạo Project
}
```

### `GET /api/student/courses/{courseId}/team`
```json
{
  "teamId": "...", "teamNo": 1, "teamName": "Team 1",
  "myRole": "LEADER",   // hoặc "MEMBER" | "MENTOR" — vai trò CỦA CHÍNH sinh viên đang gọi API
  "projectId": "uuid-hoặc-null",
  "members": [{ "studentCode": "...", "fullName": "...", "role": "LEADER" }, ...]
}
```
Dùng `myRole` để quyết định UI: hiện nút "Tạo Project"/"Cấu hình tích hợp" chỉ khi `myRole === "LEADER"`.

- `403 STUDENT_COURSE_FORBIDDEN`: chưa ghi danh ACTIVE course này.
- `404 TEAM_NOT_FOUND`: đã ghi danh nhưng Lecturer chưa gán nhóm.

---

## 15. Project Creation / Project Access

**QUY TẮC NGHIỆP VỤ QUAN TRỌNG NHẤT của phần này** (đã xác minh trong code, không phải giả định):

> **Chỉ STUDENT giữ vai trò Team Leader (`myRole === "LEADER"`) mới được tạo Project.** Lecturer không tạo Project. Thành viên thường (MEMBER/MENTOR) không tạo Project.

### Tạo Project — `POST /api/student/courses/{courseId}/project` (STUDENT only)
```json
// request — CreateStudentProjectRequest
{ "name": "...", "projectTypeId": "uuid-tuỳ-chọn", "description": "tuỳ-chọn" }
```
- Nếu người gọi không phải Leader của team → `403 NOT_TEAM_LEADER`.
- Nếu team đã có Project rồi → `409 PROJECT_ALREADY_EXISTS` (mỗi team chỉ có đúng 1 Project).
- Response (201) — `StudentProjectResponse`: `projectId, courseId, teamId, teamNo, teamName, name, description, projectType{id,code,name}, createdBy{userId,fullName}, createdAt`.

### Đọc Project — `GET /api/student/courses/{courseId}/project` (STUDENT only)
Bất kỳ thành viên nào trong team (Leader lẫn Member) đều đọc được — không giới hạn chỉ Leader. `404 PROJECT_NOT_FOUND` nếu team chưa tạo Project.

### Đọc dữ liệu Project chi tiết (Task/Sprint/Commit...) qua `/api/projects/{projectId}/**`

Phân quyền tập trung tại tầng service (`ProjectDataAuthorization`), **không** phải theo prefix path như Admin/Lecturer/Student:

| Role | Đọc (GET tasks/sprints/commits/sync-status/SSE) | Ghi (tạo/sửa/xoá task, sprint, transition, sync) |
|---|---|---|
| `ADMIN` | ❌ **Bị từ chối hoàn toàn** — Admin không đọc được dữ liệu Task/Sprint/Commit qua các API này | ❌ |
| `LECTURER` | ✅ chỉ khi được phân công dạy course sở hữu project đó | ❌ luôn bị từ chối |
| `STUDENT` (thành viên active trong team) | ✅ | ❌ nếu không phải Leader |
| `STUDENT` (Leader active của team) | ✅ | ✅ |

Đây là điểm rất dễ làm sai UI nếu không kiểm tra kỹ: **đừng cho Admin vào màn hình xem Task/Sprint của một Project cụ thể** — API sẽ trả lỗi 403 (`INTEGRATION_FORBIDDEN`/`ACCESS_DENIED`) ngay cả khi Admin xem được mọi thứ khác.

---

## 16. Jira Integration — FE Flow đầy đủ

### 16.1. Endpoint

Base: `/api/projects/{projectId}/integrations` (yêu cầu là thành viên project; các thao tác kết nối yêu cầu **Team Leader**).

| Method | Path | Role | Mục đích |
|---|---|---|---|
| GET | `` (gốc) | thành viên | Trạng thái tích hợp Jira + GitHub (mục 18) |
| POST | `/jira/connect?returnPath=` | Leader | Bắt đầu OAuth Jira → trả `{authorizationUrl, state}` |
| GET | `/jira/sites` | Leader | Danh sách Cloud site truy cập được (sau khi OAuth xong) |
| GET | `/jira/projects?cloudId=` | Leader | Danh sách Jira Project trong 1 site |
| GET | `/jira/boards?cloudId=&jiraProjectId=` | Leader | Danh sách board trong 1 Jira Project |
| PUT | `/jira` | Leader | **Lưu lựa chọn cuối cùng** — body: `{ "cloudId", "jiraProjectId", "boardId"? }` → 204 |
| DELETE | `/jira` | Leader | Ngắt kết nối (soft-revoke, xem 16.4) |

**Không có** endpoint "reconnect" riêng cho Jira — reconnect = gọi lại đúng `POST /jira/connect` → chọn lại site/project/board → `PUT /jira`.

### 16.2. Luồng "Connect Jira từ đầu"

```
1. FE (đã là Leader) bấm "Kết nối Jira"
2. POST /api/projects/{projectId}/integrations/jira/connect
   → { authorizationUrl, state }
3. window.location.href = authorizationUrl   (điều hướng trình duyệt, KHÔNG axios)
4. Người dùng đăng nhập/chấp thuận trên Atlassian
5. Atlassian redirect về backend (callback nội bộ, FE không tự gọi)
6. Backend redirect trình duyệt về:
     - /integrations/success (path FE tuỳ chỉnh qua returnPath) khi callback OK
     - /integrations/failure?code=<mã lỗi> khi thất bại
7. Tại trang success, FE gọi:
   GET /api/projects/{projectId}/integrations/jira/sites
   → cho người dùng chọn 1 site → lấy cloudId
8. GET .../jira/projects?cloudId=xxx → chọn Jira Project → lấy jiraProjectId
9. GET .../jira/boards?cloudId=xxx&jiraProjectId=yyy → chọn board (tuỳ chọn) → boardId
10. PUT .../jira { cloudId, jiraProjectId, boardId } → 204
    (Ở bước này backend TỰ ĐỘNG đăng ký webhook + chạy đồng bộ dữ liệu ban đầu — xem 16.3)
11. FE refetch GET .../integrations để cập nhật UI trạng thái "Đã kết nối"
```

### 16.3. Tự động, không cần cấu hình thủ công

- **Webhook**: ngay sau bước `PUT /jira` thành công, backend tự đăng ký "dynamic webhook" với Atlassian (nhận sự kiện `jira:issue_created/updated/deleted`, `sprint_created/updated/started/closed/deleted`). **Leader không cần vào Jira Admin để tạo webhook thủ công.**
- **Đồng bộ ban đầu**: cũng trong cùng lần gọi `PUT /jira`, backend tự kích hoạt đồng bộ dữ liệu Task/Sprint ban đầu (chạy nền, không chặn response). FE **không cần** bấm nút "Sync" sau khi kết nối lần đầu — nút Sync (mục 26) chỉ dùng để khôi phục khi có sự cố.

### 16.4. Kiểm tra trạng thái — TUYỆT ĐỐI không dùng `jira != null`

`GET /api/projects/{projectId}/integrations` trả field `jira`:
```json
{ "cloudId": "...", "siteName": "...", "projectKey": "SAGA", "boardId": "...", "status": "ACTIVE" }
```

**Vì sao không được check `jira != null`**: khi Leader bấm "Ngắt kết nối" (`DELETE .../jira`), backend chỉ **soft-revoke** — đổi `status` thành `"REVOKED"` nhưng **vẫn giữ nguyên** `cloudId`/`siteName`/`projectKey` (để hiển thị lại cho người dùng biết trước đó đã kết nối gì). Nghĩa là **`jira` sẽ KHÔNG BAO GIỜ trở lại `null` sau khi từng kết nối** — nếu FE chỉ check `jira != null` sẽ hiển thị sai là "đã kết nối" ngay cả khi đã ngắt.

✅ **Cách đúng**: `const isJiraConnected = data.jira != null && data.jira.status === "ACTIVE";`

### 16.5. Mã lỗi Jira quan trọng

| Mã lỗi | HTTP | Ý nghĩa | FE nên làm |
|---|---|---|---|
| `JIRA_OAUTH_CANCELLED` | redirect `?code=` | Người dùng huỷ OAuth giữa chừng | Cho phép bấm "Kết nối lại" |
| `JIRA_OAUTH_CALLBACK_INVALID` | redirect `?code=` | Callback không hợp lệ/hết hạn | Thử kết nối lại từ đầu |
| `JIRA_PROJECT_IN_USE` | 409 | Jira Project này đã được SAGA Project khác dùng | Chọn Jira Project khác |
| `JIRA_PROJECT_KEY_AMBIGUOUS` | 409 | Không xác định rõ project key | Chọn lại rõ ràng từ danh sách |
| `JIRA_SOURCE_REPLACE_BLOCKED_BY_EVIDENCE` | 409 | Không thể đổi nguồn Jira vì đã có bằng chứng đóng góp gắn với nguồn cũ | Không cho đổi; giải thích rõ cho người dùng |

`error_description` từ Atlassian **không bao giờ** được phản ánh vào URL redirect (để tránh lộ thông tin nhạy cảm) — chỉ có `code` là mã enum ngắn gọn.

---

## 17. GitHub Integration — FE Flow đầy đủ

### 17.1. Mô hình "Shared Installation" (từ V12)

> **Một (1) GitHub App installation có thể được dùng chung bởi nhiều SAGA Project.** Mỗi SAGA Project tự chọn repository riêng của mình. Việc một Project khác đã dùng chung installation **không** bắt buộc phải cài lại GitHub App.

Xung đột chỉ xảy ra ở **cấp repository**, không phải cấp installation: nếu repository X đã được gán (ACTIVE) cho SAGA Project A, Project B **không** thể chọn lại repository X → `409 GITHUB_REPOSITORY_IN_USE`.

### 17.2. Endpoint

Base: `/api/projects/{projectId}/integrations` (Leader-only cho các thao tác kết nối/chọn repo/ngắt kết nối):

| Method | Path | Role | Mục đích |
|---|---|---|---|
| GET | `` (gốc) | thành viên | Trạng thái tích hợp (mục 18) |
| POST | `/github/connect?returnPath=&installationId=&mode=` | Leader | Bắt đầu kết nối/kết nối lại |
| GET | `/github/reconnect/candidates` | Leader | Danh sách installation cũ có thể chọn lại |
| GET | `/github/setup/callback` | (redirect, không gọi trực tiếp) | Callback GitHub App gọi về sau khi cài đặt |
| GET | `/github/repositories` | Leader | Danh sách repo truy cập được qua installation hiện tại |
| PUT | `/github/repositories` | Leader | **Lưu** danh sách repo đã chọn — body: `[{ "repositoryId": 123, "role": "FRONTEND"|"BACKEND"|"OTHER" }]` → 204 |
| DELETE | `/github` | Leader | Ngắt kết nối GitHub khỏi project này (chỉ gỡ liên kết của project này, installation dùng chung vẫn còn cho project khác) |

### 17.3. Luồng "Connect GitHub từ đầu tới lúc chọn repo xong"

```
1. FE (Leader) bấm "Kết nối GitHub"
2. POST .../github/connect (không truyền installationId/mode lần đầu)
   → { authorizationUrl, state }
3. window.location.href = authorizationUrl  (điều hướng trình duyệt)
4. Người dùng cài đặt/chấp thuận GitHub App
5. GitHub redirect về backend → backend redirect FE:
     - /integrations/success (thành công thẳng, có thể kèm ?code=GITHUB_INSTALLATION_SELECTION_REQUIRED — xem 17.4)
     - /integrations/failure?code=<mã lỗi>
6. GET .../github/repositories → danh sách repo trong installation
7. PUT .../github/repositories [{ repositoryId, role }] → 204
   (backend TỰ ĐỘNG chạy đồng bộ commit ban đầu ngay sau bước này)
8. Refetch GET .../integrations để cập nhật UI
```

### 17.4. Khi có nhiều installation cũ — `GITHUB_INSTALLATION_SELECTION_REQUIRED`

Nếu trang success redirect kèm `?code=GITHUB_INSTALLATION_SELECTION_REQUIRED`, nghĩa là hệ thống phát hiện **nhiều** installation từng liên kết với project này trong quá khứ và **không tự đoán** — FE phải:
```
1. GET .../github/reconnect/candidates
   → [{ installationId, accountLogin, accountType }, ...]
2. Cho người dùng chọn 1 candidate
3. POST .../github/connect?installationId=<đã chọn>
   (tiếp tục luồng OAuth bình thường như 17.3 bước 3 trở đi)
```
Nếu muốn cài đặt installation **hoàn toàn mới** thay vì chọn lại cái cũ: `POST .../github/connect?mode=install_new`.

### 17.5. Kiểm tra trạng thái GitHub

```json
{
  "installationId": 12345,
  "accountLogin": "my-org",
  "status": "ACTIVE",   // hoặc SUSPENDED | DELETED
  "repositories": [
    { "id": "uuid", "repositoryId": 999, "fullName": "org/repo", "role": "BACKEND", "status": "ACTIVE" }
  ]
}
```
`github` là `null` nếu project **chưa từng** kết nối. Mảng `repositories` có thể chứa cả repo đã `REVOKED` (đã gỡ) — **lọc `status === "ACTIVE"`** để chỉ hiện repo đang thực sự kết nối.

### 17.6. Mã lỗi GitHub quan trọng

| Mã lỗi | HTTP | Ý nghĩa |
|---|---|---|
| `GITHUB_REPOSITORY_IN_USE` | 409 | Repository đã gắn với SAGA Project khác — chọn repo khác |
| `GITHUB_INSTALLATION_SELECTION_REQUIRED` | 409 (hoặc `?code=` trên redirect thành công) | Nhiều installation cũ, cần người dùng chọn (xem 17.4) |
| `GITHUB_INSTALLATION_INVALID` | 400 | `installationId` gửi lên không hợp lệ/không khớp lịch sử |
| `GITHUB_INSTALLATION_NOT_AUTHORIZED` | 403 | Tài khoản GitHub hiện tại không có quyền trên installation này |

---

## 18. Project Integration Status

`GET /api/projects/{projectId}/integrations` là **nguồn dữ liệu duy nhất, luôn đúng** cho trạng thái tích hợp — gộp cả Jira và GitHub trong một response:

```json
{
  "github": { "installationId": ..., "accountLogin": ..., "status": "ACTIVE", "repositories": [...] },
  "jira": { "cloudId": ..., "siteName": ..., "projectKey": ..., "boardId": ..., "status": "ACTIVE" }
}
```

**Nguyên tắc bắt buộc**: sau bất kỳ hành động connect/disconnect/reconnect nào (thành công hay quay lại từ redirect), FE phải **gọi lại (refetch)** endpoint này để lấy trạng thái chuẩn từ backend — **không được** tự suy đoán/giữ state cũ ở FE, vì các thao tác kết nối chạy qua redirect trình duyệt (không phải XHR) nên FE không có cách nào biết kết quả ngoài việc hỏi lại backend.

---

## 19. Task API — CRUD đầy đủ

Base: `/api/projects/{projectId}/tasks`. Phân quyền theo mục 15 (đọc: thành viên active + lecturer được phân công; ghi: chỉ Leader).

| Method | Path | Mục đích |
|---|---|---|
| GET | `/tasks` | Danh sách task |
| GET | `/tasks/{taskId}` | Chi tiết 1 task |
| POST | `/tasks` | Tạo task (201) |
| PATCH | `/tasks/{taskId}` | Cập nhật task (một phần) |
| DELETE | `/tasks/{taskId}` | Xoá task (204) |
| GET | `/tasks/options` | Dữ liệu dựng form (mục 20) |
| GET | `/tasks/{taskId}/transitions` | Danh sách transition khả dụng (mục 21) |
| POST | `/tasks/{taskId}/transition` | Đổi trạng thái task (mục 21) |
| PUT | `/tasks/{taskId}/sprint` | Gán/gỡ task khỏi sprint (mục 24) |
| GET | `/tasks/{taskId}/commits` | Commit liên kết với task (mục 25) |

**Task trong SAGA lấy Jira làm nguồn dữ liệu chuẩn (Jira-authoritative)**: mọi lệnh tạo/sửa/xoá/transition từ SAGA đều **gọi trực tiếp và đồng bộ (synchronous) sang Jira trước**, sau đó backend đọc lại kết quả chính thức từ Jira và lưu vào bảng chiếu (projection) của SAGA, rồi mới trả response cho FE. Nghĩa là: response của `PATCH`/`POST`/`transition` **luôn** là dữ liệu đã được Jira xác nhận — FE không cần refetch thêm sau các lệnh ghi thành công.

### Request tạo task — `POST /tasks`
```json
{
  "summary": "Bắt buộc, tối đa 255 ký tự",
  "description": "tuỳ chọn, tối đa 10000 ký tự",
  "issueTypeId": "tuỳ chọn — lấy từ /tasks/options",
  "assigneeAccountId": "tuỳ chọn — Jira accountId (mục 23)",
  "priorityId": "tuỳ chọn — lấy từ /tasks/options",
  "storyPoints": 5,
  "sprintId": 123,
  "sprintExternalId": "hoặc dùng field này thay cho sprintId"
}
```

### Request cập nhật — `PATCH /tasks/{taskId}`
```json
{
  "summary": "...", "description": "...", "issueTypeId": "...",
  "assigneeAccountId": "...", "clearAssignee": false,
  "priorityId": "...", "storyPoints": 5,
  "sprintExternalId": "...", "moveToBacklog": false
}
```
Mọi field đều tuỳ chọn — chỉ field khác `null` mới được cập nhật vào Jira. **Lưu ý**: DTO này có tồn tại 2 field `transitionId`/`targetStatusId` nhưng **backend không dùng chúng trong PATCH** — đổi trạng thái task **bắt buộc phải dùng endpoint riêng** `POST /tasks/{taskId}/transition` (mục 21), không được PATCH trạng thái trực tiếp.

### Response — `ProjectTaskResponse` (đầy đủ field, đã xác minh từ DTO)
```json
{
  "id": "uuid", "externalId": "...", "externalKey": "SAGA-123",
  "title": "...", "description": "...",
  "status": "TODO | IN_PROGRESS | IN_REVIEW | DONE | BLOCKED",
  "jiraStatusId": "...", "jiraStatusName": "To Do",
  "issueTypeName": "Story",
  "assigneeExternalId": "jira-account-id-hoặc-null",
  "assigneeDisplayName": "Tên hiển thị-hoặc-null",
  "assigneeStudentId": "uuid-hoặc-null",
  "assignee": { "accountId": "...", "displayName": "...", "studentId": "uuid" } ,
  "priority": "High",
  "priorityDetail": { "id": null, "name": "High" },
  "storyPoint": 5,
  "sprint": { "id": "uuid", "externalSprintId": "...", "name": "Sprint 1", "state": "active" },
  "linkedCommitCount": 3,
  "externalUpdatedAt": "...", "createdAt": "...", "updatedAt": "..."
}
```

Vài lưu ý chính xác cần nhớ:
- Field tên là **`issueTypeName`**, không phải `issueType`.
- Field tên là **`storyPoint`** (số ít), không phải `storyPoints` (response khác request).
- `priorityDetail.id` **luôn là `null`** trong implementation hiện tại — chỉ `priorityDetail.name` có giá trị thật.
- `assignee` là `null` nếu task chưa gán ai.
- `sprint` là `null` nếu task đang ở backlog.
- `linkedCommitCount` được tính lại mỗi lần đọc (không phải cột lưu sẵn).

### Xoá task — chặn nếu đã có bằng chứng
`DELETE /tasks/{taskId}` → **409 `TASK_DELETE_BLOCKED_BY_EVIDENCE`** nếu task đã có Work Session hoặc Contribution Confirmation gắn vào (mục 28) — không cho xoá để bảo toàn dữ liệu chấm điểm.

---

## 20. Task Options — Form Building

`GET /api/projects/{projectId}/tasks/options` — gọi khi mở form tạo/sửa Task. Bất kỳ thành viên nào (không chỉ Leader) đều gọi được.

```json
{
  "issueTypes": [{ "id": "...", "name": "Story", "description": "..." }],
  "priorities": [{ "id": "...", "name": "High" }],
  "assignableUsers": [{ "accountId": "...", "displayName": "..." }],
  "estimation": { "supported": true, "fieldId": "...", "fieldName": "Story Points" },
  "sprints": [{ "id": "...", "name": "Sprint 1", "state": "active" }]
}
```
Dùng đúng `id` từ đây khi gửi `issueTypeId`/`priorityId` trong `POST`/`PATCH` task; dùng `accountId` từ `assignableUsers` khi gửi `assigneeAccountId` (mục 23). `sprints` trả rỗng nếu project chưa cấu hình board.

---

## 21. Task Status / Transitions

**Không được `PATCH` trạng thái task trực tiếp** — trạng thái phải đổi qua đúng cơ chế "transition" của Jira (mỗi workflow Jira có tập transition hợp lệ khác nhau tuỳ trạng thái hiện tại).

```
1. GET /api/projects/{projectId}/tasks/{taskId}/transitions
   → [{ "id": "...", "name": "Start Progress", "toStatusId": "...", "toStatusName": "In Progress" }, ...]
2. Hiển thị danh sách transition khả dụng cho người dùng chọn
3. POST /api/projects/{projectId}/tasks/{taskId}/transition
   { "transitionId": "id-đã-chọn" }
   (hoặc dùng { "targetStatusId": "..." } nếu chỉ biết trạng thái đích, không có transitionId cụ thể)
4. Response: ProjectTaskResponse đầy đủ, đã cập nhật status/jiraStatusName mới
```
Chỉ **Team Leader** được gọi bước 3 (`403 NOT_TEAM_LEADER` nếu không phải). GET transitions (bước 1) thì mọi thành viên active đều gọi được. Đây là lệnh gọi **đồng bộ sang Jira** — có độ trễ mạng thật sự, nên hiển thị loading state khi chờ.

---

## 22. Story Point

- Field trong response Task: **`storyPoint`** (số ít, kiểu `Integer`, có thể `null`).
- Khi tạo/sửa task, gửi lên bằng field **`storyPoints`** (số nhiều) trong `CreateProjectTaskRequest`/`PatchProjectTaskRequest`.
- Khả năng ước lượng của board (có hỗ trợ story point hay không) lấy từ `GET /tasks/options` → `estimation.supported` (`boolean`) + `estimation.fieldId`/`fieldName`.
- **Không hard-code Jira custom field ID** ở FE — luôn lấy `estimation.fieldId` từ `/tasks/options` nếu cần hiển thị debug; với luồng thông thường chỉ cần gửi `storyPoints` là đủ, backend tự ánh xạ sang đúng custom field của Jira.

---

## 23. Assignee

- **Hiển thị**: dùng `assigneeDisplayName` (hoặc `assignee.displayName`) — tên hiển thị lấy từ Jira.
- **Gửi lên khi gán người**: dùng `assigneeAccountId` = **Jira `accountId`** (chuỗi do Atlassian cấp, lấy từ `GET /tasks/options` → `assignableUsers[].accountId`).
- **TUYỆT ĐỐI KHÔNG** gửi SAGA `userId`/`studentId` (UUID nội bộ) vào field `assigneeAccountId` — hai hệ định danh này hoàn toàn khác nhau (`Jira accountId != SAGA userId`). `assigneeStudentId`/`assignee.studentId` trong **response** chỉ là để SAGA tiện đối chiếu nội bộ (map ngược từ Jira accountId sang UUID sinh viên nếu khớp được), **không phải** giá trị dùng để ghi ngược lại.
- **Bỏ gán (unassign)**: gửi `PATCH` với `clearAssignee: true`.

---

## 24. Sprint CRUD

Base: `/api/projects/{projectId}/sprints`.

| Method | Path | Ghi chú |
|---|---|---|
| GET | `/sprints` | **Không phải đọc thuần** — backend tự đồng bộ với board Jira trước khi trả danh sách, nên gọi endpoint này cũng có độ trễ mạng nhất định |
| GET | `/sprints/{sprintId}` | Đọc thuần từ DB |
| POST | `/sprints` | Leader-only, 201 |
| PATCH | `/sprints/{sprintId}` | Leader-only |
| DELETE | `/sprints/{sprintId}` | Leader-only, 204 — xoá sprint cũng phát sinh SSE `TASKS_CHANGED` (vì các task trong sprint bị ảnh hưởng) |

Request tạo:
```json
{ "name": "Sprint 1", "goal": "tuỳ chọn", "startDate": "tuỳ chọn", "endDate": "tuỳ chọn" }
```
Response:
```json
{
  "id": "uuid", "externalSprintId": "...", "name": "...", "state": "active",
  "goal": "...", "startDate": "...", "endDate": "...", "completeDate": null
}
```

### Gán task vào Sprint / đưa về Backlog

```
PUT /api/projects/{projectId}/tasks/{taskId}/sprint
{ "sprintId": 123 }        // gán vào sprint có id 123
{ "sprintId": null }       // hoặc để trống body → ĐƯA VỀ BACKLOG (đã xác nhận từ code)
```

---

## 25. GitHub Commits / Task-Commit Links

| Method | Path | Mục đích |
|---|---|---|
| GET | `/api/projects/{projectId}/commits` | Toàn bộ commit đã đồng bộ của project |
| GET | `/api/projects/{projectId}/tasks/{taskId}/commits` | Commit đã liên kết với 1 task cụ thể |

```json
// ProjectCommitResponse
{
  "id": "uuid", "repoId": "uuid", "repositoryFullName": "org/repo",
  "sha": "...", "message": "...", "authorExternalId": "github-login",
  "authorStudentId": "uuid-hoặc-null", "committedAt": "...", "createdAt": "..."
}
```

**Đây là API chỉ đọc** — không có endpoint nào để FE tự tạo/xoá liên kết Task↔Commit. Việc liên kết là **hoàn toàn tự động ở backend**:

```
Lập trình viên push code lên GitHub (có nhắc mã task trong message/branch, vd "SAGA-123: fix bug")
  → GitHub gửi webhook về backend
  → backend lưu commit vào projection
  → backend tự parse mã Jira key trong message/branch → tự tạo liên kết Task↔Commit
  → SSE bắn sự kiện COMMITS_CHANGED và/hoặc TASK_LINKS_CHANGED
  → FE nhận sự kiện → refetch danh sách commit/task tương ứng (mục 27)
```
FE không cần và không thể tự dựng liên kết này qua API.

---

## 26. Project Sync

```
POST /api/projects/{projectId}/sync    (Leader only) → 202 Accepted
GET  /api/projects/{projectId}/sync-status
```

**ĐÂY KHÔNG PHẢI LUỒNG KẾT NỐI BÌNH THƯỜNG.** Kết nối Jira (mục 16) và GitHub (mục 17) đã **tự động** kích hoạt đồng bộ ban đầu ngay khi hoàn tất — endpoint `/sync` ở đây chỉ là **cơ chế khôi phục thủ công** (recovery/backfill), dùng khi:
- Có webhook bị lỡ/mất do sự cố tạm thời từ Jira/GitHub.
- Cần đồng bộ lại toàn bộ dữ liệu sau một khoảng thời gian gián đoạn.

**Không nên thiết kế UI bắt người dùng phải bấm "Sync" sau mỗi lần kết nối provider** — điều đó là thừa và sai với thiết kế thực tế của backend.

Request `POST /sync`: không có body. Response:
```json
{ "projectId": "...", "jira": "QUEUED", "github": "SKIPPED_NOT_CONFIGURED" }
```
Giá trị `jira`/`github` có thể là: `QUEUED`, `SKIPPED_NOT_CONFIGURED`, `SKIPPED_NOT_ACTIVE`, `SKIPPED_ALREADY_RUNNING`, `SKIPPED_NO_CREDENTIAL` (chỉ Jira).

`GET /sync-status` (không giới hạn Leader, thành viên active đều xem được):
```json
[
  { "projectId": "...", "provider": "JIRA", "status": "SUCCEEDED", "startedAt": "...", "completedAt": "...", "itemsProcessed": 42, "itemsFailed": 0 },
  { "provider": "GITHUB", "status": "RUNNING", ... }
]
```

---

## 27. SSE Realtime

```
GET /api/projects/{projectId}/events
```
Yêu cầu quyền đọc project (giống bảng ở mục 15 — **ADMIN không dùng được**).

```ts
const source = new EventSource(`${API_BASE_URL}/api/projects/${projectId}/events`, {
  withCredentials: true,
});
```

### Toàn bộ tên sự kiện thực tế tồn tại (đã xác minh trong enum `ProjectRealtimeEventType`)

```
READY
TASKS_CHANGED
SPRINTS_CHANGED
COMMITS_CHANGED
TASK_LINKS_CHANGED
TASK_EVIDENCE_CHANGED
SYNC_STATUS_CHANGED
```

**SSE chỉ gửi tín hiệu "có gì đó đã thay đổi" (invalidation), KHÔNG gửi kèm dữ liệu đầy đủ.** Khi nhận sự kiện, FE phải **gọi lại REST API tương ứng** để lấy dữ liệu chuẩn mới nhất — không dùng payload SSE làm state cuối cùng.

| Sự kiện nhận được | FE nên làm |
|---|---|
| `READY` | Gửi ngay khi vừa kết nối SSE thành công (kể cả sau khi reconnect) — coi như tín hiệu "làm mới toàn bộ dữ liệu project hiện tại" |
| `TASKS_CHANGED` | Refetch danh sách task |
| `SPRINTS_CHANGED` | Refetch danh sách sprint (và task nếu màn hình đang hiển thị theo sprint) |
| `COMMITS_CHANGED` | Refetch danh sách commit |
| `TASK_LINKS_CHANGED` | Refetch `linkedCommitCount` / danh sách commit của task đang mở |
| `TASK_EVIDENCE_CHANGED` | Refetch dữ liệu evidence/work-session/contribution của task đang mở |
| `SYNC_STATUS_CHANGED` | Refetch `GET /sync-status` |

Server gửi heartbeat (comment SSE, không phải event có tên) mỗi ~25 giây để giữ kết nối — FE không cần xử lý riêng, `EventSource` tự bỏ qua comment. Nếu `EventSource` tự reconnect (mất mạng tạm thời), sự kiện `READY` sẽ được gửi lại ngay khi kết nối lại thành công — dùng đây làm điểm neo để refetch toàn bộ.

**Không dùng polling 2 giây làm cơ chế realtime chính** — SSE đã đủ; polling chỉ nên là phương án dự phòng khi `EventSource` bị lỗi hoàn toàn (vd trình duyệt cũ/mạng doanh nghiệp chặn).

---

## 28. Contribution / Evidence APIs

Base: `/api/tasks/{taskId}` — **lưu ý path KHÔNG nằm dưới `/api/projects/{projectId}`** mà độc lập, chỉ cần `taskId`.

| Method | Path | Mục đích |
|---|---|---|
| POST | `/work-sessions/start` | Bắt đầu 1 phiên làm việc trên task |
| POST | `/work-sessions/{sessionId}/stop` | Kết thúc phiên |
| POST | `/contribution-confirmations` | Xác nhận đóng góp — body: `{ "commitShas": [...], "pullRequests": [...] }`, **yêu cầu step-up session** (xác thực lại gần đây) |
| GET/POST | `/web-links` | Danh sách / thêm link tham khảo cho task |
| DELETE | `/web-links/{linkId}` | Xoá link |
| GET/POST | `/files` | Danh sách / upload file đính kèm (multipart) |
| GET | `/files/{fileId}` | Tải file |
| DELETE | `/files/{fileId}` | Xoá file |

**Bằng chứng đồng bộ từ Jira là bất biến**: nếu `source === "JIRA"` (field `source` trong response web-link/file), request `DELETE` sẽ bị từ chối với `409 TASK_EVIDENCE_JIRA_IMMUTABLE` — chỉ nội dung do chính SAGA tạo ra (`source === "SAGA"`) mới xoá được.

Không có endpoint tính điểm đóng góp (contribution scoring) trong nhóm API này — việc tính điểm/trọng số thuộc các controller khác (`/api/teams/{teamId}/...`, `/api/lecturer/courses/{courseId}/...`) nằm ngoài phạm vi "evidence" của tài liệu này; **không suy đoán công thức tính điểm** ở đây.

---

## 29. Error Handling

Mọi lỗi domain (không phải lỗi mạng) trả về đúng 1 khuôn dạng:
```json
{ "code": "TÊN_MÃ_LỖI", "message": "Mô tả bằng tiếng Anh, không nên hiển thị trực tiếp cho người dùng cuối" }
```
→ FE nên có bảng ánh xạ `code` → thông điệp tiếng Việt riêng, không hiển thị thẳng `message` gốc.

### Xử lý theo nhóm HTTP status

| Status | Ý nghĩa chung | Hành động FE |
|---|---|---|
| 400 | Dữ liệu gửi lên sai. **Lưu ý**: các lỗi `@Valid` (thiếu field bắt buộc, sai định dạng...) đều bị gộp chung thành **`400 REQUEST_INVALID`** — backend **không** trả chi tiết field nào sai. FE phải tự validate phía client trước khi gửi để có UX tốt. | Kiểm tra lại form, hiển thị lỗi chung "Dữ liệu không hợp lệ" nếu không map được `code` cụ thể |
| 401 | Chưa đăng nhập / session hết hạn | Điều hướng Login |
| 403 | Sai quyền, CSRF sai, hoặc `PASSWORD_SETUP_REQUIRED` | Xem bảng mục 3.4 |
| 404 | Không tìm thấy tài nguyên | Hiển thị "không tìm thấy", refetch danh sách cha |
| 409 | Xung đột nghiệp vụ (đây là nhóm quan trọng nhất cần xử lý tử tế) | Xem bảng mã lỗi bên dưới — **không tự động retry** |
| 5xx | Lỗi hệ thống/hạ tầng (bao gồm `503 SESSION_STORE_UNAVAILABLE` khi Redis lỗi) | Hiển thị lỗi tạm thời, cho phép thử lại sau |

### Bảng mã lỗi nghiệp vụ quan trọng (đã xác minh tồn tại trong source)

| Mã lỗi | HTTP | Ý nghĩa | FE hiển thị / hành động |
|---|---|---|---|
| `TEAM_LEADER_REMOVAL_REQUIRES_REASSIGNMENT` | 409 | Không thể xoá sinh viên vì đang là Leader | Yêu cầu đổi Leader trước (mục 12) |
| `ROSTER_STUDENT_ALREADY_REMOVED` | 409 | Đã xoá/huỷ trước đó | Refetch roster, không báo lỗi gay gắt |
| `GITHUB_REPOSITORY_IN_USE` | 409 | Repo đã dùng cho Project khác | Chọn repo khác |
| `GITHUB_INSTALLATION_SELECTION_REQUIRED` | 409 / query `?code=` | Cần chọn 1 trong nhiều installation cũ | Gọi `reconnect/candidates` (mục 17.4) |
| `JIRA_PROJECT_IN_USE` | 409 | Jira Project đã dùng cho SAGA Project khác | Chọn Jira Project khác |
| `JIRA_SOURCE_REPLACE_BLOCKED_BY_EVIDENCE` | 409 | Không đổi được nguồn Jira vì đã có bằng chứng gắn với nguồn cũ | Không cho đổi, giải thích rõ |
| `TASK_DELETE_BLOCKED_BY_EVIDENCE` | 409 | Task đã có Work Session/Contribution Confirmation | Không cho xoá |
| `TASK_EVIDENCE_JIRA_IMMUTABLE` | 409 | Link/file đồng bộ từ Jira không thể xoá ở SAGA | Ẩn nút xoá cho các mục `source === "JIRA"` |
| `PASSWORD_RESET_TOKEN_INVALID` | 400 | Link đặt lại mật khẩu sai/đã dùng | Yêu cầu gửi lại email quên mật khẩu |
| `PASSWORD_RESET_TOKEN_EXPIRED` | 400 | Link đặt lại mật khẩu hết hạn | Yêu cầu gửi lại email |
| `NOT_TEAM_LEADER` | 403 | Hành động chỉ dành cho Leader | Ẩn nút hành động nếu `myRole !== "LEADER"` |
| `PROJECT_ALREADY_EXISTS` | 409 | Team đã có Project | Điều hướng sang xem Project hiện có thay vì tạo mới |

---

## 30. Frontend Query Invalidation Strategy

*(Đây là khuyến nghị kiến trúc FE, không phải hợp đồng API — tuỳ chỉnh theo thư viện thực tế đội dùng, ví dụ dưới đây theo phong cách TanStack Query.)*

Gợi ý đặt query key theo cấu trúc phân cấp để dễ invalidate:
```ts
["project", projectId, "tasks"]
["project", projectId, "tasks", taskId]
["project", projectId, "sprints"]
["project", projectId, "commits"]
["project", projectId, "integration"]
["project", projectId, "sync-status"]
```

Bảng ánh xạ sự kiện SSE → query cần invalidate (tiếp nối mục 27):

```ts
function handleSseEvent(type: string, projectId: string, queryClient: QueryClient) {
  switch (type) {
    case "READY":
      queryClient.invalidateQueries({ queryKey: ["project", projectId] });
      break;
    case "TASKS_CHANGED":
      queryClient.invalidateQueries({ queryKey: ["project", projectId, "tasks"] });
      break;
    case "SPRINTS_CHANGED":
      queryClient.invalidateQueries({ queryKey: ["project", projectId, "sprints"] });
      queryClient.invalidateQueries({ queryKey: ["project", projectId, "tasks"] });
      break;
    case "COMMITS_CHANGED":
      queryClient.invalidateQueries({ queryKey: ["project", projectId, "commits"] });
      break;
    case "TASK_LINKS_CHANGED":
      queryClient.invalidateQueries({ queryKey: ["project", projectId, "tasks"] });
      break;
    case "TASK_EVIDENCE_CHANGED":
      queryClient.invalidateQueries({ queryKey: ["project", projectId, "tasks"] });
      break;
    case "SYNC_STATUS_CHANGED":
      queryClient.invalidateQueries({ queryKey: ["project", projectId, "sync-status"] });
      break;
  }
}
```

---

## 31. Complete User Flows

### A. Khởi động app / khôi phục phiên đăng nhập
```
1. GET /api/auth/csrf     → nhận cookie XSRF-TOKEN
2. GET /api/auth/me       → biết authenticated, passwordSetupRequired, role
3. Điều hướng theo kết quả (mục 4)
```

### B. Đăng nhập local
```
1. POST /api/auth/login { identifier, password }  (cần CSRF)
2. Điều hướng theo user.role trong response
```

### C. Đăng nhập Google
```
1. window.location.href = "<API_BASE_URL>/oauth2/authorization/google"
2. (trình duyệt tự xử lý, quay lại 1 trong 3 URL: success/password-setup/failure)
3. Tại trang đích: GET /api/auth/me
```

### D. Quên/đặt lại mật khẩu
```
1. POST /api/auth/password/forgot { email }         → luôn hiện thông báo chung
2. Người dùng mở link email: /reset-password?token=...
3. POST /api/auth/password/reset { token, newPassword }
4. Điều hướng về Login
```

### E. Sửa hồ sơ cá nhân
```
1. GET /api/users/me/profile     → hiển thị form (fullName, avatarUrl hiện có)
2. PATCH /api/users/me/profile { fullName?, avatarUrl? }  (cần CSRF)
3. Dùng ngay response trả về để cập nhật UI — không cần GET lại
```

### F. Admin tạo/nhập danh sách lớp
```
1. (đã có sẵn) Semester → AcademicClass → Subject → Syllabus (published) → Course — mục 10
2. GET /api/admin/courses/{courseId}/roster/template   → tải file mẫu
3. Người dùng điền file, upload:
   POST /api/admin/courses/{courseId}/roster/import/preview (multipart)
   → nhận previewToken + danh sách dòng kèm lỗi/cảnh báo
4. POST /api/admin/courses/{courseId}/roster/import/confirm { previewToken }
5. GET /api/admin/courses/{courseId}/roster → refetch để hiển thị kết quả
```

### G. Admin xoá sinh viên khỏi lớp
→ xem chi tiết đầy đủ ở **mục 12**.

### H. Lecturer chia nhóm / đổi trưởng nhóm
```
1. GET /api/lecturer/courses/{courseId}/teams/template  → tải file mẫu chia nhóm
2. POST .../teams/import/preview (multipart)  → xem trước
3. POST .../teams/import/confirm { previewToken }
4. Cần đổi trưởng nhóm: PUT .../teams/{teamId}/leader { teamMemberId }
5. Cần chuyển 1 thành viên sang team khác: PATCH .../team-members/{teamMemberId}/team { targetTeamId }
6. GET .../teams  → refetch để cập nhật UI
```

### I. Team Leader tạo Project
```
1. GET /api/student/courses/{courseId}/team   → xác nhận myRole === "LEADER"
2. GET /api/student/courses/{courseId}/project → 404 nếu chưa có
3. POST /api/student/courses/{courseId}/project { name, projectTypeId?, description? }
4. Dùng response (có projectId) để điều hướng sang trang quản lý Project
```

### J. Kết nối Jira từ đầu tới lúc dùng được
→ xem đầy đủ ở **mục 16.2**.

### K. Kết nối lại Jira đã bị revoke
```
1. GET /api/projects/{projectId}/integrations → thấy jira.status === "REVOKED"
2. POST .../jira/connect  (chạy lại y hệt luồng kết nối mới — mục 16.2)
```

### L. Kết nối GitHub từ đầu tới lúc chọn xong repo
→ xem đầy đủ ở **mục 17.3**.

### M. Kết nối lại GitHub / installation dùng chung
→ xem đầy đủ ở **mục 17.4**.

### N. Tạo Task
```
1. GET /api/projects/{projectId}/tasks/options  → dữ liệu dựng form
2. POST /api/projects/{projectId}/tasks { summary, issueTypeId?, assigneeAccountId?, priorityId?, storyPoints?, sprintId? }
3. Dùng response (ProjectTaskResponse) để thêm vào danh sách hiện có, không cần refetch toàn bộ
```

### O. Sửa story point / assignee / priority của Task
```
PATCH /api/projects/{projectId}/tasks/{taskId} { storyPoints?, assigneeAccountId?, priorityId? }
→ dùng response mới để cập nhật UI
```

### P. Chuyển trạng thái Task
→ xem đầy đủ ở **mục 21**.

### Q. Gán Task vào Sprint / đưa về Backlog
```
PUT /api/projects/{projectId}/tasks/{taskId}/sprint { sprintId: 123 }   // hoặc null để về backlog
```

### R. Tạo/sửa Sprint
```
POST /api/projects/{projectId}/sprints { name, goal?, startDate?, endDate? }
PATCH /api/projects/{projectId}/sprints/{sprintId} { name?, goal?, state?, startDate?, endDate? }
```

### S. Jira tạo/sửa Task từ phía Jira, SAGA tự cập nhật
```
1. Người dùng thao tác trực tiếp trên Jira (không qua SAGA)
2. Jira gửi webhook (jira:issue_created/updated/deleted) về backend SAGA
3. Backend cập nhật bảng chiếu Task nội bộ
4. Backend bắn SSE TASKS_CHANGED (mục 27)
5. FE đang mở màn hình project nhận sự kiện → tự refetch /tasks
   (không cần người dùng bấm F5)
```

### T. GitHub push code, tự liên kết commit với Task
→ xem đầy đủ ở **mục 25**; kết thúc bằng SSE `COMMITS_CHANGED`/`TASK_LINKS_CHANGED`.

### U. Trang project nhận cập nhật realtime qua SSE
```
1. Mở trang → new EventSource("/api/projects/{projectId}/events", { withCredentials: true })
2. Nhận READY ngay lập tức → refetch toàn bộ dữ liệu trang
3. Trong lúc mở trang, nhận các sự kiện khác → refetch đúng phần dữ liệu tương ứng (bảng mục 27)
4. Nếu mất kết nối, EventSource tự reconnect → nhận lại READY → refetch lại toàn bộ
```

### V. Khôi phục thủ công khi nghi ngờ dữ liệu bị thiếu (Manual Sync)
```
1. POST /api/projects/{projectId}/sync   (chỉ Leader, chỉ dùng khi nghi ngờ có sự cố)
2. GET /api/projects/{projectId}/sync-status  → theo dõi tiến trình (poll định kỳ vài giây,
   vì đây là tác vụ nền — SSE không có sự kiện riêng cho "sync xong" ngoài SYNC_STATUS_CHANGED)
```

---

## 32. Các lỗi FE dễ mắc

1. **Quên `credentials: "include"`/`withCredentials: true`** → cookie session/CSRF không được gửi, mọi request thất bại 401/403 khó hiểu.
2. **Quên gắn header `X-XSRF-TOKEN`** cho các request POST/PUT/PATCH/DELETE → luôn nhận `403 ACCESS_DENIED`.
3. **Cố đọc cookie `SAGA_SESSION` bằng JavaScript** → không thể (HttpOnly), và cũng không cần thiết.
4. **Coi `jira != null` là "đã kết nối"** — sai, vì disconnect chỉ soft-revoke, phải kiểm `status === "ACTIVE"` (mục 16.4).
5. **Gửi SAGA `userId` vào field `assigneeAccountId`** — phải dùng Jira `accountId` (mục 23).
6. **Coi trạng thái Task có thể sửa bằng `PATCH` thông thường** — bắt buộc dùng `/transition` (mục 21).
7. **Bắt người dùng bấm "Sync" sau mỗi lần kết nối provider** — dư thừa, backend đã tự đồng bộ (mục 16.3, 17.3, 26).
8. **Lecturer cố tạo Project** — sẽ luôn thất bại, chỉ Team Leader (STUDENT) mới tạo được (mục 15).
9. **MEMBER (không phải Leader) cố cấu hình tích hợp Jira/GitHub** — các endpoint connect/select đều yêu cầu Leader.
10. **Bắt người dùng cài lại GitHub App chỉ vì installation đã được Project khác dùng** — installation được thiết kế để dùng chung (mục 17.1); chỉ **repository** mới bị giới hạn 1-project.
11. **Coi payload SSE là dữ liệu cuối cùng để render** — SSE chỉ là tín hiệu, phải refetch REST API tương ứng (mục 27).
12. **Bỏ qua lỗi 409, coi như lỗi hệ thống chung** — các lỗi 409 trong bảng mục 29 đều mang ý nghĩa nghiệp vụ cụ thể, cần xử lý UI riêng.
13. **Nhầm `courseId` với `projectId`** — hai ID hoàn toàn khác nhau và dùng ở hai nhóm route khác nhau (`/api/admin/courses/{courseId}/...`, `/api/lecturer/courses/{courseId}/...` dùng `courseId`; `/api/projects/{projectId}/...` dùng `projectId`). Một Course có thể có nhiều Team, mỗi Team có tối đa 1 Project.
14. **Để Admin vào thẳng màn hình Task/Sprint của một Project** — API sẽ trả 403 vì Admin bị chặn đọc dữ liệu này (mục 15).

---

## 33. Copy-paste Code Examples

### API client (fetch) kèm CSRF tự động

```ts
// api-client.ts
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL; // KHÔNG hard-code URL production

function getCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

export async function ensureCsrfCookie(): Promise<void> {
  if (!getCookie("XSRF-TOKEN")) {
    await fetch(`${API_BASE_URL}/api/auth/csrf`, { credentials: "include" });
  }
}

export async function apiFetch(path: string, options: RequestInit = {}) {
  const method = (options.method ?? "GET").toUpperCase();
  const isMutating = ["POST", "PUT", "PATCH", "DELETE"].includes(method);
  if (isMutating) await ensureCsrfCookie();

  const headers = new Headers(options.headers);
  if (isMutating) {
    const token = getCookie("XSRF-TOKEN");
    if (token) headers.set("X-XSRF-TOKEN", token);
  }
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const res = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    credentials: "include",
    headers,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new ApiError(res.status, body?.code ?? "UNKNOWN", body?.message ?? res.statusText);
  }
  return res.status === 204 ? null : res.json();
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}
```

### Đăng nhập

```ts
export function login(identifier: string, password: string) {
  return apiFetch("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ identifier, password }),
  });
}
```

### Sửa hồ sơ

```ts
export function updateProfile(patch: { fullName?: string; avatarUrl?: string }) {
  return apiFetch("/api/users/me/profile", { method: "PATCH", body: JSON.stringify(patch) });
}
```

### Quên / đặt lại mật khẩu

```ts
export function forgotPassword(email: string) {
  return apiFetch("/api/auth/password/forgot", { method: "POST", body: JSON.stringify({ email }) });
}

export function resetPassword(token: string, newPassword: string) {
  return apiFetch("/api/auth/password/reset", {
    method: "POST",
    body: JSON.stringify({ token, newPassword }),
  });
}
```

### Danh sách Task

```ts
export function listTasks(projectId: string) {
  return apiFetch(`/api/projects/${projectId}/tasks`);
}

export function createTask(projectId: string, body: {
  summary: string; issueTypeId?: string; assigneeAccountId?: string;
  priorityId?: string; storyPoints?: number; sprintId?: number;
}) {
  return apiFetch(`/api/projects/${projectId}/tasks`, { method: "POST", body: JSON.stringify(body) });
}
```

### Transition

```ts
export function listTransitions(projectId: string, taskId: string) {
  return apiFetch(`/api/projects/${projectId}/tasks/${taskId}/transitions`);
}

export function transitionTask(projectId: string, taskId: string, transitionId: string) {
  return apiFetch(`/api/projects/${projectId}/tasks/${taskId}/transition`, {
    method: "POST",
    body: JSON.stringify({ transitionId }),
  });
}
```

### SSE + ánh xạ query invalidation

```ts
export function subscribeProjectEvents(
  projectId: string,
  onEvent: (type: string) => void,
): () => void {
  const source = new EventSource(`${API_BASE_URL}/api/projects/${projectId}/events`, {
    withCredentials: true,
  } as EventSourceInit);

  const types = [
    "READY", "TASKS_CHANGED", "SPRINTS_CHANGED", "COMMITS_CHANGED",
    "TASK_LINKS_CHANGED", "TASK_EVIDENCE_CHANGED", "SYNC_STATUS_CHANGED",
  ];
  types.forEach((type) => source.addEventListener(type, () => onEvent(type)));

  return () => source.close();
}
```

---

## 34. Swagger / Manual Testing

- Swagger UI: `/swagger-ui.html` (public, không cần đăng nhập để xem docs, nhưng gọi thử API vẫn cần session/CSRF hợp lệ).
- Swagger dùng được cho: hầu hết các API JSON thông thường (GET/POST/PATCH/DELETE của Task, Roster, Team, Profile...) — miễn là bạn đã có session hợp lệ trong cùng trình duyệt (đăng nhập qua FE thật trước, sau đó mở Swagger cùng trình duyệt/cùng cookie).
- Swagger **KHÔNG** test được:
  - Luồng OAuth có điều hướng trình duyệt thật sự (Google login, Jira/GitHub connect) — các luồng này redirect ra ngoài domain backend rồi quay lại, Swagger UI (chạy trong iframe/tab riêng) không thể hoàn tất chuỗi redirect này đúng cách.
  - **SSE** (`/api/projects/{projectId}/events`) — Swagger UI không có UI để giữ kết nối stream lâu dài và hiển thị các sự kiện đến.
  - Webhook nội bộ (`/api/webhooks/**`) — các endpoint này do Jira/GitHub gọi trực tiếp bằng chữ ký riêng, không phải để test thủ công qua Swagger.
- Với các API cần CSRF, phải tự set cookie `XSRF-TOKEN` (gọi `GET /api/auth/csrf` trước) — Swagger tự copy cookie này vào header nếu cấu hình đúng, nhưng an toàn nhất vẫn là gọi `GET /api/auth/csrf` trong chính tab Swagger trước khi thử bất kỳ API ghi dữ liệu nào.

---

## 35. Final Endpoint Index

> Bảng tra nhanh. Chi tiết đầy đủ (request/response/lỗi) nằm ở các mục tương ứng phía trên.

### AUTH
| Method | Path | Role | Mục |
|---|---|---|---|
| GET | `/api/auth/csrf` | public | 3 |
| GET | `/api/auth/me` | public | 4 |
| POST | `/api/auth/login` | public | 5 |
| POST | `/api/auth/register` | public | 5 |
| POST | `/api/auth/logout` | any | 5 |
| GET | `/oauth2/authorization/google` | public (browser nav) | 6 |
| POST | `/api/auth/password/setup` | any (session) | 6 |
| POST | `/api/auth/password/forgot` | public | 7 |
| POST | `/api/auth/password/reset` | public | 7 |

### PROFILE
| Method | Path | Role |
|---|---|---|
| GET / PATCH | `/api/users/me/profile` | any |

### ADMIN / ACADEMIC
| Method | Path | Role |
|---|---|---|
| POST/GET/PATCH | `/api/admin/subjects`, `/{subjectId}` | ADMIN |
| POST/GET/PATCH/POST publish/archive | `/api/admin/subjects/{subjectId}/syllabi/...` | ADMIN |
| POST/GET/PUT/PATCH | `/api/admin/semesters`, `/active`, `/{semesterId}` | ADMIN |
| POST/GET/PATCH | `/api/admin/classes`, `/{classId}` | ADMIN |
| POST/GET/PATCH | `/api/admin/courses`, `/{courseId}` | ADMIN |
| GET | `/api/admin/lecturers` | ADMIN |

### ROSTER
| Method | Path | Role |
|---|---|---|
| GET | `/api/admin/courses/{courseId}/roster/template` | ADMIN |
| GET | `/api/admin/courses/{courseId}/roster` | ADMIN |
| POST | `/api/admin/courses/{courseId}/roster/import/preview` | ADMIN |
| POST | `/api/admin/courses/{courseId}/roster/import/confirm` | ADMIN |
| POST | `/api/admin/courses/{courseId}/roster/students` | ADMIN |
| DELETE | `/api/admin/courses/{courseId}/roster/enrollments/{enrollmentId}` | ADMIN |
| DELETE | `/api/admin/courses/{courseId}/roster/invitations/{invitationId}` | ADMIN |

### LECTURER / TEAM
| Method | Path | Role |
|---|---|---|
| GET | `/api/lecturer/courses`, `/{courseId}`, `/{courseId}/roster` | LECTURER/ADMIN |
| GET | `/api/lecturer/courses/{courseId}/teams`, `/teams/template` | LECTURER/ADMIN |
| POST | `/api/lecturer/courses/{courseId}/teams/import/preview`, `/confirm` | LECTURER/ADMIN |
| PUT | `/api/lecturer/courses/{courseId}/teams/{teamId}/leader` | LECTURER/ADMIN |
| PATCH | `/api/lecturer/courses/{courseId}/team-members/{teamMemberId}/team` | LECTURER/ADMIN |

### STUDENT
| Method | Path | Role |
|---|---|---|
| GET | `/api/student/courses` | STUDENT |
| GET | `/api/student/courses/{courseId}/team` | STUDENT |
| GET | `/api/student/project-types` | STUDENT |

### PROJECT
| Method | Path | Role |
|---|---|---|
| GET/POST | `/api/student/courses/{courseId}/project` | STUDENT (POST: Leader only) |
| GET | `/api/projects/{projectId}/integrations` | thành viên |

### JIRA
| Method | Path | Role |
|---|---|---|
| POST | `/api/projects/{projectId}/integrations/jira/connect` | Leader |
| GET | `.../jira/sites`, `.../jira/projects`, `.../jira/boards` | Leader |
| PUT/DELETE | `.../jira` | Leader |
| POST/PATCH/DELETE/GET | `/api/integrations/jira/link`, `/{identityId}/primary`, `/{identityId}`, `/oauth/callback` | any (personal) |

### GITHUB
| Method | Path | Role |
|---|---|---|
| POST | `.../integrations/github/connect` | Leader |
| GET | `.../github/reconnect/candidates`, `.../github/repositories` | Leader |
| PUT/DELETE | `.../github/repositories`, `.../github` | Leader |
| GET | `.../github/setup/callback` | (redirect) |
| POST/PATCH/DELETE/GET | `/api/integrations/github/link`, `/{identityId}/primary`, `/{identityId}`, `/oauth/callback` | any (personal) |

### TASK
| Method | Path | Role |
|---|---|---|
| GET | `/api/projects/{projectId}/tasks`, `/{taskId}`, `/options`, `/{taskId}/transitions`, `/{taskId}/commits` | thành viên |
| POST/PATCH/DELETE | `/api/projects/{projectId}/tasks`, `/{taskId}` | Leader |
| PUT | `.../tasks/{taskId}/sprint` | Leader |
| POST | `.../tasks/{taskId}/transition` | Leader |

### SPRINT
| Method | Path | Role |
|---|---|---|
| GET | `/api/projects/{projectId}/sprints`, `/{sprintId}` | thành viên |
| POST/PATCH/DELETE | `/api/projects/{projectId}/sprints`, `/{sprintId}` | Leader |

### COMMIT
| Method | Path | Role |
|---|---|---|
| GET | `/api/projects/{projectId}/commits` | thành viên |

### SYNC
| Method | Path | Role |
|---|---|---|
| POST | `/api/projects/{projectId}/sync` | Leader |
| GET | `/api/projects/{projectId}/sync-status` | thành viên |

### SSE
| Method | Path | Role |
|---|---|---|
| GET | `/api/projects/{projectId}/events` | thành viên |

### EVIDENCE / CONTRIBUTION
| Method | Path | Role |
|---|---|---|
| POST | `/api/tasks/{taskId}/work-sessions/start`, `/{sessionId}/stop` | thành viên |
| POST | `/api/tasks/{taskId}/contribution-confirmations` | thành viên (cần step-up) |
| GET/POST/DELETE | `/api/tasks/{taskId}/web-links`, `/{linkId}` | thành viên |
| GET/POST/DELETE | `/api/tasks/{taskId}/files`, `/{fileId}` | thành viên |
