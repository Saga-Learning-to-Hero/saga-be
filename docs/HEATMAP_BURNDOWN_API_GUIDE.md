# Heatmap và Burndown API

Hai API activity/progress cho dashboard team. **Không** phải % đóng góp hay điểm — công thức grade vẫn ở `GET /api/teams/{teamId}/contribution-evaluation`.

Base path: `/api` (**không** `/api/v1`). Cookie session `SAGA_SESSION`. Mọi `fetch` dùng `credentials: "include"`. **Không Bearer / JWT.** GET không CSRF.

Lỗi luôn:

```json
{ "code": "...", "message": "..." }
```

`courseId` / `teamId` / `sprintId` / `studentId` là UUID SAGA. `studentId` = **StudentProfile**, không phải `GET /api/auth/me` → `user.id`.

Cách lấy id:

```text
GET /api/student/courses/{courseId}/team      → team.id, projectId
GET /api/lecturer/courses/{courseId}/teams    → teams[].id, projectId, members[].studentProfileId
GET /api/projects/{projectId}/sprints         → sprint id UUID
```

Team chưa có project → heatmap/burndown `404 PROJECT_NOT_FOUND`.

---

## 1. Heatmap API

### 1.1 Endpoint

```http
GET /api/courses/{courseId}/teams/{teamId}/heatmap?startDate=2026-08-01&endDate=2026-08-31
GET /api/courses/{courseId}/teams/{teamId}/heatmap?studentId={studentId}&startDate=2026-08-01&endDate=2026-08-31
```

### 1.2 Query parameters

| Param | Bắt buộc | Ghi chú |
| --- | --- | --- |
| `startDate` | có | `YYYY-MM-DD` |
| `endDate` | có | `YYYY-MM-DD`, không trước `startDate` |
| `studentId` | không | Chỉ ACTIVE member của team. Ngoài team → `404 TEAM_NOT_FOUND` |

Khoảng ngày tối đa **366** ngày (inclusive). Sai khoảng / thiếu ngày → `400 REQUEST_INVALID`.

Không `studentId`: heatmap **cả team**. Có `studentId`: chỉ sinh viên đó (`students` 1 phần tử; `days` = tổng của người đó).

### 1.3 Response

`200`. Team chưa có activity vẫn 200, count = 0. Mỗi sinh viên ACTIVE luôn có `cells` đủ ngày trong range (kể cả 0).

```json
{
  "courseId": "...",
  "teamId": "...",
  "studentId": null,
  "startDate": "2026-08-01",
  "endDate": "2026-08-31",
  "students": [
    {
      "studentId": "...",
      "studentCode": "SE170102",
      "fullName": "Nguyễn Văn A",
      "commits": 4,
      "peerReviews": 1,
      "comments": 2,
      "documents": 1,
      "tasks": 3,
      "totalActivities": 11,
      "totalScore": 21,
      "cells": [
        {
          "date": "2026-08-01",
          "commits": 1,
          "peerReviews": 0,
          "comments": 0,
          "documents": 0,
          "tasks": 1,
          "totalActivities": 2,
          "totalScore": 5
        }
      ]
    }
  ],
  "days": []
}
```

`days` là tổng team (hoặc tổng sinh viên đang filter) theo ngày — cùng shape với `cells`.

### 1.4 Nguồn đếm và điểm

Đọc projection MySQL, không gọi live GitHub/Jira. Ngày lấy từ timestamp local (`toLocalDate()`).

| Field | Nguồn | Timestamp | Điểm / event |
| --- | --- | --- | --- |
| `commits` | `git_commit` đã map `author_student_id` | `committedAt` (fallback `createdAt`) | 3 |
| `peerReviews` | `peer_review` đã nộp (`starRating` ≠ null), theo **reviewer** | `createdAt` | 2 |
| `comments` | `comment` đã map author, gắn task/PR/issue của project | `createdAt` | 1 |
| `documents` | `task_file` + `task_web_link` + `task_attachment` trên task chưa xóa | `createdAt` | 1 |
| `tasks` | task chưa xóa, đã gán sinh viên | `createdAt` | 2 |

`totalActivities` = tổng 5 cột.  
`totalScore` = `3*commits + 2*peerReviews + 1*comments + 1*documents + 2*tasks`.

Document: ưu tiên user upload (`createdBy` → StudentProfile); không có thì gán assignee của task. Attachment Jira không có uploader → assignee.

Event không map được student ACTIVE của team **bị bỏ**. FE tô màu theo `totalScore` (thấp / trung / cao là việc UI).

### 1.5 FE render

1. Trục dọc = `students`.
2. Trục ngang = `cells[].date` (đã đủ ngày, không cần tự pad).
3. Tooltip = breakdown 5 loại + `totalScore`.
4. `studentId` trên root `null` khi xem cả team.

---

## 2. Burndown API

### 2.1 Endpoint

```http
GET /api/courses/{courseId}/teams/{teamId}/sprints/{sprintId}/burndown
```

`sprintId` phải thuộc project của team. Sai sprint / sprint xóa mềm → `404 PROJECT_NOT_FOUND` (“Sprint was not found for this project.”).

Sprint thiếu `startDate` hoặc `endDate` → `400 REQUEST_INVALID`.

### 2.2 Response

```json
{
  "courseId": "...",
  "teamId": "...",
  "sprintId": "...",
  "sprintName": "Sprint 3",
  "startDate": "2026-08-01",
  "endDate": "2026-08-07",
  "totalScope": 10,
  "points": [
    {
      "date": "2026-08-01",
      "idealRemaining": 10,
      "actualRemaining": 10,
      "doneCount": 0
    }
  ]
}
```

`totalScope` = số task **hiện** thuộc sprint (chưa xóa mềm). Không có changelog nên task thêm giữa sprint vẫn nằm trong scope từ ngày đầu.

Mỗi ngày inclusive từ `startDate` → `endDate`:

- `idealRemaining` = giảm tuyến tính nguyên: `totalScope * (lastIndex - i) / lastIndex`. Ngày đầu = `totalScope`, ngày cuối = `0`. Sprint 1 ngày → `idealRemaining = 0`.
- `doneCount` = task `status === DONE` có mốc hoàn thành ≤ ngày đó (`completedAt`, fallback `resolvedAt`, rồi `createdAt`).
- `actualRemaining` = `totalScope - doneCount`.

Task không `DONE` không bao giờ vào `doneCount`.

### 2.3 FE render

Hai đường: X = ngày, Y = task còn lại. `actualRemaining` cao hơn `idealRemaining` = chậm.

```js
const res = await fetch(
  `/api/courses/${courseId}/teams/${teamId}/sprints/${sprintId}/burndown`,
  { credentials: "include" }
);
const data = await res.json();
const chartData = data.points.map((p) => ({
  date: p.date,
  ideal: p.idealRemaining,
  actual: p.actualRemaining,
  done: p.doneCount,
}));
```

---

## 3. Quyền

Cùng `ProjectDataAuthorization.requireReader` với `GET /api/projects/{projectId}/tasks`.

| Caller | Kết quả |
| --- | --- |
| STUDENT member ACTIVE của team | 200 — heatmap cả team (và teammate nếu biết `studentId`) |
| STUDENT không thuộc team | `403 INTEGRATION_FORBIDDEN` |
| LECTURER đúng course | 200 |
| LECTURER sai course | `403 LECTURER_COURSE_FORBIDDEN` |
| ADMIN | `403 ACCESS_DENIED` |
| Chưa login | 401 |

`courseId` + `teamId` không khớp → `404 TEAM_NOT_FOUND`.

---

## 4. So sánh nhanh

| | Heatmap | Burndown |
| --- | --- | --- |
| Mục tiêu | Hoạt động theo người và ngày | Tiến độ task trong sprint |
| Phạm vi thời gian | `startDate`–`endDate` query | Ngày sprint |
| Lọc | `studentId` optional | `sprintId` trên path |
| SoT điểm? | Không | Không |

Khác `GET /api/projects/{projectId}/analytics/sprint-activity`: API đó là count task/commit **theo sprint** (personal cho student). Heatmap/burndown là lưới ngày + đường remaining.

---

## 5. Việc FE không làm

- Không tính % đóng góp từ `totalScore`.
- Không gọi GitHub/Jira từ browser để bù count.
- Không dùng `/api/v1/...`.
- Không lấy avatar GitHub cho heatmap — đây không phải graph node.
