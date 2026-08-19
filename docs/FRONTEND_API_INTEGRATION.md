# SAGA Backend — Tích hợp API cho Frontend

File này là **contract tích hợp Backend ↔ Frontend có hiệu lực** khi public API đã được implement.

> **Trạng thái hiện tại:** chưa chốt public API contract.  
> Không tự bịa endpoint path, cookie name, quy tắc lưu token, SSE event name, hoặc response schema từ kế hoạch kiến trúc.

---

## 1. Frontend developer nên đọc gì

Frontend developer thông thường cần:

```text
docs/FRONTEND_API_INTEGRATION.md
+
src/main/java/com/saga/be/controller/
```

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
| Base API path | TBD / Chưa chốt |
| API versioning | TBD / Chưa chốt |
| Login endpoint | TBD / Chưa chốt |
| Logout endpoint | TBD / Chưa chốt |
| Current-user endpoint | TBD / Chưa chốt |
| CSRF contract | TBD / Chưa chốt |
| Session/token transport | TBD / Chưa chốt |
| Error response format | TBD / Chưa chốt |
| Pagination format | TBD / Chưa chốt |
| Graph snapshot endpoint | TBD / Chưa chốt |
| Graph delta/recovery endpoint | TBD / Chưa chốt |
| SSE endpoint | TBD / Chưa chốt |
| SSE event envelope | Đã lên kế hoạch kiến trúc, schema chính xác TBD / Chưa chốt |
| CORS credential policy | TBD / Chưa chốt |

**TBD / Chưa chốt nghĩa là Frontend chưa được hard-code giả định nào.**

Không có endpoint đã chốt cho login, logout, graph, hay SSE. Không dùng path suy đoán.

---

## 3. Authentication contract

Backend sẽ sở hữu trạng thái authentication/tài khoản.

Google OAuth/OIDC có thể là một đường login, nhưng cơ chế session giữa browser và backend **chưa chốt**.

Cho đến khi kiến trúc authentication được chấp nhận:

- không lưu access token suy đoán vào `localStorage`;
- không giả định Bearer token authentication;
- không giả định cookie name;
- không giả định refresh-token endpoint;
- không giả định hành vi CSRF;
- không implement logic Frontend đặc thù theo provider nếu chưa có contract đã thống nhất.

Khi authentication được triển khai, section này phải ghi:

```text
Login flow
Session/token transport
Cookie attributes (nếu dùng)
CSRF behavior
Refresh/re-authentication
Logout
Expired/revoked session behavior
Account-disabled behavior
Google login redirect/callback behavior
```

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

Chưa chốt global public error schema.

Khi thiết kế, mọi API nên dùng một typed error envelope thống nhất.

Không để từng controller tự invent JSON error shape khác nhau.

Contract cuối cùng cần đủ thông tin để FE:

- hiển thị message an toàn cho user;
- phân biệt lỗi validation / auth / authorization / conflict / provider;
- correlate lỗi với backend log khi thích hợp;
- tránh parse exception message.

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

Chưa có endpoint nào được đăng ký.

| Method | Path | Auth | Roles | Status | Owner |
| --- | --- | --- | --- | --- | --- |
| — | — | — | — | CHƯA TRIỂN KHAI | — |

Chỉ thêm dòng đầu tiên sau khi endpoint đã tồn tại.
