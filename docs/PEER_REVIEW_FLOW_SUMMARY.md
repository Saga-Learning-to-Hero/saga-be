# Luồng Peer Review — Tổng hợp cho Frontend

Peer review **đã ship** trên SAGA BE V2. Path **không** dùng `/api/v1`. Cookie session là `SAGA_SESSION`.

---

## 1. Luồng UI

1. `GET /api/peer-review-rubrics/default` — rubric global để render form.
2. `GET /api/teams/{teamId}/peer-review-rubric` — rubric theo team (subject nếu có, không thì global).
3. `GET /api/teams/{teamId}/sprints/{sprintId}/peer-reviews/candidates` — người có thể chấm (không gồm chính mình).
4. `POST /api/teams/{teamId}/sprints/{sprintId}/peer-reviews` — nộp / cập nhật (upsert).
5. `GET /api/teams/{teamId}/sprints/{sprintId}/peer-reviews` — list team/sprint.
6. `%` đóng góp đọc từ `GET /api/teams/{teamId}/contribution-evaluation` — FE không tự nhân hệ số P.

---

## 2. Auth / CSRF

- Cookie `SAGA_SESSION` + `credentials: "include"`.
- GET không cần CSRF.
- POST cần `GET /api/auth/csrf` rồi header `X-XSRF-TOKEN`.

```ts
const csrf = await fetch('/api/auth/csrf', { credentials: 'include' }).then((r) => r.json());

await fetch(`/api/teams/${teamId}/sprints/${sprintId}/peer-reviews`, {
  method: 'POST',
  credentials: 'include',
  headers: {
    'Content-Type': 'application/json',
    'X-XSRF-TOKEN': csrf.token,
  },
  body: JSON.stringify(body),
});
```

---

## 3. API

### 3.1 Rubric mặc định

`GET /api/peer-review-rubrics/default` — mọi user đã đăng nhập.

```json
{
  "teamId": null,
  "subjectId": null,
  "criteria": [
    {
      "rubricId": "c0a80101-0000-4000-8000-000000000001",
      "criteriaName": "Hoàn thành & Chất lượng",
      "description": "Làm đúng, đủ task được giao; code/chức năng chạy ổn định, ít lỗi."
    }
  ]
}
```

Bốn tiêu chí global được seed: Hoàn thành & Chất lượng, Tiến độ & Quy trình, Giao tiếp & Hỗ trợ, Thái độ & Xử lý sự cố.

### 3.2 Rubric theo team

`GET /api/teams/{teamId}/peer-review-rubric`

- ADMIN: mọi team
- LECTURER: instructor của course
- STUDENT: thành viên ACTIVE

`subjectId` khác null khi team dùng rubric của subject; null khi fallback global.

### 3.3 Candidates

`GET /api/teams/{teamId}/sprints/{sprintId}/peer-reviews/candidates`

Chỉ STUDENT thuộc team. ADMIN/LECTURER → `403 PEER_REVIEW_FORBIDDEN`.

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

`reviewerId` = student profile của session. Không gửi reviewer trong body.

### 3.4 Submit (upsert)

`POST /api/teams/{teamId}/sprints/{sprintId}/peer-reviews` — STUDENT thuộc team + CSRF.

Chấm từng tiêu chí (phải đủ mọi rubric đang active; mỗi tiêu chí 1–5 sao):

```json
{
  "revieweeId": "uuid",
  "criteriaRatings": [
    { "rubricId": "uuid-1", "starRating": 5 },
    { "rubricId": "uuid-2", "starRating": 4 },
    { "rubricId": "uuid-3", "starRating": 5 },
    { "rubricId": "uuid-4", "starRating": 4 }
  ],
  "comment": "Phối hợp tốt trong Sprint"
}
```

Hoặc chỉ tổng sao (`1–100`) khi không chấm từng tiêu chí:

```json
{ "revieweeId": "uuid", "starRating": 18, "comment": "Phối hợp tốt trong Sprint" }
```

`peer_review.star_rating` = tổng sao. Contribution đọc field này.

- Khóa upsert: `(sprint, reviewer, reviewee)`
- Self-review / reviewee khác team / team chưa có project → `400 PEER_REVIEW_INVALID`
- Sprint không thuộc project team → `404`

### 3.5 List

`GET /api/teams/{teamId}/sprints/{sprintId}/peer-reviews`

ADMIN mọi team; LECTURER instructor; STUDENT thuộc team. Sinh viên đọc được cả list (không ẩn danh).

---

## 4. Contribution

`P_s(i)` = sao nhận được / tổng sao team trong sprint. Chưa có peer → `1`. `finalContributionPercentage` đã gồm P. **Không** nhân `peerReviewScore` thêm trên FE.

---

## 5. Endpoints

```text
GET    /api/peer-review-rubrics/default
GET    /api/teams/{teamId}/peer-review-rubric
GET    /api/teams/{teamId}/sprints/{sprintId}/peer-reviews/candidates
POST   /api/teams/{teamId}/sprints/{sprintId}/peer-reviews
GET    /api/teams/{teamId}/sprints/{sprintId}/peer-reviews
GET    /api/teams/{teamId}/contribution-evaluation
```
