# Hướng dẫn Frontend — Gắn link và file vào task

Playbook kéo API khi sinh viên nộp **URL** hoặc **file** vào một task trên SAGA. Dùng làm bằng chứng DOCUMENT / RESEARCH (DEC-090 / DEC-092). FE **không** tính điểm; chỉ gắn evidence rồi (nếu là Leader) gọi lại evaluate.

Task CRUD (tạo / sửa / transition / sprint): `docs/FE_API_INTEGRATION_GUIDE_VI.md` §19–24. Contract đóng góp: `docs/FRONTEND_API_INTEGRATION.md` §18. Luồng %: `docs/FRONTEND_CONTRIBUTION_API.md`.

Base path: `/api`. Cookie session `SAGA_SESSION`. Mọi `fetch` dùng `credentials: "include"`. **Không Bearer / JWT.**

Lỗi luôn:

```json
{ "code": "...", "message": "..." }
```

---

## 0. Auth + CSRF

Mọi request cần session (`POST /api/auth/login` hoặc Google).

| | Cookie | Ghi chú |
| --- | --- | --- |
| Session | `SAGA_SESSION` (HttpOnly) | Browser tự gửi |
| CSRF | `XSRF-TOKEN` | Đọc được từ JS |

**GET không CSRF.** Mọi `POST` / `DELETE`:

1. `GET /api/auth/csrf` (hoặc GET bất kỳ đã set cookie CSRF)
2. Header `X-XSRF-TOKEN` = giá trị cookie `XSRF-TOKEN`

Upload file **vẫn** gửi CSRF. Không nhét CSRF vào body multipart.

```js
async function csrfHeader() {
  const res = await fetch("/api/auth/csrf", { credentials: "include" });
  const { token } = await res.json();
  return { "X-XSRF-TOKEN": token };
}
```

---

## 1. Lấy hoặc tạo `taskId` trước khi gắn evidence

Task sống trên SAGA dưới dạng **projection Jira**. Hai cách có `taskId`:

1. **Tạo trên SAGA** — Team Leader `POST /api/projects/{projectId}/tasks`. Backend tạo issue trên Jira **đồng bộ**, rồi lưu projection và trả `ProjectTaskResponse` (`201`). Dùng `id` ngay, không cần refetch list.
2. **Đọc list** — `GET /api/projects/{projectId}/tasks` (cả task tạo từ SAGA lẫn issue tạo trực tiếp trên Jira rồi webhook/sync về).

When creating a Jira subtask, keep the two parent fields distinct: `parentTaskId` is the optional
native SAGA hierarchy, while `jiraParentTaskId` selects a same-source local Task whose canonical
Jira `externalId` is sent as `fields.parent.id`. Set both to the same UUID only when both
hierarchies should align. A Jira subtask may use `jiraParentTaskId` without `parentTaskId`; never
send a parent from another Jira source.

Project phải đã connect Jira (`GET /api/projects/{projectId}/integrations` → `jira.status === "ACTIVE"`). Chưa có project / chưa connect → không tạo được task.

### Failover migration metadata

Task list and task detail retain both historical Jira source Tasks and their failover-created target
Tasks. `ProjectTaskResponse.migration` is a one-hop, non-recursive summary:

```json
{
  "migratedFrom": { "taskId": "...", "externalKey": "SAGA-3", "jiraIntegrationId": "...", "runId": "..." },
  "migratedTo": null,
  "superseded": false
}
```

`migratedFrom` and `migratedTo` may both be present for a middle Task in a migration chain.
When `superseded` is `true`, render the source Task as historical; it remains readable and its
original evidence remains attached to it. It is excluded from current-work aggregates, while the
target remains the canonical current work item. The API never redirects the source Task, transfers
evidence, or recursively serializes the chain.

CRUD đầy đủ (options, PATCH, transition, sprint, xóa): `docs/FE_API_INTEGRATION_GUIDE_VI.md` §19.

### Student — bootstrap `projectId`

```text
GET /api/student/courses
GET /api/student/courses/{courseId}/team      → projectId, myRole (LEADER mới POST được)
GET /api/student/courses/{courseId}/project   → projectId (nếu team đã setup)
GET /api/projects/{projectId}/tasks           → id của từng task
```

`projectId == null` → chưa có project, không có task để gắn.

### Lecturer

```text
GET /api/lecturer/courses/{courseId}/teams    → projectId từng team
GET /api/projects/{projectId}/tasks
```

Lecturer **đọc** task, **không** POST/PATCH/DELETE task (chỉ Leader). List: student trong team sở hữu project, hoặc lecturer đúng course.

### Tạo task (Leader, CSRF)

```text
GET  /api/projects/{projectId}/tasks/options   (mọi member — dựng form)
POST /api/projects/{projectId}/tasks           (chỉ Leader)
```

```json
POST /api/projects/{projectId}/tasks
{
  "summary": "Viết báo cáo SRS",
  "description": "optional, tối đa 10000",
  "issueTypeId": "từ /tasks/options",
  "assigneeAccountId": "Jira accountId từ options.assignableUsers",
  "priorityId": "từ /tasks/options",
  "storyPoints": 5,
  "sprintId": 123
}
```

`summary` bắt buộc, tối đa 255. `assigneeAccountId` là **Jira accountId**, không phải SAGA `userId`/`studentId`. Không phải Leader → `403 NOT_TEAM_LEADER`.

Response `201` — lấy `id` làm `taskId` cho web-links / files:

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "externalId": "10001",
  "externalKey": "SWP-12",
  "title": "Viết báo cáo SRS",
  "description": null,
  "status": "TODO",
  "jiraStatusName": "To Do",
  "issueTypeName": "Story",
  "assigneeExternalId": null,
  "assigneeDisplayName": null,
  "assigneeStudentId": null,
  "assignee": null,
  "priority": null,
  "storyPoint": 5,
  "sprint": null,
  "linkedCommitCount": 0,
  "externalUpdatedAt": "2026-09-08T09:00:00",
  "createdAt": "2026-09-08T09:00:00",
  "updatedAt": "2026-09-08T09:00:00"
}
```

`status`: `TODO` | `IN_PROGRESS` | `IN_REVIEW` | `DONE` | `BLOCKED`. Field điểm là **`storyPoint`** (số ít); request tạo dùng **`storyPoints`**.

`GET /tasks` **không** trả labels. Scoring vẫn đọc nhãn `saga:code|test|document|research` từ DB (Jira). `POST /tasks` hiện **không** nhận field label — gắn `saga:document` / `saga:research` trên Jira (hoặc sync về). FE vẫn cho nộp file/link trên mọi task; backend chỉ **công nhận điểm** khi DONE + đúng một nhãn DOCUMENT/RESEARCH + ≥1 evidence.

---

## 2. Khi nào cần gắn link / file

| Nhãn Jira trên task | Cần evidence? | Ghi chú |
| --- | --- | --- |
| `saga:code` | Không | Commit không cộng điểm; không cần file/link |
| `saga:test` | Không | |
| `saga:document` | **Có** | ≥1 attachment Jira **hoặc** web link **hoặc** file SAGA |
| `saga:research` | **Có** | Giống DOCUMENT |

- Số lượng file/link **không** tăng điểm. Một cái là đủ.
- Sai/thiếu/hai nhãn, chưa DONE, chưa gắn sprint → không vào tiêu chí đóng góp.
- `storyPoint` null → backend tính như `1`.

Hai nguồn evidence độc lập:

| Nguồn | API | Ai nộp |
| --- | --- | --- |
| URL (Google Doc, Confluence, Drive, …) | `/api/tasks/{taskId}/web-links` | Team member |
| File tài liệu / ảnh | `/api/tasks/{taskId}/files` | Team member |
| Attachment / remote link từ Jira | Sync tự động | Không nộp qua SAGA |

Row sync từ Jira có `source: "JIRA"` — FE **không xóa** được.

---

## 3. Quyền

| API | Team member | Lecturer đúng course | ADMIN |
| --- | --- | --- | --- |
| `GET/POST/DELETE .../web-links` | Có | Không (403) | Không (403) |
| `GET .../files` | Có | Có | Có |
| `POST/DELETE .../files` | Có | Không | Không |
| `GET .../files/{fileId}` (download) | Có | Có | Có |

Không phải member → `403 INTEGRATION_FORBIDDEN` (`Not a member of this team.`).  
Task không tồn tại / đã xóa → `404 TASK_NOT_FOUND`.

Member (kể cả không phải Leader) **được** nộp file/link. Chỉ Leader mới xem `%` evaluate.

---

## 4. Gắn URL (`web-links`)

```http
GET    /api/tasks/{taskId}/web-links
POST   /api/tasks/{taskId}/web-links
DELETE /api/tasks/{taskId}/web-links/{linkId}
```

List theo `createdAt` tăng dần.

### 4.1 Tạo

`201 Created`. CSRF. JSON.

```http
POST /api/tasks/{taskId}/web-links
Content-Type: application/json
X-XSRF-TOKEN: <cookie XSRF-TOKEN>
```

```json
{
  "url": "https://docs.google.com/document/d/abc/edit",
  "title": "Spec báo cáo"
}
```

| Field | Bắt buộc | Rule |
| --- | --- | --- |
| `url` | Có | Trim; `http://` hoặc `https://`; có host; không khoảng trắng / xuống dòng; tối đa **2048** |
| `title` | Không | Trim; blank → `null`; tối đa **255** |

URL giống nhau trên **cùng task** (sau normalize/hash) → `409 TASK_WEB_LINK_DUPLICATE`. Task khác được phép trùng URL.

`javascript:`, `ftp:`, URL không scheme → `400 TASK_WEB_LINK_INVALID`. Body thiếu `url` → `400 REQUEST_INVALID`.

```js
const headers = await csrfHeader();
const res = await fetch(`/api/tasks/${taskId}/web-links`, {
  method: "POST",
  credentials: "include",
  headers: { ...headers, "Content-Type": "application/json" },
  body: JSON.stringify({
    url: "https://docs.google.com/document/d/abc/edit",
    title: "Spec báo cáo",
  }),
});
```

### 4.2 Response

```json
{
  "id": "8c2e1a10-4b11-4d22-9f33-aaaaaaaaaaaa",
  "taskId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "url": "https://docs.google.com/document/d/abc/edit",
  "title": "Spec báo cáo",
  "source": "SAGA",
  "createdByUserId": "…",
  "createdAt": "2026-09-08T10:00:00"
}
```

| Field | Ý nghĩa |
| --- | --- |
| `id` | `linkId` cho DELETE |
| `source` | `"SAGA"` nộp trên app; `"JIRA"` sync từ issue (remote/web link) |
| `createdByUserId` | User nộp; có thể `null` với row Jira |

### 4.3 Xóa

`204 No Content`. CSRF. Chỉ xóa được `source = SAGA`.

```http
DELETE /api/tasks/{taskId}/web-links/{linkId}
```

- Sai `linkId` hoặc link không thuộc `taskId` → `404 TASK_WEB_LINK_NOT_FOUND`
- `source = JIRA` → `409 TASK_EVIDENCE_JIRA_IMMUTABLE`

UI: badge `JIRA` → disable nút xóa.

---

## 5. Gắn file (`files`)

```http
GET    /api/tasks/{taskId}/files
POST   /api/tasks/{taskId}/files
GET    /api/tasks/{taskId}/files/{fileId}
DELETE /api/tasks/{taskId}/files/{fileId}
```

List theo `createdAt` tăng dần. File **không** lưu BLOB MySQL; disk `data/task-files`. FE không đọc path đó.

### 5.1 Upload

`201 Created`. CSRF. **Multipart**, không JSON.

| | Giá trị |
| --- | --- |
| `Content-Type` | `multipart/form-data` (browser tự set boundary) |
| Field name | **`file`** (đúng tên này) |
| CSRF | Header `X-XSRF-TOKEN` |
| Tối đa | **10MB** / file |
| Số file `SAGA` / task | **20** (file `JIRA` không tính vào hạn này) |

```js
const headers = await csrfHeader();
const form = new FormData();
form.append("file", fileInput.files[0]); // HTML input type=file

const res = await fetch(`/api/tasks/${taskId}/files`, {
  method: "POST",
  credentials: "include",
  headers, // không set Content-Type tay
  body: form,
});
```

Loại cho phép (theo **đuôi file**, backend còn check magic bytes):

| Nhóm | Extension |
| --- | --- |
| Tài liệu | `pdf`, `doc`, `docx`, `xls`, `xlsx`, `ppt`, `pptx` |
| Ảnh | `png`, `jpg`, `jpeg`, `gif`, `webp` |
| Text | `txt`, `csv`, `md` |

Exe / ELF bị từ chối. Content-Type khai báo lệch đuôi (trừ vài alias như `image/jpg`, CSV `text/plain`) → `400 TASK_FILE_INVALID`.

Cùng nội dung (SHA-256) trên **cùng task** → `409 TASK_FILE_DUPLICATE`. Đổi tên file không giúp nếu bytes giống nhau.

### 5.2 Response metadata (list / upload)

```json
{
  "id": "7b1c9e20-1111-2222-3333-bbbbbbbbbbbb",
  "taskId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "filename": "bao-cao.pdf",
  "mimeType": "application/pdf",
  "sizeBytes": 204800,
  "source": "SAGA",
  "createdByUserId": "…",
  "createdAt": "2026-09-08T10:00:00"
}
```

| Field | Ý nghĩa |
| --- | --- |
| `id` | `fileId` cho download / DELETE |
| `filename` | Tên gốc đã sanitize (bỏ path) |
| `mimeType` | Do backend gán theo extension, không tin client |
| `source` | `"SAGA"` hoặc `"JIRA"` |

### 5.3 Download

`200`. Session, **không** CSRF. Body = bytes file.

```http
GET /api/tasks/{taskId}/files/{fileId}
```

Header:

```text
Content-Type: application/pdf
Content-Disposition: attachment; filename="bao-cao.pdf"
```

Dùng `<a href>` / `window.open` **không** gửi cookie cross-origin đúng cách nếu FE/BE khác origin — hãy `fetch` + blob:

```js
const res = await fetch(`/api/tasks/${taskId}/files/${fileId}`, {
  credentials: "include",
});
const blob = await res.blob();
const url = URL.createObjectURL(blob);
const a = document.createElement("a");
a.href = url;
a.download = filenameFromList;
a.click();
URL.revokeObjectURL(url);
```

Không có API preview inline. `Content-Disposition: attachment` — FE tự quyết định mở tab hay save.

### 5.4 Xóa

`204 No Content`. CSRF. Chỉ xóa `source = SAGA`.

```http
DELETE /api/tasks/{taskId}/files/{fileId}
```

- Sai id / file không thuộc task → `404 TASK_FILE_NOT_FOUND`
- `source = JIRA` → `409 TASK_EVIDENCE_JIRA_IMMUTABLE`

---

## 6. Jira sync (để FE không nhầm)

Sau khi team connect Jira Cloud:

- Webhook issue + job INITIAL đẩy attachment **bytes** vào `task_file` (`source: JIRA`)
- Remote / web link Jira vào `task_web_link` (`source: JIRA`)
- Snapshot Jira **không** xóa link/file `SAGA`
- Metadata Jira vẫn vào `task_attachment` (cũng đủ điều kiện điểm DOCUMENT/RESEARCH)

FE:

- Hiện cả hai nguồn trên cùng list
- Badge `JIRA` / `SAGA`
- Ẩn hoặc disable xóa khi `source === "JIRA"`
- Không gọi API Jira trực tiếp để nộp evidence

---

## 7. Lỗi

| HTTP | `code` | Khi nào | Gợi ý UI |
| --- | --- | --- | --- |
| 401 | (unauthenticated) | Thiếu cookie session | Đưa về login |
| 403 | `INTEGRATION_FORBIDDEN` | Không phải team member | Ẩn form nộp |
| 404 | `TASK_NOT_FOUND` | Sai `taskId` | Refresh list task |
| 404 | `TASK_WEB_LINK_NOT_FOUND` | Sai `linkId` | Refresh list link |
| 404 | `TASK_FILE_NOT_FOUND` | Sai `fileId` | Refresh list file |
| 400 | `REQUEST_INVALID` | JSON thiếu/sai field (`url` blank, …) | Validate form |
| 400 | `TASK_WEB_LINK_INVALID` | URL không http(s), có space, quá dài, không host | Toast message |
| 400 | `TASK_FILE_INVALID` | Thiếu file, sai loại, magic không khớp, exe | Chọn lại file |
| 400 | `TASK_FILE_TOO_LARGE` | > 10MB (kể cả Spring `MaxUploadSizeExceeded`) | Báo hạn 10MB |
| 409 | `TASK_WEB_LINK_DUPLICATE` | Cùng URL trên task | Highlight link đã có |
| 409 | `TASK_FILE_DUPLICATE` | Cùng nội dung trên task | “File này đã được nộp” |
| 409 | `TASK_FILE_LIMIT` | Đã 20 file `SAGA` | Chặn upload, gợi ý xóa file cũ |
| 409 | `TASK_EVIDENCE_JIRA_IMMUTABLE` | Xóa evidence sync Jira | Disable nút xóa |
| 500 | `TASK_FILE_STORE_FAILED` | Lỗi ghi/đọc disk | Retry / báo backend |

`message` đủ để hiện toast. Đừng parse message để rẽ nhánh — dùng `code`.

---

## 8. Gợi ý màn hình task

```text
login + CSRF
→ lấy projectId
→ (Leader, task mới) GET .../tasks/options → POST /api/projects/{projectId}/tasks → dùng response.id
→ (hoặc) GET /api/projects/{projectId}/tasks → user chọn task
→ song song:
     GET /api/tasks/{taskId}/web-links
     GET /api/tasks/{taskId}/files
→ form:
     URL + title → POST web-links
     <input type="file"> → POST files (field "file")
→ xóa: DELETE nếu source === "SAGA"
→ download: GET files/{fileId} → blob
→ (Leader) GET /api/teams/{teamId}/contribution-evaluation
```

Checklist UI:

- Member: hiện form thêm URL + chọn file.
- Lecturer/Admin: list + download file; **không** gọi POST/DELETE; **không** gọi GET web-links (sẽ 403) trừ khi sau này backend mở quyền đọc.
- Validate client: URL bắt đầu `http://`/`https://`; file ≤ 10MB; đuôi trong whitelist — vẫn xử lý 400 từ server.
- Sau POST thành công: append item vào list (hoặc GET lại). Leader refresh evaluate.
- `source === "JIRA"`: khóa xóa, tooltip “Đồng bộ từ Jira”.

---

## 9. Việc API evidence này không làm

- CRUD task — dùng `/api/projects/{projectId}/tasks` (mục 1 và `FE_API_INTEGRATION_GUIDE_VI.md` §19).
- Sửa URL hay thay file in-place — xóa row `SAGA` rồi tạo mới.
- Preview PDF/ảnh phía server.
- Nộp GitHub attachment (không ingest).
- Gắn nhãn `saga:document` / `saga:research` khi `POST /tasks` — nhãn vẫn lấy từ Jira.
