# Hướng dẫn Frontend — Heatmap và Burndown

Playbook kéo **2 API activity** khi FE vẽ lưới heatmap (GitHub-style) và biểu đồ burndown sprint. FE **không** tính lại count; chỉ render số backend trả về. Đây **không** phải % đóng góp — công thức grade vẫn ở `GET /api/teams/{teamId}/contribution-evaluation`.

Khác các API gần đó:

| | Heatmap / Burndown | Sprint activity | Graph Cytoscape |
| --- | --- | --- | --- |
| Path | `/api/courses/{courseId}/teams/{teamId}/...` | `/api/projects/{projectId}/analytics/sprint-activity` | `/api/projects/{projectId}/graph/...` |
| Trục | Ngày (+ sinh viên) | Sprint | Node/cạnh |
| Dùng khi | Lưới hoạt động, đường remaining thật | Chart task–commit theo sprint | Canvas giải thích contribution |

Contract tổng: `docs/FRONTEND_API_INTEGRATION.md`. Sprint list: `GET /api/projects/{projectId}/sprints`. Chart theo sprint: `docs/FRONTEND_SPRINT_ACTIVITY_API.md`. Graph: `docs/FRONTEND_GRAPH_API.md`.

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

**GET không CSRF.** Cả hai endpoint đều GET. Không body.

---

## 1. Lấy `courseId` / `teamId` / `sprintId` / `studentId` trước

Mọi UUID là **SAGA**, không dùng `externalSprintId` Jira, không dùng `GET /api/auth/me` → `user.id`.

`studentId` = UUID **StudentProfile** — cùng id với `assignee.studentId` trên task, `candidates[].studentId` peer review, `members[].studentProfileId` phía lecturer.

### Student (mọi thành viên ACTIVE, không chỉ Leader)

```text
GET /api/student/courses
GET /api/student/courses/{courseId}/team      → id (teamId), projectId, members
GET /api/projects/{projectId}/sprints         → id UUID từng sprint (burndown)
```

- `projectId == null` → Leader chưa tạo project. **Không** gọi heatmap/burndown; hiện “nhóm chưa setup project”. BE cũng `404 PROJECT_NOT_FOUND`.
- 404 team → chưa vào team.
- Heatmap **không** cần `projectId` trên path, nhưng vẫn cần team đã có project.

Student **được** xem heatmap cả team (và teammate nếu biết `studentId`). Khác `sprint-activity`: API đó luôn scope về chính mình.

### Lecturer

```text
GET /api/lecturer/courses/{courseId}/teams
  → teams[].id, projectId, members[].studentProfileId
GET /api/projects/{projectId}/sprints
```

ADMIN **không** gọi được (`403 ACCESS_DENIED`) — cùng policy đọc Task/Commit.

---

## 2. Map màn hình → API

| Màn hình | Gọi |
| --- | --- |
| Lưới hoạt động cả nhóm | `GET /api/courses/{courseId}/teams/{teamId}/heatmap?startDate=&endDate=` |
| Heatmap 1 sinh viên | thêm `&studentId={studentId}` |
| Burndown 1 sprint | `GET /api/courses/{courseId}/teams/{teamId}/sprints/{sprintId}/burndown` |

Dropdown sprint = `GET /api/projects/{projectId}/sprints` → dùng `id` cho burndown.  
Range heatmap mặc định hợp lý: tháng hiện tại, hoặc `startDate`/`endDate` của sprint đang chọn (cùng format `YYYY-MM-DD`).

Không gọi heatmap + burndown + 5 graph song song lúc mount nếu chưa mở tab.

---

## 3. Quyền

Cùng `ProjectDataAuthorization.requireReader` với `GET /api/projects/{projectId}/tasks`.

| Caller | Heatmap không `studentId` | Heatmap có `studentId` | Burndown |
| --- | --- | --- | --- |
| STUDENT member ACTIVE | 200 — cả team | 200 nếu target ACTIVE cùng team | 200 |
| STUDENT không thuộc team | `403 INTEGRATION_FORBIDDEN` | giống cột trái | giống cột trái |
| LECTURER đúng course | 200 | 200 nếu target ACTIVE cùng team | 200 |
| LECTURER sai course | `403 LECTURER_COURSE_FORBIDDEN` | giống cột trái | giống cột trái |
| ADMIN | `403 ACCESS_DENIED` | giống cột trái | giống cột trái |
| Chưa login | 401 | 401 | 401 |

`courseId` + `teamId` không khớp → `404 TEAM_NOT_FOUND`.  
`studentId` không phải member ACTIVE → `404 TEAM_NOT_FOUND`.  
`sprintId` không thuộc project của team / đã xóa mềm → `404 PROJECT_NOT_FOUND`.

---

## 4. Heatmap — request

```http
GET /api/courses/{courseId}/teams/{teamId}/heatmap?startDate={YYYY-MM-DD}&endDate={YYYY-MM-DD}
GET /api/courses/{courseId}/teams/{teamId}/heatmap?studentId={uuid}&startDate={YYYY-MM-DD}&endDate={YYYY-MM-DD}
```

| Param | Bắt buộc | Default | Ghi chú |
| --- | --- | --- | --- |
| `startDate` | có | — | `YYYY-MM-DD` |
| `endDate` | có | — | không trước `startDate` |
| `studentId` | không | cả team | UUID StudentProfile |

Khoảng tối đa **366 ngày** (inclusive). Thiếu param / sai format / range quá dài → `400 REQUEST_INVALID`.

---

## 5. Heatmap — response

`200`. Chưa có commit/task vẫn 200, count = 0 — **không** 404.

Mỗi member ACTIVE luôn có đủ `cells` cho mọi ngày trong range (kể cả 0). FE **không** tự pad ngày trống.

```ts
interface HeatmapResponse {
  courseId: string;
  teamId: string;
  studentId: string | null; // null = cả team
  startDate: string;        // YYYY-MM-DD
  endDate: string;
  students: StudentHeatmap[];
  days: HeatmapCell[];      // tổng team (hoặc tổng 1 SV nếu đang filter)
}

interface StudentHeatmap {
  studentId: string;
  studentCode: string | null;
  fullName: string | null;
  commits: number;
  peerReviews: number;
  documents: number;
  tasks: number;
  totalActivities: number;
  cells: HeatmapCell[];     // cùng số phần tử với số ngày trong range
}

interface HeatmapCell {
  date: string;             // YYYY-MM-DD
  commits: number;
  peerReviews: number;
  documents: number;
  tasks: number;
  totalActivities: number;
}
```

`days[i]` cùng `date` với `students[*].cells[i]`. Filter `studentId` → `students` 1 phần tử, `days` = cells của người đó, root `studentId` = UUID đó.

### Count trên ô

Không có điểm nỗ lực / hệ số. FE tô màu theo `totalActivities` (số event), không nhân trọng số.

| Field | Ý nghĩa |
| --- | --- |
| `commits` | Commit đã map sinh viên |
| `peerReviews` | Peer review **đã nộp** (sao), theo người chấm |
| `documents` | File / link / attachment trên task |
| `tasks` | Task đã gán sinh viên (ngày tạo projection) |

```text
totalActivities = commits + peerReviews + documents + tasks
```

Đây là **mức hoạt động**, không phải điểm môn. Commit chưa map identity không vào lưới. Không có field `comments` hay `totalScore`.

Gợi ý màu (UI, không phải contract BE): thấp / trung / cao theo `cell.totalActivities`. Tooltip = 4 cột count.

---

## 6. Burndown — request / response

```http
GET /api/courses/{courseId}/teams/{teamId}/sprints/{sprintId}/burndown
```

Không query. Ngày lấy từ sprint (`startDate` / `endDate` cắt `YYYY-MM-DD`). Sprint thiếu một trong hai → `400 REQUEST_INVALID`.

```ts
interface BurndownChartResponse {
  courseId: string;
  teamId: string;
  sprintId: string;
  sprintName: string | null;
  startDate: string;   // YYYY-MM-DD
  endDate: string;
  totalScope: number;  // số task hiện thuộc sprint (chưa xóa)
  points: BurndownPoint[];
}

interface BurndownPoint {
  date: string;
  actualRemaining: number;
  doneCount: number;
}
```

`points` đủ ngày inclusive từ `startDate` → `endDate`.

- `doneCount`: task `DONE` có mốc hoàn thành ≤ ngày đó.
- `actualRemaining` = `totalScope - doneCount`.

Không có `idealRemaining`. Không có changelog: task thêm giữa sprint vẫn nằm trong `totalScope` từ ngày đầu.

---

## 7. Lỗi thường gặp

| HTTP | `code` | Khi nào | UI |
| --- | --- | --- | --- |
| 401 | (unauthenticated) | Hết session | Về login |
| 403 | `INTEGRATION_FORBIDDEN` | Student không thuộc team | Ẩn dashboard |
| 403 | `LECTURER_COURSE_FORBIDDEN` | GV sai course | Ẩn team này |
| 403 | `ACCESS_DENIED` | ADMIN hoặc role khác | Không dùng session admin |
| 404 | `TEAM_NOT_FOUND` | Sai `teamId`/`courseId`, hoặc `studentId` không ACTIVE | Toast; bỏ filter SV |
| 404 | `PROJECT_NOT_FOUND` | Team chưa có project, hoặc sai `sprintId` | “Chưa setup project” / đổi sprint |
| 400 | `REQUEST_INVALID` | Thiếu/sai ngày, range > 366 ngày, sprint không có start/end | Toast; kiểm tra date picker |

Chưa có activity / sprint 0 task → vẫn `200`, count 0. **Không** hiện error.

CORS: origin FE phải nằm `SAGA_AUTH_FRONTEND_ORIGINS`. Local: `http://localhost:3000` + `credentials: "include"`.

---

## 8. Ví dụ fetch

```js
async function loadTeamHeatmap(courseId, teamId, startDate, endDate) {
  const q = new URLSearchParams({ startDate, endDate });
  const res = await fetch(
    `/api/courses/${courseId}/teams/${teamId}/heatmap?${q}`,
    { credentials: "include" },
  );
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

async function loadStudentHeatmap(courseId, teamId, studentId, startDate, endDate) {
  const q = new URLSearchParams({ startDate, endDate, studentId });
  const res = await fetch(
    `/api/courses/${courseId}/teams/${teamId}/heatmap?${q}`,
    { credentials: "include" },
  );
  return res.json();
}

async function loadBurndown(courseId, teamId, sprintId) {
  const res = await fetch(
    `/api/courses/${courseId}/teams/${teamId}/sprints/${sprintId}/burndown`,
    { credentials: "include" },
  );
  return res.json();
}
```

Vẽ heatmap:

```js
const rows = data.students.map((s) => ({
  label: s.fullName || s.studentCode,
  scores: s.cells.map((c) => c.totalActivities),
}));
const dates = data.days.map((d) => d.date);
```

Vẽ burndown:

```js
const chartData = data.points.map((p) => ({
  date: p.date,
  actual: p.actualRemaining,
  done: p.doneCount,
}));
```

---

## 9. Việc FE không làm

- Không tính % đóng góp / story point từ `totalActivities` hay `doneCount`.
- Không gọi Jira/GitHub từ browser để “bù” commit.
- Không dùng `/api/v1/courses/...`.
- Không pad ngày trống — BE đã trả đủ `cells`.
- Không bịa `comments` / `totalScore` — heatmap không có hai field đó.
- Không bịa đường `idealRemaining` — burndown chỉ có `actualRemaining` + `doneCount`.
- Không lấy `user.id` làm `studentId`.
- Không thay heatmap bằng graph Cytoscape, hoặc ngược lại.
- Không dùng `GET /progress` cho member thường (403) thay heatmap.

List sprint thô (tên, goal, ngày): `GET /api/projects/{projectId}/sprints`. Burndown không thay list đó.
