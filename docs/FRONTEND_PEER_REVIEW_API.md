# Hướng dẫn Frontend — Luồng Peer Review

Playbook kéo API khi sinh viên **chấm đồng đội theo sprint**, và khi giảng viên / Leader **xem kết quả**. FE **không** tính hệ số P hay % đóng góp từ số sao. Chỉ render form từ rubric, nộp sao, rồi (nếu là Leader/GV) đọc lại `contribution-evaluation`.

Contract tổng: `docs/FRONTEND_API_INTEGRATION.md`. Luồng %: `docs/FRONTEND_CONTRIBUTION_API.md`. Công thức P: `docs/CONTRIBUTION_CALCULATION_SPEC.md` §0.4.

Base path: `/api` (**không** `/api/v1`). Cookie session `SAGA_SESSION`. Mọi `fetch` dùng `credentials: "include"`. **Không Bearer / JWT.**

Lỗi luôn:

```json
{ "code": "PEER_REVIEW_INVALID", "message": "..." }
```

---

## 0. Auth + CSRF

Mọi request cần session (`POST /api/auth/login` hoặc Google).

| | Cookie | Ghi chú |
| --- | --- | --- |
| Session | `SAGA_SESSION` (HttpOnly) | Browser tự gửi |
| CSRF | `XSRF-TOKEN` | Đọc được từ JS |

**GET không CSRF.** `POST` nộp review:

1. `GET /api/auth/csrf`
2. Header `X-XSRF-TOKEN` = `csrf.token` (hoặc cookie `XSRF-TOKEN`)

```js
async function csrfHeader() {
  const res = await fetch("/api/auth/csrf", { credentials: "include" });
  const { token } = await res.json();
  return { "X-XSRF-TOKEN": token };
}
```

---

## 1. Lấy `teamId` và `sprintId` trước

Peer review gắn **một team + một sprint**. Không gửi `reviewerId` — backend lấy từ session.

### Student (mọi thành viên ACTIVE, không chỉ Leader)

```text
GET /api/student/courses
GET /api/student/courses/{courseId}/team      → teamId, projectId, myRole
GET /api/projects/{projectId}/sprints         → id, name, state của từng sprint
```

- `teamId` null / 404 → chưa vào team, không chấm được.
- `projectId == null` → team chưa có project → `POST` review sẽ `400 PEER_REVIEW_INVALID` (“Team has no project.”). Hiện “nhóm chưa setup project”.
- Sprint dùng **`id` UUID SAGA**, không dùng `externalSprintId` của Jira.

### Lecturer / Admin

```text
GET /api/lecturer/courses/{courseId}/teams    → teamId, projectId
GET /api/projects/{projectId}/sprints         → sprintId
```

GV **không** gọi candidates / submit. Chỉ đọc rubric + list (và evaluate %).

---

## 2. Map màn hình → API

### 2.1 Student — form chấm một sprint

```text
GET  /api/teams/{teamId}/peer-review-rubric
GET  /api/teams/{teamId}/sprints/{sprintId}/peer-reviews/candidates
POST /api/teams/{teamId}/sprints/{sprintId}/peer-reviews     (CSRF)
```

Sau nộp: gọi lại **candidates** (cập nhật `alreadyReviewed`) hoặc **list**. SSE `PEER_REVIEW_CHANGED` trên `GET /api/projects/{projectId}/events` — refetch list/candidates. Graph 5 đợi `GRAPH_CHANGED` rồi GET graph (không lấy nodes từ SSE). Leader muốn thấy % đổi: `GET /api/teams/{teamId}/contribution-evaluation`.

### 2.2 Lecturer / Admin / Leader — xem mạng lưới sao

```text
GET /api/teams/{teamId}/peer-review-rubric
GET /api/teams/{teamId}/sprints/{sprintId}/peer-reviews
GET /api/teams/{teamId}/contribution-evaluation          (chỉ Leader / GV / Admin)
```

---

## 3. Quyền

| Endpoint | STUDENT (member ACTIVE) | LECTURER (đúng course) | ADMIN |
| --- | --- | --- | --- |
| `GET /api/peer-review-rubrics/default` | có | có | có |
| `GET /api/teams/{teamId}/peer-review-rubric` | có | có | có |
| `GET .../peer-reviews/candidates` | có | **403** `PEER_REVIEW_FORBIDDEN` | **403** |
| `POST .../peer-reviews` | có | **403** | **403** |
| `GET .../peer-reviews` | có (cả list team) | có | có |

FE **đừng** giả định Admin nộp hộ. Session Admin trên candidates/submit sẽ 403 dù đã login.

Sinh viên đọc được **toàn bộ** review của team/sprint (không ẩn danh). Đây là behavior hiện tại — UI list cho GV/Leader; form chấm cho từng student chỉ cần candidates.

---

## 4. Rubric — render form động

**Không hard-code** số tiêu chí và **không hard-code** `rubricId`. Không còn field `weight` trên rubric.

Ưu tiên rubric theo team (đúng subject nếu có). Default global chỉ dùng khi chưa có `teamId`, hoặc team rubric rỗng.

```http
GET /api/teams/{teamId}/peer-review-rubric
GET /api/peer-review-rubrics/default
```

Không CSRF.

### Response

```json
{
  "teamId": "uuid-or-null",
  "subjectId": "uuid-or-null",
  "criteria": [
    {
      "rubricId": "c0a80101-0000-4000-8000-000000000001",
      "criteriaName": "Hoàn thành & Chất lượng",
      "description": "Làm đúng, đủ task được giao; code/chức năng chạy ổn định, ít lỗi."
    }
  ]
}
```

| Field | Ý nghĩa |
| --- | --- |
| `teamId` | null trên default; UUID team trên API team |
| `subjectId` | UUID subject khi team dùng rubric môn; `null` khi fallback global |
| `criteria[].rubricId` | Đưa vào `criteriaRatings[].rubricId` lúc POST |
| `criteria[].criteriaName` | Label sao trên form |
| `criteria[].description` | Tooltip / helper text |

Seed global hiện có 4 tiêu chí (thứ tự `createdAt`): Hoàn thành & Chất lượng, Tiến độ & Quy trình, Giao tiếp & Hỗ trợ, Thái độ & Xử lý sự cố. **Vẫn map theo mảng `criteria`**, đừng giả định mãi là 4.

Mỗi tiêu chí trên form: input sao **nguyên 1–5**.

---

## 5. Candidates — danh sách người được chấm

```http
GET /api/teams/{teamId}/sprints/{sprintId}/peer-reviews/candidates
```

Chỉ STUDENT thuộc team. Reviewer = session; **không** có trong query.

```json
{
  "teamId": "uuid",
  "sprintId": "uuid",
  "reviewerId": "uuid",
  "candidates": [
    {
      "studentId": "uuid",
      "fullName": "Nguyễn Văn A",
      "studentCode": "SE001",
      "alreadyReviewed": false,
      "existingReviewId": null,
      "existingTotalStarRating": null
    }
  ]
}
```

| Field | Dùng trên UI |
| --- | --- |
| `reviewerId` | Student profile của mình — đối chiếu, không gửi lại |
| `studentId` | = `revieweeId` lúc POST |
| `alreadyReviewed` | true → nút “Sửa đánh giá” / hiện sao đã nộp |
| `existingReviewId` | id review cũ (upsert vẫn POST cùng URL) |
| `existingTotalStarRating` | Tổng sao đã lưu (tổng các tiêu chí), không phải sao TB |

Chính mình **không** nằm trong `candidates`. Không tự thêm self vào form.

---

## 6. Submit — upsert một cặp (sprint, mình, một bạn)

```http
POST /api/teams/{teamId}/sprints/{sprintId}/peer-reviews
```

CSRF bắt buộc. Body JSON.

### Cách nên dùng: chấm từng tiêu chí

Phải gửi **đủ** mọi `rubricId` đang active của team, không trùng, mỗi `starRating` ∈ **1..5**.

```json
{
  "revieweeId": "uuid-của-candidate.studentId",
  "criteriaRatings": [
    { "rubricId": "c0a80101-0000-4000-8000-000000000001", "starRating": 5 },
    { "rubricId": "c0a80101-0000-4000-8000-000000000002", "starRating": 4 },
    { "rubricId": "c0a80101-0000-4000-8000-000000000003", "starRating": 5 },
    { "rubricId": "c0a80101-0000-4000-8000-000000000004", "starRating": 4 }
  ],
  "comment": "Phối hợp tốt trong Sprint"
}
```

Backend lưu `starRating` tổng = 5+4+5+4 = **18**. Contribution đọc tổng này.

Validate FE trước khi POST:

1. `revieweeId` ∈ `candidates[].studentId` và ≠ `reviewerId`
2. `criteriaRatings.length === rubric.criteria.length`
3. Set `rubricId` trùng khít với rubric (không thiếu, không thừa)
4. Mỗi sao 1–5 nguyên
5. `comment` tối đa 4000 ký tự, có thể omit / `null`

### Cách phụ: chỉ gửi tổng sao

Khi không chấm từng tiêu chí (rubric rỗng, hoặc màn hình rút gọn):

```json
{
  "revieweeId": "uuid",
  "starRating": 18,
  "comment": "Phối hợp tốt trong Sprint"
}
```

`starRating` tổng ∈ **1..100**. Nếu đã gửi `criteriaRatings` (kể cả mảng rỗng được coi là “không chấm chi tiết” — mảng rỗng / omit thì cần `starRating`). **Đừng gửi cả hai:** nếu có `criteriaRatings` non-empty, backend **bỏ** `starRating` trên body và dùng tổng tiêu chí.

Khuyến nghị product: luôn dùng `criteriaRatings` đủ rubric.

### Upsert

Cùng `(sprintId, reviewer session, revieweeId)` gửi lại → **cập nhật** review cũ, không tạo bản thứ hai. Nút “Sửa đánh giá” = POST giống create.

### Response `200`

```json
{
  "id": "uuid",
  "sprintId": "uuid",
  "sprintName": "Sprint 1",
  "reviewerId": "uuid",
  "reviewerName": "Student A",
  "revieweeId": "uuid",
  "revieweeName": "Student B",
  "starRating": 18,
  "criteriaRatings": [
    {
      "rubricId": "c0a80101-0000-4000-8000-000000000001",
      "criteriaName": "Hoàn thành & Chất lượng",
      "starRating": 5
    }
  ],
  "comment": "Phối hợp tốt trong Sprint",
  "createdAt": "2026-09-14T12:00:00",
  "updatedAt": "2026-09-14T12:05:00"
}
```

`starRating` trên response = **tổng sao**. `criteriaRatings` rỗng nếu lần nộp chỉ gửi tổng.

---

## 7. List — mọi review trong sprint

```http
GET /api/teams/{teamId}/sprints/{sprintId}/peer-reviews
```

```json
{
  "teamId": "uuid",
  "sprintId": "uuid",
  "sprintName": "Sprint 1",
  "reviews": [ ]
}
```

Mỗi phần tử trong `reviews` cùng shape với response POST (§6). Dùng cho bảng GV, ma trận sao, hoặc graph peer-review phía FE.

---

## 8. Lỗi thường gặp

| HTTP | `code` | Khi nào | UI gợi ý |
| --- | --- | --- | --- |
| 401 | `INVALID_CREDENTIALS` | Chưa login / hết session | Về login |
| 403 | `PEER_REVIEW_FORBIDDEN` | Không phải student trên team (candidates/submit); hoặc không phải member/GV/Admin (rubric/list) | Ẩn form; hiện “chỉ thành viên nhóm mới chấm” |
| 403 | `LECTURER_COURSE_FORBIDDEN` | GV sai course | Không mở team này |
| 404 | `TEAM_NOT_FOUND` | Sai `teamId` | Quay lại chọn team |
| 404 | `PROJECT_NOT_FOUND` | `sprintId` không thuộc project của team | Đổi sprint / sync Jira |
| 400 | `PEER_REVIEW_INVALID` | Self-review; reviewee khác team; team chưa project; thiếu/sai rubric; thiếu `starRating` | Message từ `message` |
| 400 | `REQUEST_INVALID` | Bean validation (thiếu `revieweeId`, sao ngoài 1–5 / 1–100, comment > 4000) | Check form |

---

## 9. Peer review và % đóng góp

Evaluate **đã** nhân hệ số P từ `peer_review.star_rating` (tổng sao người đó **được** chấm trong sprint / tổng sao team sprint). Sprint chưa ai chấm → P = 1.

| Field evaluate | FE làm gì |
| --- | --- |
| `finalContributionPercentage` | Hiện % cuối — **đã gồm P** |
| `peerReviewScore` | Hiện hệ số P (tham khảo). **Không** nhân thêm vào % |
| `warnings` chứa `NO_PEER_REVIEW` / `LOW_PEER_REVIEW` | Badge “chưa có peer” / “peer thấp” |

MEMBER không gọi evaluate (403). Họ chỉ nộp peer; Leader/GV xem %.

---

## 10. Checklist FE

Làm:

- [ ] `credentials: "include"` mọi request
- [ ] CSRF trên POST
- [ ] Lấy `teamId` từ my-team / lecturer teams; `sprintId` từ `GET /api/projects/{projectId}/sprints`
- [ ] Render sao từ `GET .../peer-review-rubric` → `criteria`
- [ ] `revieweeId` = `candidates[].studentId`
- [ ] POST đủ `criteriaRatings` khớp rubric
- [ ] Sau POST: refetch candidates (và evaluate nếu Leader)

Không làm:

- [ ] Path `/api/v1/...`
- [ ] Gửi `reviewerId` trong body
- [ ] Hard-code 4 `rubricId` hoặc field `weight`
- [ ] Cho Admin/GV bấm Submit (sẽ 403)
- [ ] Tự tính P hoặc nhân `peerReviewScore` vào %
- [ ] Dùng `externalSprintId` Jira làm path sprint

---

## 11. Copy-paste fetch

```js
const api = "/api";

async function loadPeerReviewForm(teamId, sprintId) {
  const [rubric, candidates] = await Promise.all([
    fetch(`${api}/teams/${teamId}/peer-review-rubric`, { credentials: "include" }).then((r) => r.json()),
    fetch(`${api}/teams/${teamId}/sprints/${sprintId}/peer-reviews/candidates`, {
      credentials: "include",
    }).then((r) => r.json()),
  ]);
  return { rubric, candidates };
}

async function submitPeerReview(teamId, sprintId, revieweeId, starsByRubricId, comment) {
  const csrf = await csrfHeader();
  const criteriaRatings = Object.entries(starsByRubricId).map(([rubricId, starRating]) => ({
    rubricId,
    starRating,
  }));
  const res = await fetch(`${api}/teams/${teamId}/sprints/${sprintId}/peer-reviews`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json", ...csrf },
    body: JSON.stringify({ revieweeId, criteriaRatings, comment }),
  });
  if (!res.ok) throw await res.json();
  return res.json();
}
```

`starsByRubricId` phải cover hết `rubric.criteria[].rubricId`.
