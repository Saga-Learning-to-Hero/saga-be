# FE note — Avatar sinh viên trên các API GET danh sách

Additive. Không đổi path, query, hay field cũ. Field mới là `string | null`: URL `http`/`https` người dùng đã lưu trên hồ sơ, hoặc `null` khi chưa có ảnh / chưa có tài khoản.

Nguồn: `user_account.avatar_url`.

Hai chỗ **đã có từ trước**, tên field là `avatar` (không phải `avatarUrl`):

| API | Field |
| --- | --- |
| `GET /api/courses/{courseId}/teams/{teamId}/heatmap` | `students[].avatar`, `days[].actors[].avatar` |
| Graph node `STUDENT` (`/graph/overview`, `/graph/activity`, `/graph/attribution`, `/graph/peer-review`, `/students/{studentId}/graph/contribution`) | `data.avatar` |

`GET /api/admin/users` đã có `items[].avatarUrl`. Work-session trong timeline đã có `workSessions.sessions[].avatarUrl` và `workSessions.openSessions[].avatarUrl`.

---

## Field mới

### Roster và team

`GET /api/admin/courses/{courseId}/roster`

```json
{
  "entries": [
    {
      "kind": "ENROLLMENT",
      "studentUserId": "uuid",
      "studentCode": "SE123456",
      "fullName": "Nguyen Van A",
      "avatarUrl": "https://example.com/avatar.png",
      "email": "a@fpt.edu.vn"
    }
  ]
}
```

`kind = "INVITATION"` (chưa đăng ký) thì `avatarUrl` luôn `null`.

`GET /api/lecturer/courses/{courseId}/roster` — `entries[].avatarUrl` (cạnh `fullName`, `studentCode`).

`GET /api/lecturer/courses/{courseId}/teams` — `teams[].members[].avatarUrl`.

`GET /api/student/courses/{courseId}/team` — `members[].avatarUrl`.

### Peer review

`GET /api/teams/{teamId}/sprints/{sprintId}/peer-reviews/candidates` — `candidates[].avatarUrl`.

`GET /api/teams/{teamId}/sprints/{sprintId}/peer-reviews`

```json
{
  "reviews": [
    {
      "reviewerId": "uuid",
      "reviewerName": "Nguyen Van A",
      "reviewerAvatarUrl": "https://example.com/a.png",
      "revieweeId": "uuid",
      "revieweeName": "Tran Thi B",
      "revieweeAvatarUrl": null
    }
  ]
}
```

### Đóng góp và tiến độ

`GET /api/teams/{teamId}/contribution-evaluation` — `members[].avatarUrl`.

`GET /api/projects/{projectId}/progress` — `memberProgress[].avatarUrl`.

`GET /api/projects/{projectId}/progress/members/{studentId}` — `avatarUrl` (cùng cấp với `fullName`, `studentCode`).

### Task và commit

`GET /api/projects/{projectId}/tasks` (và detail `GET .../tasks/{taskId}`)

```json
{
  "assignee": {
    "accountId": "jira-account-id",
    "displayName": "Nguyen Van A",
    "studentId": "uuid",
    "avatarUrl": "https://example.com/avatar.png"
  }
}
```

`assignee` null khi task chưa gán. `avatarUrl` null khi assignee Jira chưa map sang sinh viên SAGA.

`GET /api/projects/{projectId}/commits` và `GET /api/projects/{projectId}/tasks/{taskId}/commits` — `items[].authorAvatarUrl` (cạnh `authorStudentId`).

`GET /api/projects/{projectId}/tasks/{taskId}/work-session-timeline` — `commits.items[].authorAvatarUrl`.

Commit chưa map author (`authorStudentId` null) thì `authorAvatarUrl` là `null`.
