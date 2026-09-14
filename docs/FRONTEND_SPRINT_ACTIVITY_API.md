# Hướng dẫn Frontend — Thống kê activity theo sprint

Playbook kéo API khi FE vẽ **dashboard cá nhân / chart task–commit theo từng sprint**. FE **không** tính lại count; chỉ render số backend trả về. Đây **không** phải % đóng góp hay điểm — công thức grade vẫn nằm ở `GET /api/teams/{teamId}/contribution-evaluation`.

Khác `GET /api/projects/{projectId}/progress`:

| | `/progress` | `/analytics/sprint-activity` |
| --- | --- | --- |
| Ai gọi được | Leader hoặc Lecturer | Mọi member ACTIVE + Lecturer đúng course |
| Scope student | Team-wide (Leader) | **Cá nhân** (task assign cho mình + commit mình author) |
| Cắt theo sprint | Chỉ sprint đang `active` | **Mọi** sprint của project (kể cả CLOSED / FUTURE) |

Contract tổng: `docs/FRONTEND_API_INTEGRATION.md`. Task/sprint CRUD: `docs/FE_API_INTEGRATION_GUIDE_VI.md` §19–24. Evidence: `docs/FRONTEND_TASK_EVIDENCE_API.md`.

Base path: `/api` (**không** `/api/v1`). Cookie session `SAGA_SESSION`. Mọi `fetch` dùng `credentials: "include"`. **Không Bearer / JWT.**

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

**GET không CSRF.** Endpoint này chỉ GET.

---

## 1. Lấy `projectId` trước

### Student (mọi thành viên ACTIVE, không chỉ Leader)

```text
GET /api/student/courses
GET /api/student/courses/{courseId}/team      → projectId
GET /api/projects/{projectId}/analytics/sprint-activity
```

- `projectId == null` → Team Leader chưa tạo project. Không gọi analytics; hiện “nhóm chưa setup project”.
- 404 team → chưa vào team.

### Lecturer

```text
GET /api/lecturer/courses/{courseId}/teams    → projectId từng team
GET /api/projects/{projectId}/analytics/sprint-activity
GET /api/projects/{projectId}/analytics/sprint-activity?studentId={studentId}
```

`studentId` là UUID **StudentProfile** (cùng id trong roster / member progress), không phải `userId` tài khoản.

ADMIN **không** gọi được (403) — cùng policy đọc dữ liệu project với `GET /api/projects/{projectId}/tasks`.

---

## 2. Map màn hình → API

### 2.1 Student dashboard — chart của chính mình

```text
GET /api/projects/{projectId}/analytics/sprint-activity
```

Không gửi `studentId`. Backend **bỏ qua** query `studentId` nếu student cố xem teammate — luôn trả data của session hiện tại.

### 2.2 Lecturer — chart cả team

```text
GET /api/projects/{projectId}/analytics/sprint-activity
```

Không `studentId` → tổng task/commit **cả project**, cắt theo từng sprint.

### 2.3 Lecturer — drill-down một sinh viên

```text
GET /api/projects/{projectId}/analytics/sprint-activity?studentId={studentId}
```

Cùng shape JSON, chỉ khác filter (task assignee + commit author = sinh viên đó). Sinh viên phải là member ACTIVE của team sở hữu project, không thì `404 TEAM_NOT_FOUND`.

---

## 3. Quyền

| Caller | Không `studentId` | Có `studentId` |
| --- | --- | --- |
| STUDENT (member ACTIVE) | Data **của mình** | Bị ignore — vẫn data của mình |
| STUDENT không thuộc team | `403 INTEGRATION_FORBIDDEN` | giống cột trái |
| LECTURER đúng course | Data **cả project** | Data cá nhân member đó |
| LECTURER sai course | `403 LECTURER_COURSE_FORBIDDEN` | giống cột trái |
| ADMIN | `403 ACCESS_DENIED` | giống cột trái |

---

## 4. Request

```http
GET /api/projects/{projectId}/analytics/sprint-activity
GET /api/projects/{projectId}/analytics/sprint-activity?studentId={uuid}
```

Không CSRF. Không body.

---

## 5. Response

`200`. `sprints` rỗng (`[]`) nếu project chưa có sprint projection — **không** 404.

```json
{
  "generatedAt": "2026-09-14T10:00:00Z",
  "sprints": [
    {
      "sprintId": "3f1c0a12-8b2e-4d91-9c44-1a2b3c4d5e6f",
      "sprintName": "SAGA Sprint 4",
      "state": "ACTIVE",
      "startDate": "2026-09-10",
      "endDate": "2026-09-24",
      "tasks": {
        "total": 12,
        "todo": 2,
        "inProgress": 3,
        "inReview": 2,
        "done": 5
      },
      "commits": {
        "linked": 18,
        "unlinked": 4
      }
    }
  ]
}
```

Thứ tự sprint: mới nhất trước (`startDate` / `createdAt` desc) — cùng thứ tự `GET /api/projects/{projectId}/sprints`.

Sprint không có việc của scope hiện tại **vẫn trả về**, count = 0. FE dùng để vẽ trục X đủ các sprint.

### Field

| Field | Type | Ghi chú |
| --- | --- | --- |
| `generatedAt` | ISO-8601 UTC (`…Z`) | Thời điểm BE aggregate, không phải last sync Jira/GitHub |
| `sprints[].sprintId` | UUID SAGA | Dùng `id` này, **không** dùng `externalSprintId` Jira |
| `sprints[].sprintName` | string \| null | Tên projection |
| `sprints[].state` | string \| null | Uppercase state Jira: `ACTIVE`, `CLOSED`, `FUTURE` |
| `sprints[].startDate` | `YYYY-MM-DD` \| null | Cắt từ `LocalDateTime` sprint |
| `sprints[].endDate` | `YYYY-MM-DD` \| null | Cắt từ `LocalDateTime` sprint |
| `tasks.total` | number | Mọi status, **gồm BLOCKED** |
| `tasks.todo` | number | `TODO` |
| `tasks.inProgress` | number | `IN_PROGRESS` |
| `tasks.inReview` | number | `IN_REVIEW` |
| `tasks.done` | number | `DONE` |
| `commits.linked` | number | Distinct commit gắn ≥ 1 task **không xóa** thuộc sprint này |
| `commits.unlinked` | number | Commit nằm trong `[startDateTime, endDateTime]` sprint, **chưa** gắn task của sprint này |

Không có field `blocked`. Nếu `total > todo + inProgress + inReview + done` thì phần dư là `BLOCKED` (và status null nếu có). FE **đừng** assert `total === sum(4 cột)`.

Task backlog (`sprint_id` null) **không** vào chart này.

---

## 6. Cách hiểu `linked` / `unlinked`

Dữ liệu đọc từ DB projection — **không** gọi live Jira/GitHub lúc GET.

**Personal (student, hoặc lecturer + `studentId`):**

- Task: `assignee_student_id` = sinh viên đó.
- Commit: `author_student_id` = sinh viên đó.

**Project-wide (lecturer, không `studentId`):** mọi assignee / author trong project.

| | Định nghĩa |
| --- | --- |
| `linked` | Distinct `git_commit` có `task_git_commit_link` tới task còn sống (`deleted_at` null) **trong sprint này**. Một commit gắn nhiều task cùng sprint vẫn đếm **1**. |
| `unlinked` | Commit (cùng scope author) có `committedAt` (fallback `createdAt`) ∈ `[sprint.startDate, sprint.endDate]` **và** không nằm trong tập `linked` của sprint đó. |

Sprint thiếu `startDate` **hoặc** `endDate` → `unlinked` luôn `0`. `linked` vẫn đếm bình thường.

Cửa sổ dùng timestamp đầy đủ trên sprint (không chỉ ngày), inclusive cả hai đầu.

---

## 7. Lỗi thường gặp

| HTTP | `code` | Khi nào | FE làm gì |
| --- | --- | --- | --- |
| 401 | (unauthenticated) | Chưa login / hết session | Redirect login |
| 403 | `INTEGRATION_FORBIDDEN` | Student không thuộc team project | Ẩn dashboard / “không phải thành viên” |
| 403 | `LECTURER_COURSE_FORBIDDEN` | GV không phụ trách course | Không hiện team này |
| 403 | `ACCESS_DENIED` | ADMIN hoặc role không đọc project | Không dùng session admin cho màn student/GV này |
| 404 | `TEAM_NOT_FOUND` | Lecturer truyền `studentId` không phải member ACTIVE | Toast / bỏ drill-down |

Chưa có sprint / chưa có task / chưa connect GitHub → vẫn `200` với `sprints: []` hoặc count 0. **Không** hiện error.

---

## 8. Ví dụ fetch

```js
async function loadMySprintActivity(projectId) {
  const res = await fetch(`/api/projects/${projectId}/analytics/sprint-activity`, {
    credentials: "include",
  });
  if (res.status === 401) {
    // redirect login
    return;
  }
  if (!res.ok) {
    const err = await res.json();
    throw new Error(err.code || res.statusText);
  }
  return res.json();
}

async function loadStudentSprintActivity(projectId, studentId) {
  const res = await fetch(
    `/api/projects/${projectId}/analytics/sprint-activity?studentId=${studentId}`,
    { credentials: "include" },
  );
  return res.json();
}
```

Vẽ chart:

```js
const labels = data.sprints.map((s) => s.sprintName);
const done = data.sprints.map((s) => s.tasks.done);
const linked = data.sprints.map((s) => s.commits.linked);
```

`state === "ACTIVE"` để highlight sprint hiện tại. So sánh case-insensitive nếu cần; BE đã uppercase.

---

## 9. Việc FE không làm

- Không tính % đóng góp / hệ số P từ các số này.
- Không gọi Jira/GitHub từ browser để “bù” count.
- Không dùng `GET /progress` thay cho student dashboard — member thường **403**.
- Không gửi `studentId` từ session student để xem người khác — BE ignore, phí request.
- Không hard-code số sprint hay giả định luôn có đúng 1 sprint `ACTIVE`.

Muốn list sprint thô (tên, goal, ngày): `GET /api/projects/{projectId}/sprints`. Analytics chỉ là aggregate, không thay list đó.
