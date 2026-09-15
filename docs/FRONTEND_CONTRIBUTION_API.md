# Hướng dẫn Frontend — API luồng đóng góp (Contribution)

File này là playbook kéo API cho màn hình % đóng góp trên SAGA-BE-V2. Công thức DEC-092 đã chốt; FE **không tính lại %**. Chỉ đọc field backend trả về và nộp evidence / trọng số / override.

Base path: `/api`. Cookie session `SAGA_SESSION`. `credentials: "include"`. **Không Bearer / JWT.**

Lỗi luôn:

```json
{ "code": "CONTRIBUTION_FORBIDDEN", "message": "..." }
```

Công thức chi tiết: `docs/CONTRIBUTION_CALCULATION_SPEC.md`. Contract tổng: `docs/FRONTEND_API_INTEGRATION.md` §18.

---

## 0. Auth + CSRF

Mọi request cần session (đã `POST /api/auth/login` hoặc Google).

| | Cookie | Ghi chú |
| --- | --- | --- |
| Session | `SAGA_SESSION` (HttpOnly) | Browser tự gửi |
| CSRF | `XSRF-TOKEN` | Đọc được từ JS |

**GET không CSRF.** Mọi `POST` / `PUT` / `DELETE`:

1. `GET /api/auth/csrf` (hoặc GET bất kỳ đã set cookie CSRF)
2. Header `X-XSRF-TOKEN` = giá trị cookie `XSRF-TOKEN`

---

## 1. Lấy `teamId` trước khi evaluate

Evaluate nhận **`teamId`**, không phải `courseId`.

### Student (chỉ LEADER mới xem được %)

```text
GET /api/student/courses
GET /api/student/courses/{courseId}/team
```

`StudentTeamResponse` có `teamId`, `projectId`, `myRole` (`LEADER` / `MEMBER` / …).

- `myRole !== "LEADER"` → **không gọi** evaluate (sẽ `403 CONTRIBUTION_FORBIDDEN`). Hiện UI “chỉ Leader xem % đóng góp”.
- `projectId == null` → evaluate vẫn 200 nhưng `members = []`.

### Lecturer / Admin

```text
GET /api/lecturer/courses
GET /api/lecturer/courses/{courseId}/teams
```

Mỗi team: `teamId`, `projectId`, `members[].studentProfileId` (dùng cho override).

---

## 2. Màn hình % đóng góp (API chính)

```http
GET /api/teams/{teamId}/contribution-evaluation
```

Không CSRF. Tính **live**, không ghi snapshot `assessment_run`. Gọi lại mỗi lần mở màn / sau khi nộp evidence / sau override.

### Quyền

| Role | Điều kiện |
| --- | --- |
| `ADMIN` | Mọi team |
| `LECTURER` | Đúng course mình phụ trách |
| `STUDENT` | Chỉ `RoleInTeam.LEADER` của **đúng** team |
| MEMBER / MENTOR | `403 CONTRIBUTION_FORBIDDEN` |

### Response

```json
{
  "teamId": "…",
  "projectId": "…",
  "courseId": "…",
  "configMode": "COURSE",
  "sliceWeights": {
    "codeWeight": 40.0000,
    "testWeight": 10.0000,
    "documentWeight": 15.0000,
    "researchWeight": 35.0000
  },
  "members": [
    {
      "studentProfileId": "…",
      "fullName": "Nguyen Van A",
      "studentCode": "SE001",
      "roleInTeam": "LEADER",
      "sliceScore": 12.5000,
      "sliceContributionPercentage": 43.1600,
      "finalContributionPercentage": 43.1600,
      "peerReviewScore": 1.0000,
      "codeContributionPercentage": 50.0000,
      "testContributionPercentage": 0.0000,
      "documentContributionPercentage": 30.0000,
      "researchContributionPercentage": 20.0000,
      "taskContributionPercentage": 40.0000,
      "sprintBreakdowns": [
        {
          "sprintId": "…",
          "sprintName": "Sprint 1",
          "sliceScore": 12.5000,
          "sliceContributionPercentage": 43.1600,
          "contributionPercentage": 43.1600
        }
      ],
      "warnings": []
    }
  ]
}
```

`configMode`: `"COURSE"` | `"PROJECT_GROUP"`.  
`sliceWeights` trên evaluate là **0–100** (tỷ lệ đã nhân 100), scale 4 chữ số thập phân. Tổng ~100.

`members = []` khi team chưa có project hoặc chưa có member.

### Gắn field vào UI (bắt buộc)

| UI | Field | Ý nghĩa |
| --- | --- | --- |
| Cột / bar **trước peer** | `sliceContributionPercentage` | % theo task + trọng số, tổng team = 100 |
| Cột / bar **sau peer (điểm cuối)** | `finalContributionPercentage` | Đã nhân peer (+ override nếu có), tổng team = 100 |
| Tooltip điểm thô | `sliceScore` | Σ slice, chưa nhân P |
| Sao peer (0–1) | `peerReviewScore` | Chỉ hiển thị. `1` = chưa có peer hoặc mẫu số 0 |
| Radar 4 tiêu chí | `code/test/document/researchContributionPercentage` | Tỷ lệ trong từng tiêu chí cả project, **không** phải slice mix |
| Từng sprint | `sprintBreakdowns[]` | Cùng cặp: `sliceContributionPercentage` (trước peer sprint) và `contributionPercentage` (sau peer sprint) |
| Cảnh báo | `warnings[]` | String code, xem mục 2.1 |

**FE không được:**

- Nhân `peerReviewScore` lên `finalContributionPercentage` lần nữa.
- Cộng / trung bình các `% sprint` để ra `% cuối`.
- Dùng `taskContributionPercentage` thay slice (field khác công thức).
- Tự cộng story point / tự check nhãn trên client để ra %.

Khi mọi người cùng `P` (hoặc chưa ai peer, `P = 1`), `% slice` trùng `% cuối`.

### 2.1 `warnings[]`

| Code | Gợi ý UI |
| --- | --- |
| `NO_PEER_REVIEW` | % cao nhưng chưa có peer |
| `LOW_PEER_REVIEW` | Peer thấp so với % cuối |
| `INSUFFICIENT_EVIDENCE` | Ít nguồn evidence so với % cao |
| `NO_EVIDENCE` | Không task / document / peer |

Đây là tín hiệu hiển thị, không đổi công thức.

---

## 3. Task nào được backend tính (để thiết kế UI evidence)

Backend chỉ đưa task vào tiêu chí khi:

```text
status = DONE
AND có sprint
AND đúng một nhãn: saga:code | saga:test | saga:document | saga:research
```

- `saga:code` / `saga:test` — không cần file/link.
- `saga:document` / `saga:research` — cần **≥1** bằng chứng: file Jira, URL, hoặc file SAGA. Số lượng không tăng điểm.
- Sai/thiếu/hai nhãn, chưa DONE, chưa gắn sprint → không vào tiêu chí.
- `storyPoint` null → tính như `1`. Commit không cộng điểm.

Nhãn **đúng chữ, phân biệt hoa thường**.

Danh sách / tạo task (Leader tạo trên SAGA, ghi Jira rồi trả projection):

```http
GET  /api/projects/{projectId}/tasks
POST /api/projects/{projectId}/tasks
```

CRUD đầy đủ: `docs/FE_API_INTEGRATION_GUIDE_VI.md` §19. Gắn file/link: `docs/FRONTEND_TASK_EVIDENCE_API.md`.

Quyền đọc: student trong team sở hữu project, hoặc lecturer đúng course. Ghi task: **Leader**. `status` là enum `TODO` | `IN_PROGRESS` | `IN_REVIEW` | `DONE` | `BLOCKED`. Response **không** gồm labels; scoring vẫn đọc labels từ DB. FE dùng `id` để gọi API file/link.

---

## 4. Nộp evidence (DOCUMENT / RESEARCH)

Playbook chi tiết (lấy `taskId`, CSRF, fetch multipart, quyền, UI): `docs/FRONTEND_TASK_EVIDENCE_API.md`.

Team member. Write cần CSRF. Sau khi nộp, Leader gọi lại evaluate.

### 4.1 URL

```http
GET    /api/tasks/{taskId}/web-links
POST   /api/tasks/{taskId}/web-links
DELETE /api/tasks/{taskId}/web-links/{linkId}
```

```json
POST /api/tasks/{taskId}/web-links
{ "url": "https://docs.google.com/document/d/…", "title": "Spec báo cáo" }
```

- `url` bắt buộc, `http://` hoặc `https://`, tối đa 2048 ký tự.
- `title` optional, tối đa 255.
- `201` khi tạo. Trùng URL trên cùng task → `409 TASK_WEB_LINK_DUPLICATE`.
- Không phải member → `403 INTEGRATION_FORBIDDEN`.
- `source`: `"SAGA"` (nộp trên app) hoặc `"JIRA"` (sync). Xóa `JIRA` → `409 TASK_EVIDENCE_JIRA_IMMUTABLE`.

```json
{
  "id": "…",
  "taskId": "…",
  "url": "https://docs.google.com/…",
  "title": "Spec báo cáo",
  "source": "SAGA",
  "createdByUserId": "…",
  "createdAt": "2026-09-08T10:00:00"
}
```

### 4.2 File (PDF / Office / ảnh)

```http
GET    /api/tasks/{taskId}/files
POST   /api/tasks/{taskId}/files          (multipart field name: file)
GET    /api/tasks/{taskId}/files/{fileId} (download bytes)
DELETE /api/tasks/{taskId}/files/{fileId}
```

POST: `Content-Type: multipart/form-data`, field **`file`**, vẫn gửi `X-XSRF-TOKEN`. Không JSON.

- Tối đa 10MB / file, 20 file `SAGA` / task.
- Cho phép: pdf, png, jpg/jpeg, gif, webp, txt, csv, md, doc/docx, xls/xlsx, ppt/pptx.
- Trùng nội dung trên cùng task → `409 TASK_FILE_DUPLICATE`.
- Upload/xóa: team member. List/download: member, giảng viên đúng course, ADMIN.
- `source`: `"SAGA"` | `"JIRA"`. Xóa `JIRA` → `409 TASK_EVIDENCE_JIRA_IMMUTABLE`.
- Download: `Content-Disposition: attachment` (mở/save file, không preview bắt buộc).

```json
{
  "id": "…",
  "taskId": "…",
  "filename": "bao-cao.pdf",
  "mimeType": "application/pdf",
  "sizeBytes": 204800,
  "source": "SAGA",
  "createdByUserId": "…",
  "createdAt": "2026-09-08T10:00:00"
}
```

Gợi ý UI task DOCUMENT/RESEARCH: list link + file; badge `JIRA` thì disable nút xóa; sau upload thành công refresh evaluate.

---

## 5. Trọng số (Lecturer / Admin)

Evaluate dùng trọng số đã lưu. FE không nhân trọng số trên client.

### 5.1 Mode + trọng số mặc định course (0–100, tổng 100 ± 0.01)

```http
GET  /api/lecturer/courses/{courseId}/contribution-slice-weights
PUT  /api/lecturer/courses/{courseId}/contribution-slice-weights
PUT  /api/lecturer/courses/{courseId}/contribution-config-mode
GET  /api/lecturer/courses/{courseId}/contribution-team-weights
```

```json
PUT .../contribution-slice-weights
{ "codeWeight": 40, "testWeight": 10, "documentWeight": 15, "researchWeight": 35 }

PUT .../contribution-config-mode
{ "mode": "COURSE" }
```

`mode`: `"COURSE"` | `"PROJECT_GROUP"`.

GET weights:

```json
{
  "mode": "COURSE",
  "codeWeight": 40,
  "testWeight": 10,
  "documentWeight": 15,
  "researchWeight": 35
}
```

Chuyển `PROJECT_GROUP` chỉ khi **mọi team đã có project** đều đã có group weights. Thiếu → `409 TEAM_MODE_CONFIGURATION_INCOMPLETE`.

`GET .../contribution-team-weights` → từng team `configured: true/false` + `projectId` để PUT group weights.

### 5.2 Trọng số từng project (0–1, tổng 1.0)

Chỉ dùng khi `configMode = PROJECT_GROUP`.

```http
GET /api/projects/{projectId}/group-weights
PUT /api/projects/{projectId}/group-weights
```

```json
PUT /api/projects/{projectId}/group-weights
{
  "teamId": "…",
  "codeWeight": 0.40,
  "testWeight": 0.10,
  "documentWeight": 0.15,
  "researchWeight": 0.35,
  "note": "optional"
}
```

`teamId` optional; nếu gửi phải khớp team của project, sai → `400 GROUP_PROJECT_MISMATCH`.  
Chưa cấu hình: GET trả `codeWeight`… `null`. Evaluate team này khi mode `PROJECT_GROUP` → `409 TEAM_WEIGHT_CONFIG_INCOMPLETE`.

**Đừng nhầm đơn vị:** course PUT dùng 40; project-group PUT dùng 0.40. Evaluate `sliceWeights` luôn 0–100.

---

## 6. Override giảng viên

```http
POST /api/teams/{teamId}/contribution-override
```

CSRF. `LECTURER` đúng course hoặc `ADMIN`. Student → `403`. `201`.

```json
{
  "studentProfileId": "…",
  "percentage": 40,
  "reason": "Điều chỉnh sau bảo vệ"
}
```

- `percentage` 0–100.
- `studentProfileId` phải là member team (lấy từ evaluate `members[].studentProfileId` hoặc lecturer teams).
- Ghi **ngay**, không workflow duyệt.
- Chỉ đụng `% cuối`. `sliceScore` / `sliceContributionPercentage` không đổi.
- Sau override, **team được chuẩn hóa lại = 100** — các member khác cũng đổi `finalContributionPercentage`. Phải GET evaluate lại, đừng cộng tay.

```json
{
  "id": "…",
  "studentProfileId": "…",
  "oldValue": 43.1600,
  "newValue": 40,
  "reason": "Điều chỉnh sau bảo vệ"
}
```

---

## 7. Lỗi thường gặp

| HTTP | `code` | Khi nào |
| --- | --- | --- |
| 401 | (unauthenticated) | Thiếu cookie session |
| 403 | `CONTRIBUTION_FORBIDDEN` | Student không phải Leader; student gọi override |
| 403 | `LECTURER_COURSE_FORBIDDEN` | Lecturer không phụ trách course |
| 403 | `INTEGRATION_FORBIDDEN` | Không phải team member (file/link) |
| 404 | `TEAM_NOT_FOUND` / `TASK_NOT_FOUND` | Sai id |
| 409 | `TEAM_WEIGHT_CONFIG_INCOMPLETE` | Mode `PROJECT_GROUP` nhưng team chưa có group weights |
| 409 | `TEAM_MODE_CONFIGURATION_INCOMPLETE` | Chuyển mode khi còn team thiếu config |
| 400 | `CONTRIBUTION_WEIGHTS_INVALID` | Tổng trọng số sai / ngoài range |
| 400 | `CONTRIBUTION_OVERRIDE_INVALID` | % ngoài 0–100, thiếu field, không phải member |
| 400 | `GROUP_PROJECT_MISMATCH` | `teamId` không khớp project |
| 400 | `TASK_WEB_LINK_INVALID` / `TASK_FILE_INVALID` / `TASK_FILE_TOO_LARGE` | URL/file không hợp lệ |
| 409 | `TASK_WEB_LINK_DUPLICATE` / `TASK_FILE_DUPLICATE` | Trùng trên cùng task |
| 409 | `TASK_FILE_LIMIT` | Quá 20 file SAGA / task |
| 409 | `TASK_EVIDENCE_JIRA_IMMUTABLE` | Xóa evidence sync từ Jira |

---

## 8. Gợi ý màn hình theo role

### Leader (student)

```text
GET /api/student/courses/{courseId}/team     → teamId, projectId, myRole
GET /api/teams/{teamId}/contribution-evaluation
GET /api/projects/{projectId}/tasks
GET/POST /api/tasks/{taskId}/web-links
GET/POST /api/tasks/{taskId}/files
```

Sau nộp evidence → GET evaluate lại.

### Lecturer

```text
GET /api/lecturer/courses/{courseId}/teams
GET /api/teams/{teamId}/contribution-evaluation
GET/PUT trọng số course hoặc project-group
POST /api/teams/{teamId}/contribution-override
GET /api/tasks/{taskId}/files | web-links     (đọc / download, không upload nếu không phải member)
```

### Member (không phải Leader)

Không gọi evaluate. Vẫn nộp file/link trên task nếu là member. Vẫn chấm peer: [`docs/FRONTEND_PEER_REVIEW_API.md`](./FRONTEND_PEER_REVIEW_API.md).

---

## 9. Việc chưa có trên API này

- Snapshot lịch sử `assessment_run` (mỗi GET là số hiện tại).
- Gắn nhãn `saga:*` lúc tạo task (nhãn vẫn từ Jira). Task CRUD: `docs/FE_API_INTEGRATION_GUIDE_VI.md` §19.

Peer review **đã ship** — playbook FE: [`docs/FRONTEND_PEER_REVIEW_API.md`](./FRONTEND_PEER_REVIEW_API.md). Evaluate đọc `peer_review.star_rating` đã nộp. Peer chưa có → `peerReviewScore = 1` và `% slice` = `% cuối` (trừ khi đã override).
