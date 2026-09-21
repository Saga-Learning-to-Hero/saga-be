# Hướng dẫn Frontend — Dashboard 1 course cho giảng viên

Playbook kéo API khi FE vẽ **màn dashboard lớp** của giảng viên: KPI lớp, card từng team, risk, sprint hiện tại, activity, peer review, sync. FE **không** tính lại KPI / risk; chỉ render số backend trả về.

Đây **không** phải % đóng góp hay điểm. Công thức grade vẫn nằm ở `GET /api/teams/{teamId}/contribution-evaluation`. Endpoint này **không đọc `contribution_override`**.

Khác `GET /api/lecturer/courses/{courseId}/progress`:

| | `/progress` | `/dashboard` |
| --- | --- | --- |
| Mục đích | Card nhẹ (tổng task / % / tên sprint) | Một request đủ để vẽ cả màn dashboard lớp |
| Risk / KPI | Không | BE sở hữu, trả `risk` + `summary` |
| Sprint | Chỉ `currentSprintName` | Sprint `active` từng project + so sánh sprint trước |
| Sync Jira/GitHub | Không | `ACTIVE` / `DEGRADED` / `REVOKED` + last success |
| Peer review | Không | `n × (n − 1)`, bỏ MENTOR |
| ADMIN | 403 | 403 |

Contract tổng: `docs/FRONTEND_API_INTEGRATION.md`. Team/roster: `docs/FE_API_INTEGRATION_GUIDE_VI.md`. Contribution: `docs/FRONTEND_CONTRIBUTION_API.md`. Sprint activity 1 project: `docs/FRONTEND_SPRINT_ACTIVITY_API.md`.

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

## 1. Lấy `courseId` trước

```text
GET /api/lecturer/courses
GET /api/lecturer/courses/{courseId}/dashboard
GET /api/lecturer/courses/{courseId}/dashboard?scope=CURRENT_SPRINT
```

`courseId` là UUID SAGA của course được gán cho giảng viên đang login.

- List courses: `GET /api/lecturer/courses` (ADMIN vẫn gọi được list này để support).
- Dashboard **không** cho ADMIN. Dùng session giảng viên phụ trách lớp.

---

## 2. Map màn hình → API

Mở dashboard 1 lớp → **một** GET. Không loop theo team.

```text
GET /api/lecturer/courses/{courseId}/dashboard
```

| UI | Field |
| --- | --- |
| Header lớp | `courseCode`, `subjectCode`, `subjectName`, `classCode`, `semesterCode` |
| KPI lớp | `summary.*` |
| Tổng task lớp | `taskStatusTotals.*` |
| Legend risk | `riskPolicy` (số BE đang dùng — đừng hard-code) |
| List / table team | `teams[]` |
| Badge risk card | `teams[].risk.level` + `teams[].risk.reasons` |
| Click 1 team → project detail | `teams[].projectId` → `GET /api/projects/{projectId}/progress` |
| Click 1 team → chart sprint | `teams[].projectId` → `GET /api/projects/{projectId}/analytics/sprint-activity` |
| Gửi reminder | **Không** lấy từ dashboard. `teams[].reminder` luôn `null` |

`scope` hiện chỉ `CURRENT_SPRINT`. Bỏ query cũng được — BE default `CURRENT_SPRINT`.

---

## 3. Quyền

| Caller | Kết quả |
| --- | --- |
| `LECTURER` đúng course | `200` |
| `LECTURER` không phụ trách course | `403 LECTURER_COURSE_FORBIDDEN` |
| `ADMIN` | `403 LECTURER_COURSE_FORBIDDEN` (check trước, không tiết lộ course có tồn tại hay không) |
| `STUDENT` | `403` từ security (`/api/lecturer/**` cần role LECTURER/ADMIN) |

---

## 4. Request

```http
GET /api/lecturer/courses/{courseId}/dashboard
GET /api/lecturer/courses/{courseId}/dashboard?scope=CURRENT_SPRINT
```

Không CSRF. Không body.

| Query | Bắt buộc | Giá trị |
| --- | --- | --- |
| `scope` | Không | Chỉ `CURRENT_SPRINT`. Khác → `400 INVALID_DASHBOARD_SCOPE` |

---

## 5. Response

`200`. Course chưa có team / team chưa có project / chưa có sprint `active` vẫn `200` — **không** 404. FE dùng `null` và count `0` trong `summary`.

```json
{
  "courseId": "7c2e1a90-4b11-4f0a-9c22-88aa11bb22cc",
  "courseCode": "SE123",
  "subjectCode": "SEP490",
  "subjectName": "Capstone Project",
  "classCode": "SE1741",
  "semesterCode": "SP26",
  "scope": "CURRENT_SPRINT",
  "generatedAt": "2026-09-21T12:00:00Z",
  "riskPolicy": {
    "inactivityWarningDays": 3,
    "inactivityCriticalDays": 5,
    "scheduleLagWarningPercentagePoints": 20.0,
    "scheduleLagCriticalPercentagePoints": 35.0,
    "peerReviewWarningElapsedPercent": 80.0
  },
  "summary": {
    "enrolledStudents": 24,
    "unassignedStudents": 2,
    "totalTeams": 6,
    "healthyTeams": 3,
    "warningTeams": 1,
    "criticalTeams": 1,
    "unknownTeams": 1,
    "teamsWithoutProject": 1,
    "teamsWithoutActiveSprint": 1,
    "teamsWithSyncFailure": 0
  },
  "taskStatusTotals": {
    "total": 40,
    "todo": 10,
    "inProgress": 8,
    "inReview": 4,
    "done": 16,
    "blocked": 2,
    "overdue": 3,
    "completionPercent": 40.0
  },
  "teams": [
    {
      "teamId": "11111111-1111-1111-1111-111111111111",
      "teamNo": 1,
      "teamName": "Team Alpha",
      "projectId": "22222222-2222-2222-2222-222222222222",
      "projectName": "SAGA",
      "memberCount": 4,
      "currentSprint": {
        "sprintId": "33333333-3333-3333-3333-333333333333",
        "sprintName": "Sprint 4",
        "state": "ACTIVE",
        "startDate": "2026-09-10",
        "endDate": "2026-09-24",
        "elapsedPercent": 50.0
      },
      "progress": {
        "totalTasks": 10,
        "todo": 2,
        "inProgress": 3,
        "inReview": 1,
        "done": 3,
        "blocked": 1,
        "overdue": 1,
        "completionPercent": 30.0,
        "scheduleGapPercentagePoints": 20.0
      },
      "activity": {
        "lastActivityAt": "2026-09-20T08:15:00Z",
        "inactiveDays": 1,
        "totalActivities": 12,
        "series": [
          {
            "date": "2026-09-10",
            "commits": 2,
            "tasks": 1,
            "peerReviews": 0,
            "documents": 0,
            "totalActivities": 3
          }
        ]
      },
      "traceability": {
        "completedTasks": 3,
        "completedTasksWithCommit": 2,
        "completedTasksWithoutCommit": 1,
        "linkedCommits": 5,
        "unlinkedCommits": 1,
        "taskCommitLinkRate": 66.67
      },
      "peerReview": {
        "expectedReviews": 12,
        "submittedReviews": 8,
        "completionRate": 66.67,
        "pendingStudentCount": 2,
        "pendingStudentProfileIds": [
          "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        ],
        "deadlineAt": "2026-09-24T00:00:00Z"
      },
      "sync": {
        "jiraStatus": "ACTIVE",
        "jiraSyncStatus": "SUCCEEDED",
        "jiraLastSuccessfulSyncAt": "2026-09-21T10:00:00Z",
        "githubStatus": "ACTIVE",
        "githubSyncStatus": "SUCCEEDED",
        "githubLastSuccessfulSyncAt": "2026-09-21T10:05:00Z"
      },
      "configuration": {
        "contributionMode": "COURSE",
        "contributionWeightsConfigured": true
      },
      "previousSprintComparison": {
        "sprintId": "44444444-4444-4444-4444-444444444444",
        "sprintName": "Sprint 3",
        "completionDeltaPercentagePoints": 5.0,
        "activityDeltaPercent": -10.0,
        "traceabilityDeltaPercentagePoints": 8.0
      },
      "risk": {
        "level": "WARNING",
        "reasons": [
          {
            "code": "SCHEDULE_LAG",
            "severity": "WARNING",
            "actualValue": 20.0,
            "thresholdValue": 20.0,
            "unit": "PERCENTAGE_POINT",
            "affectedStudentProfileIds": []
          }
        ]
      },
      "reminder": null
    }
  ]
}
```

`generatedAt` là ISO-8601 UTC (`…Z`). Ngày sprint / series là `YYYY-MM-DD`. Instant khác (`lastActivityAt`, sync, peer deadline) cũng UTC `…Z`.

---

## 6. Field — lớp

| Field | Type | Ghi chú |
| --- | --- | --- |
| `courseId` | UUID | Path param vừa gọi |
| `scope` | `CURRENT_SPRINT` | Echo query (hoặc default) |
| `generatedAt` | Instant UTC | Thời điểm BE aggregate, **không** phải last sync Jira/GitHub |
| `riskPolicy` | object | Ngưỡng BE đang áp. FE hiện tooltip / legend từ đây |
| `summary.enrolledStudents` | number | Enrollment `ACTIVE` |
| `summary.unassignedStudents` | number | Enrollment `ACTIVE` chưa vào team |
| `summary.totalTeams` | number | Mọi team của course |
| `summary.healthyTeams` / `warningTeams` / `criticalTeams` / `unknownTeams` | number | Đếm theo `teams[].risk.level` |
| `summary.teamsWithoutProject` | number | `projectId == null` |
| `summary.teamsWithoutActiveSprint` | number | Có project nhưng không có sprint `active` |
| `summary.teamsWithSyncFailure` | number | `jiraSyncStatus` hoặc `githubSyncStatus` = `FAILED` |
| `taskStatusTotals.*` | number / `Double` | Cộng progress **sprint hiện tại** của mọi team. Team không sprint không cộng |

`completionPercent` / các `%` khác: `null` khi mẫu số = 0 (không có task). **Không** ra `0` trong case đó.

---

## 7. Field — card team

| Field | Type | Khi nào `null` |
| --- | --- | --- |
| `teamId` / `teamNo` / `teamName` | luôn có | — |
| `projectId` / `projectName` | UUID / string | Team chưa gắn project |
| `memberCount` | number | Count member enrollment `ACTIVE` (gồm MENTOR) |
| `currentSprint` | object | Không có sprint `state=active` |
| `progress` | object | Không có current sprint |
| `activity` | object | Có project thì luôn có (series có thể `[]`) |
| `traceability` | object | Không có current sprint |
| `peerReview` | object | Không có current sprint |
| `sync` | object | Có project thì luôn có; status từng kênh có thể `null` nếu chưa connect |
| `configuration` | object | Luôn có, kể cả team không project |
| `previousSprintComparison` | object | Không có sprint closed trước current |
| `risk` | object | Luôn có |
| `reminder` | object | **Luôn `null`** |

### Current sprint

Current sprint **không** dùng chung 1 `sprintId` cho cả lớp. Mỗi project lấy sprint projection `state` ignore-case `"active"` đầu tiên (order `startDate`/`createdAt` desc).

| Field | Ghi chú |
| --- | --- |
| `sprintId` | UUID SAGA, **không** dùng `externalSprintId` Jira |
| `state` | Uppercase, thường `ACTIVE` |
| `elapsedPercent` | 0 nếu chưa tới `startDate`, 100 nếu đã qua `endDate`, `null` nếu thiếu ngày |

### Progress

Cắt **task của sprint hiện tại**, không phải cả project.

| Field | Ghi chú |
| --- | --- |
| `todo` / `inProgress` / `inReview` / `done` / `blocked` | Đúng enum `TaskStatus` |
| `totalTasks` | Tổng status trên |
| `overdue` | Chưa `DONE`, có `dueDate` < now |
| `completionPercent` | `done / total * 100`, làm tròn 2 chữ số, `null` nếu total = 0 |
| `scheduleGapPercentagePoints` | `elapsedPercent − completionPercent`. Dương = trễ lịch |

### Activity

`lastActivityAt` = max(`last commit`, `last task updatedAt`) **cả project** (không chỉ sprint). `inactiveDays` = số ngày lịch từ last activity tới `generatedAt` (UTC).

`series` là từng ngày trong `[startDate, endDate]` của current sprint, **cắt tối đa 90 ngày** (giữ đuôi gần `endDate`). Team không sprint → `series = []`, `totalActivities = 0`.

| Cột series | Nguồn |
| --- | --- |
| `commits` | Git commit V23 (`parentCount` null hoặc ≤ 1) |
| `tasks` | Task `createdAt` |
| `peerReviews` | Peer review đã nộp (`starRating` ≠ null) |
| `documents` | Task file + web link `createdAt` |

### Traceability

Commit V23 + `task_git_commit_link` trong sprint hiện tại.

| Field | Ghi chú |
| --- | --- |
| `completedTasksWithCommit` | Task `DONE` có ≥ 1 commit V23 gắn |
| `completedTasksWithoutCommit` | Task `DONE` không commit |
| `linkedCommits` | Distinct commit gắn task còn sống của sprint |
| `unlinkedCommits` | Commit V23 nằm trong cửa sổ sprint nhưng chưa gắn task sprint này |
| `taskCommitLinkRate` | `withCommit / completed * 100`, `null` nếu chưa có task DONE |

### Peer review

Expected = `n × (n − 1)` với `n` = member ACTIVE **không** phải `MENTOR`. `n ≤ 1` → expected = 0.

`pendingStudentProfileIds` là UUID **StudentProfile**, cùng id roster / contribution — **không** phải `userId`.

`deadlineAt` = `endDate` sprint (UTC).

### Sync

| Field | Enum |
| --- | --- |
| `jiraStatus` / `githubStatus` | `ACTIVE`, `DEGRADED`, `REVOKED` — **không** có `CONNECTED` |
| `jiraSyncStatus` / `githubSyncStatus` | Job mới nhất: `RUNNING`, `SUCCEEDED`, `FAILED` |
| `*LastSuccessfulSyncAt` | Job `SUCCEEDED` gần nhất; `null` nếu chưa từng success |

Nhiều GitHub repo trên 1 project: BE giữ status “tốt hơn” (`ACTIVE` > `DEGRADED` > khác).

### Configuration

| `contributionMode` | `contributionWeightsConfigured` |
| --- | --- |
| `COURSE` | luôn `true` (weight nằm ở course) |
| `PROJECT_GROUP` | `true` khi team/project đã có `ProjectGroupWeightConfig` |

**Không** có field override. FE **không** gọi `POST /api/teams/{teamId}/contribution-override` từ màn này.

---

## 8. Risk — FE chỉ hiển thị

`risk.level` = max severity của `reasons[]`. FE **không** tự tính lại từ progress/activity.

Thứ tự severity BE dùng: `HEALTHY` < `WARNING` < `CRITICAL` < `UNKNOWN`.

Nếu có `DATA_UNAVAILABLE` thì `level` có thể là `UNKNOWN` **kể cả** khi card còn reason CRITICAL khác. Hiện badge UNKNOWN + list đủ reasons.

### Policy (cũng nằm trong `riskPolicy`)

| Rule | WARNING | CRITICAL |
| --- | --- | --- |
| `INACTIVE` | 3 ngày | 5 ngày |
| `SCHEDULE_LAG` | gap ≥ 20 pp | gap ≥ 35 pp |
| `PEER_REVIEW_INCOMPLETE` | sprint chưa hết và `elapsedPercent` ≥ 80 | sprint đã hết (`state=closed` hoặc `now ≥ endDate`) |

### `code`

| `code` | Khi nào | `unit` | `affectedStudentProfileIds` |
| --- | --- | --- | --- |
| `NO_PROJECT` | `projectId == null` — **short-circuit**, không thêm reason khác | `COUNT` | `[]` |
| `NO_ACTIVE_SPRINT` | Có project, không sprint `active` | `COUNT` | `[]` |
| `INACTIVE` | Biết last activity và inactive ≥ 3 / 5 ngày | `DAY` | `[]` |
| `DATA_UNAVAILABLE` | Có project nhưng **không** biết last activity | `DAY` | `[]` |
| `SCHEDULE_LAG` | `scheduleGapPercentagePoints` ≥ 20 / 35 | `PERCENTAGE_POINT` | `[]` |
| `BLOCKED_TASKS` | `blocked ≥ 1` | `TASK` | `[]` |
| `OVERDUE_TASKS` | `overdue ≥ 1` | `TASK` | `[]` |
| `MISSING_TASK_COMMIT_LINK` | `completedTasksWithoutCommit ≥ 1` | `TASK` | `[]` |
| `JIRA_SYNC_FAILED` | job Jira mới nhất `FAILED` | `COUNT` | `[]` |
| `GITHUB_SYNC_FAILED` | job GitHub mới nhất `FAILED` | `COUNT` | `[]` |
| `CONTRIBUTION_CONFIG_MISSING` | mode `PROJECT_GROUP` và chưa có weight config | `COUNT` | `[]` |
| `PEER_REVIEW_INCOMPLETE` | submitted < expected, và đủ điều kiện 80% / hết sprint | `REVIEW` | pending `studentProfileId` |

Không có last activity → **không** gắn `INACTIVE`, chỉ `DATA_UNAVAILABLE`.

`NO_PROJECT` → `level = CRITICAL`, `progress` / `currentSprint` / `sync` = `null`.

Gợi ý màu:

| `level` | UI |
| --- | --- |
| `HEALTHY` | xanh |
| `WARNING` | vàng |
| `CRITICAL` | đỏ |
| `UNKNOWN` | xám — “thiếu data”, không phải healthy |

---

## 9. `null` vs `0`

| Tình huống | FE làm gì |
| --- | --- |
| `projectId == null` | Card “chưa có project”. Đừng gọi `/progress` hay sprint-activity |
| `currentSprint == null` | “Chưa có sprint đang chạy”. `progress` / `traceability` / `peerReview` cũng `null` |
| `%` = `null` | Hiện “—” / “N/A”, **không** hiện 0% |
| `lastActivityAt` = `null` | Hiện “chưa có activity”, badge `UNKNOWN` nếu BE trả `DATA_UNAVAILABLE` |
| `sync.*Status` = `null` | “Chưa connect”, không phải lỗi sync |
| `previousSprintComparison` = `null` | Ẩn block so sánh sprint trước |
| `reminder` = `null` | Ẩn “lần nhắc gần nhất”. **Đừng** gọi thêm API notification history từ field này — chưa có |
| Count `0` | Có data, thật sự bằng 0 — hiện `0` |

---

## 10. Lỗi thường gặp

| HTTP | `code` | Khi nào | FE làm gì |
| --- | --- | --- | --- |
| 401 | (unauthenticated) | Chưa login / hết session | Redirect login |
| 403 | `LECTURER_COURSE_FORBIDDEN` | Không phải GV của lớp, hoặc ADMIN | Không mở dashboard; session admin không dùng màn này |
| 404 | `COURSE_NOT_FOUND` | `courseId` sai / đã xóa | Toast / quay list courses |
| 400 | `INVALID_DASHBOARD_SCOPE` | `scope` khác `CURRENT_SPRINT` | Đừng gửi scope lạ; omit query |

Course rỗng / chưa sync Jira / chưa GitHub → vẫn `200`. **Không** hiện error page.

---

## 11. Ví dụ fetch

```js
async function loadLecturerCourseDashboard(courseId) {
  const res = await fetch(`/api/lecturer/courses/${courseId}/dashboard`, {
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
```

KPI:

```js
const { summary, taskStatusTotals, teams, riskPolicy } = data;
const atRisk = summary.warningTeams + summary.criticalTeams + summary.unknownTeams;
```

List card:

```js
teams.map((team) => ({
  id: team.teamId,
  title: `Team ${team.teamNo} · ${team.teamName}`,
  level: team.risk.level,
  reasons: team.risk.reasons.map((r) => r.code),
  sprint: team.currentSprint?.sprintName ?? null,
  done: team.progress?.done ?? null,
  canOpenProject: team.projectId != null,
}));
```

Activity chart 1 card (chỉ khi có `currentSprint`):

```js
const labels = team.activity.series.map((d) => d.date);
const totals = team.activity.series.map((d) => d.totalActivities);
```

Drill-down:

```js
if (team.projectId) {
  // GET /api/projects/{projectId}/progress
  // GET /api/projects/{projectId}/analytics/sprint-activity
}
```

---

## 12. Việc FE không làm

- Không tự tính `risk.level` / `INACTIVE` / `SCHEDULE_LAG` từ `inactiveDays` hay gap. Đọc `risk`.
- Không hard-code 3 / 5 ngày hay 20 / 35 pp — dùng `riskPolicy` nếu cần hiện chú thích.
- Không đọc / ghi `contribution_override` trên màn này.
- Không coi dashboard là % đóng góp. Muốn % → `GET /api/teams/{teamId}/contribution-evaluation`.
- Không gọi Jira/GitHub từ browser để “bù” số.
- Không dùng `GET /progress` thay dashboard — thiếu risk, peer, sync, series.
- Không giả định mọi team chung 1 `sprintId` hay luôn có đúng 1 sprint `ACTIVE`.
- Không map status `CONNECTED` — BE trả `ACTIVE`.
- Không dùng `studentId` tài khoản cho pending peer — dùng `studentProfileId`.
- Không hiện block reminder từ `teams[].reminder` (luôn `null`).
- Không gửi `scope=ALL_TIME` / `PREVIOUS_SPRINT` — sẽ 400.

Muốn list team thô (CRUD): `GET /api/lecturer/courses/{courseId}/teams`. Dashboard là aggregate, không thay list đó.
