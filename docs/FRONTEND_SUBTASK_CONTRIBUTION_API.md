# Hướng dẫn Frontend — Subtask và % đóng góp

Ngày: 2026-10-02.

Công thức % đóng góp **không đổi**. FE vẫn không tự tính `%`. File này chỉ nói phần mới: story point của Subtask là phần trăm của cha, và cha có Subtask không còn bị cảnh báo thiếu minh chứng.

Playbook % đóng góp: `docs/FRONTEND_CONTRIBUTION_API.md`. Tạo/sửa task và `evidenceCheck`: `docs/FE_API_INTEGRATION_GUIDE_VI.md` §19.

Cookie `SAGA_SESSION`, `credentials: "include"`. `POST` / `PATCH` cần CSRF: header `X-XSRF-TOKEN` = cookie `XSRF-TOKEN`.

---

## 1. Một cột, hai nghĩa

Response task vẫn có `storyPoint` và `issueTypeLevel`. Nghĩa của `storyPoint` phụ thuộc `issueTypeLevel`.

| `issueTypeLevel` | Có Subtask? | `storyPoint` trên UI |
| --- | --- | --- |
| `STANDARD` (Story, Task, Bug) | Không | Điểm thật. Trống = backend tính 1 |
| `STANDARD` | Có | Trần của các Subtask. Trống = trần 1. Assignee của cha không nhận số này |
| `SUBTASK` | — | Thang **1–10**. `6` = **60%** trần của cha |
| `EPIC` / `ABOVE_EPIC` | — | Không đưa vào % đóng góp |

Không có field `contributionWeight`. Số 1–10 lưu ở `storyPoint` của Subtask (cột Jira story point).

Cha được nối bằng `jiraParentTaskId` lúc tạo. Backend resolve `jiraIntegrationId` + `parentExternalId`.

---

## 2. Form Subtask

`issueTypeLevel` của loại đang tạo là `SUBTASK` thì:

- Bắt buộc chọn cha (`jiraParentTaskId`). Cha phải là hạng mục `STANDARD`.
- Input story point: số nguyên **1–10**. Nhãn có thể vẫn là “Story point”, nhưng caption phải nói `6` = 60% của cha.
- Không cho nhập story point kiểu 13, 20.
- Không cho chọn sprint riêng. Subtask đi theo sprint của cha.
- Nhãn `saga:code` / `saga:test` / `saga:document` / `saga:research` gắn trên **cha**. Subtask không có nhãn riêng cho việc chấm điểm.

```http
POST /api/projects/{projectId}/tasks
```

```json
{
  "summary": "Viết API login",
  "issueTypeId": "<id loại Subtask>",
  "jiraParentTaskId": "<uuid task cha>",
  "storyPoints": 6,
  "assigneeAccountId": "<jira account>"
}
```

Sửa số đã nhập:

```http
PATCH /api/projects/{projectId}/tasks/{taskId}
{ "storyPoints": 4 }
```

`parentTaskId` là tên cũ của `jiraParentTaskId`. Form mới chỉ gửi `jiraParentTaskId`.

### Trần 100%

Tổng `storyPoint × 10` của mọi Subtask cùng cha ≤ 100.

Đã có một Subtask `6` (60%) thì các Subtask còn lại cộng lại tối đa `4` (40%). Chưa dùng hết 40% vẫn tạo được. Vượt thì `400`:

```json
{
  "code": "TASK_SUBTASK_PERCENT_INVALID",
  "message": "Subtask shares of this parent would total 110%. They must total at most 100%.",
  "details": {
    "usedPercent": 60,
    "requestedPercent": 50,
    "maxPercent": 100
  }
}
```

Số ngoài 1–10 cũng `TASK_SUBTASK_PERCENT_INVALID`, `details` có `minPoints: 1`, `maxPoints: 10`.

FE nên chặn trước khi gọi API: còn lại = `100 - tổng (storyPoint × 10) của Subtask anh em`. Ô nhập tối đa là `min(10, còn lại / 10)`.

Hạng mục `STANDARD` không bị giới hạn 1–10. Story point cha vẫn là điểm Jira bình thường.

---

## 3. Khi nào Subtask được điểm

Backend tự chia. FE chỉ hiển thị `%` từ `GET /api/teams/{teamId}/contribution-evaluation`.

```text
phần trăm = storyPoint(subtask) × 10
share     = storyPoint(cha) × phần trăm / 100
```

Cha 10 điểm, Subtask A nhập 6, Subtask B nhập 4:

| | A | B |
| --- | --- | --- |
| Nhập | 6 | 4 |
| % của cha | 60% | 40% |
| Share | 6 | 4 |

Share chỉ vào công thức khi **cả cha và Subtask đó đều `DONE`**. B chưa DONE thì B nhận 0, 4 điểm không sang A. Cha chưa DONE thì cả hai nhận 0.

Assignee của cha không nhận story point cha. Muốn tính công review / quản lý thì tạo một Subtask và gán cho người đó.

Sprint lấy từ cha. Nhãn `saga:*` là của chính Subtask: form tạo và sửa Subtask gửi `labels` như task thường (`saga:code`, `saga:test`, `saga:document`, `saga:research`), tối đa một nhãn. Cha `saga:code` vẫn có Subtask `saga:test`. `saga:document` / `saga:research` cần file hoặc link trên **Subtask**, không cần trên cha. `saga:code` / `saga:test` cần commit không phải merge gắn trên **Subtask** và Subtask đã DONE. Commit không cộng thêm điểm.

Epic không tạo điểm.

---

## 4. Cảnh báo evidence và commit

Đọc `evidenceCheck` trên `GET /api/projects/{projectId}/tasks`. Đừng tự đoán theo `linkedCommitCount` hay `evidenceCount`.

Cha đã có ít nhất một Subtask:

- `requiresCommit` = `false`
- `requiresDocument` = `false`
- `status` không phải `MISSING_COMMIT` / `MISSING_DOCUMENT`

File, link và commit nằm trên Subtask. `evidenceCheck` của Subtask dùng nhãn của chính Subtask:

| Nhãn trên Subtask | Subtask cần |
| --- | --- |
| `saga:code`, `saga:test` | Commit không phải merge |
| `saga:document`, `saga:research` | File, link, hoặc attachment |

Subtask `DONE` mà thiếu phần đó vẫn hiện cảnh báo trên Subtask. Cha thì không.

---

## 5. Việc FE không làm

- Không nhân `storyPoint` của Subtask vào % đóng góp thêm một lần. Evaluate đã gồm share.
- Không cộng story point cha với story point các Subtask.
- Không cảnh báo cha thiếu commit hoặc thiếu tài liệu khi cha đã có Subtask và `evidenceCheck.status` là `SATISFIED`.
- Không gửi story point Subtask ngoài 1–10, và không gửi tổng vượt 100%.
