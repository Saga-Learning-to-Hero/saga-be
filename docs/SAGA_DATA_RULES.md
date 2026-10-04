# Quy tắc dữ liệu SAGA

Quy tắc cho người, task, commit, và cách điền form tạo task. Điểm đóng góp chỉ lấy từ task **DONE**. Commit dùng để gắn task và làm minh chứng, không cộng điểm thêm.

---

## 1. Người thật, task thật

Không đặt tên dữ liệu là demo, test, fake, sample hay dummy.

Người là người thật: họ tên, email đúng miền trường, mã sinh viên đúng dạng. Không dùng `lecturer.demo`, `user1`, `svdemo`.

Task là việc thật của project. Summary mô tả việc cụ thể, ví dụ `Tạo form đăng nhập`. Key do Jira sinh, dạng `CHỮ-SỐ` như `SAGA-123`. Không đặt key hay tiêu đề kiểu `DEMO-1`, `Demo board`, `task test`.

Sprint, project và repo cùng quy tắc: tên là nhóm và việc thật, không phải nhãn demo.

---

## 2. Commit

Commit đúng phải có dạng:

```text
feat:[task external key] tạo form đăng nhập
```

`task external key` là key Jira của task, ví dụ `SAGA-123`. Message đầy đủ:

```text
feat:[SAGA-123] tạo form đăng nhập
```

- `feat:` đứng đầu.
- Key nằm trong ngoặc vuông, sát dấu `:`, không có khoảng trắng: `[SAGA-123]`.
- Phần sau ngoặc là mô tả ngắn việc vừa làm.

SAGA đọc message commit (và tên nhánh) để tìm key dạng `CHỮ-SỐ`. Key khớp đúng một task còn sống của nguồn Jira đang kết nối thì commit được gắn task đó. Key không khớp, hoặc hai nguồn Jira cùng project key, thì không gắn.

Commit **merge không tính** là minh chứng, dù vẫn có thể xuất hiện trong danh sách commit đã link.

Pull request được quét ở tiêu đề, mô tả và tên nhánh. Muốn gắn task thì cũng đưa key vào, cùng dạng `[SAGA-123]`.

---

## 3. Đặt tên task

Key (`SAGA-123`) do Jira sinh khi tạo task. Người dùng không tự đặt key.

Người dùng chỉ viết **summary** (tiêu đề), tối đa 255 ký tự. Không có mẫu bắt buộc trong tiêu đề. Loại việc do **nhãn** quyết định, không phải bởi chữ trong tiêu đề.

Ví dụ summary: `Tạo form đăng nhập`.

---

## 4. Task được tính điểm khi nào

Một task chỉ vào công thức đóng góp khi đủ hết:

- trạng thái `DONE`
- đã vào **sprint** (task backlog = 0 điểm)
- **đúng một** nhãn: `saga:code`, `saga:test`, `saga:document`, hoặc `saga:research`
- `storyPoint` để trống thì tính là **1**

| Nhãn | Minh chứng cần có |
|---|---|
| `saga:code`, `saga:test` | Ít nhất một commit đã link, không phải merge |
| `saga:document`, `saga:research` | Ít nhất một file, link, hoặc attachment. Commit không thay được tài liệu |

Hai nhãn SAGA cùng lúc thì task bị coi là mơ hồ và không được điểm. `saga:doc` được đổi thành `saga:document`.

Task cha đã có Subtask thì minh chứng nằm trên Subtask. Nhãn và sprint lấy từ cha. Người được giao task cha không nhận story point của cha.

---

## 5. Tạo task — điền những gì

`POST /api/projects/{projectId}/tasks`.

Trước khi tạo phải liên kết Jira cá nhân. Task nhãn `saga:code` hoặc `saga:test` còn phải liên kết GitHub. Thiếu thì `403 PERSONAL_INTEGRATION_REQUIRED`.

Gọi `GET /api/projects/{projectId}/tasks/options` để lấy danh sách loại thẻ, độ ưu tiên, người được giao, sprint và nhãn. Dùng đúng `id` từ response đó.

| Field | Cách điền |
|---|---|
| `summary` | Bắt buộc. Tiêu đề, tối đa 255 ký tự. |
| `description` | Tuỳ chọn, tối đa 10.000 ký tự. |
| `issueTypeId` | Lấy từ `issueTypes`. Chọn theo `level`, không theo tên hiển thị. |
| `labels` | Tối đa một: `saga:code`, `saga:test`, `saga:document`, `saga:research`. |
| `storyPoints` | Hạng mục thường: điểm Jira. Để trống thì khi chấm tính là 1. Subtask: số nguyên 1–10. `6` = 60% điểm cha. Tổng các Subtask cùng cha không quá 100%. |
| `sprintId` | Id sprint từ options. Subtask không chọn sprint — đi theo cha. |
| `assigneeAccountId` | Jira `accountId` trong `assignableUsers`. Không gửi UUID của SAGA. Thành viên tạo task thì hệ thống tự gán cho chính mình. |
| `priorityId` | Tuỳ chọn, lấy từ options. |
| `startDate`, `dueDate` | `yyyy-MM-dd`. Ngày bắt đầu không được sau hạn. Task đã vào sprint thì cả hai ngày phải nằm trong sprint. |
| `jiraParentTaskId` | Epic cha: tuỳ chọn với hạng mục thường. Bắt buộc với Subtask. Cha của Subtask phải là hạng mục `STANDARD`. |
| `jiraIntegrationId` | Bắt buộc khi project có hơn một nguồn Jira. |

### Hạng mục thường (code)

```json
{
  "summary": "Tạo form đăng nhập",
  "description": "Form email và mật khẩu, gọi API đăng nhập",
  "issueTypeId": "<id loại Task hoặc Story>",
  "labels": ["saga:code"],
  "storyPoints": 5,
  "sprintId": 123,
  "assigneeAccountId": "<jira accountId>",
  "startDate": "2026-10-03",
  "dueDate": "2026-10-10"
}
```

Commit tương ứng:

```text
feat:[SAGA-123] tạo form đăng nhập
```

`SAGA-123` là `externalKey` Jira trả về sau khi tạo task, không phải chữ tự đặt trong summary.

### Subtask

Story point Subtask là thang 1–10 trên trần của cha. Không gửi nhãn và không gửi sprint.

```json
{
  "summary": "Tạo form đăng nhập",
  "issueTypeId": "<id loại Subtask>",
  "jiraParentTaskId": "<uuid task cha>",
  "storyPoints": 6,
  "assigneeAccountId": "<jira accountId>"
}
```

`6` nghĩa là 60% story point của cha. Đã có một Subtask `6` thì các Subtask còn lại cộng lại tối đa `4`. Vượt 100% hoặc số ngoài 1–10 thì `400 TASK_SUBTASK_PERCENT_INVALID`.

Share của một Subtask vào điểm khi Subtask đó `DONE`. Cha và các Subtask khác chưa xong không chặn share này. Sprint lấy từ cha. Nhãn `saga:*` là của chính Subtask. Task `saga:document` hoặc `saga:research` cần file hoặc link trên Subtask.

### Cấp loại thẻ

| Cấp | Cha khi tạo | Sprint |
|---|---|---|
| `EPIC` | Không có | Không chọn |
| `STANDARD` (Task, Story, Bug…) | Epic, tuỳ chọn | Có |
| `SUBTASK` | Hạng mục `STANDARD`, bắt buộc | Theo cha, không chọn riêng |
| `ABOVE_EPIC` | Không tạo từ SAGA | — |

Đổi trạng thái task không dùng PATCH. Dùng `POST /api/projects/{projectId}/tasks/{taskId}/transition` với transition Jira cho phép.
