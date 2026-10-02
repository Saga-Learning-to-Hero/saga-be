# Graph phân rã công việc — contract gửi FE

Backend đã ghi `PARENT_OF` và `HAS_WORK_ITEM` lên Neo4j. `graphVersion` là **3**. Project đang lưu graph cũ sẽ tự xóa và dựng lại lần đầu được mở sau khi deploy. FE không gọi API dựng graph.

Id node task là `task:{uuid}` nội bộ, không phải `task:SAGA-15`. Label hiển thị vẫn là Jira key.

## Cạnh mới

| `label` | Chiều | Ý nghĩa |
| --- | --- | --- |
| `HAS_WORK_ITEM` | Project → Task | Item không có cha trên Jira: Epic, Standard không thuộc Epic, hoặc cấp trên Epic không có cha |
| `PARENT_OF` | Task → Task | Cha → con đúng như Jira |

Không dùng `DECOMPOSED_INTO`. `OWNS` vẫn chỉ là Team → Project. `CONTAINS` vẫn là Sprint → Task (kế hoạch), không phải cha.

Chuỗi hiển thị:

```text
PROJECT ─HAS_WORK_ITEM→ EPIC ─PARENT_OF→ STORY / TASK / BUG ─PARENT_OF→ SUBTASK ─EVIDENCED_BY→ COMMIT
```

Story và Task cùng cấp. Không có cạnh Story → Task. Initiative (cấp trên Epic) vẫn hiện, chỉ đọc.

## Node TASK

Các field này chỉ có trên node `TASK`:

| Field | Dùng để |
| --- | --- |
| `issueTypeLevel` | `EPIC` / `STANDARD` / `SUBTASK` / `ABOVE_EPIC` / `UNKNOWN` — chọn kích thước |
| `jiraHierarchyLevel` | Số thô Jira: `-1` / `0` / `1` / `2+` |
| `issueTypeId` | Id loại thẻ trên Jira |
| `issueTypeName` | Tên đúng trên Jira, ví dụ `Feature`, `User Story` |
| `issueType` | Nhóm hiển thị. Feature nằm ở `TASK`. Không dùng field này để suy cấp |
| `parentExternalKey` | Key cha, chỉ để hiện chữ |
| `parentResolution` | `RESOLVED` hoặc `UNRESOLVED`. Thiếu field = item cấp cao nhất |
| `parentResolutionReason` | `PARENT_NOT_SYNCED` hoặc `PARENT_SOURCE_REVOKED` khi unresolved |

Item có cha trên Jira mà SAGA chưa có cha đó vẫn hiện trên graph, **không** có `HAS_WORK_ITEM`. FE hiện cảnh báo theo `parentResolution = "UNRESOLVED"`.

## Graph nào trả cạnh nào

| Graph | `PARENT_OF` | `HAS_WORK_ITEM` |
| --- | --- | --- |
| 1 Overview | Có. Cả project trả mọi cặp. Theo sprint trả task của sprint, chuỗi cha, subtask con | Có, tới gốc mỗi chuỗi |
| 2 Contribution | Không | Không |
| 3 Activity | Có, chuỗi cha và subtask của task trong sprint | Có |
| 4 Attribution | Có, từ task được commit chứng minh đi lên cha | Có |

`focusNodeId` + `depth` lọc trên dữ liệu của view đang gọi, nên drill-down overview / activity / attribution vẫn thấy `PARENT_OF`.

## Gợi ý vẽ

| `issueTypeLevel` | Kích thước tương đối |
| --- | ---: |
| Project | 1.50 |
| `ABOVE_EPIC` | 1.40 |
| `EPIC` | 1.30 |
| `STANDARD` | 1.00 |
| `SUBTASK` | 0.78 |
| Commit | 0.72 |

`PARENT_OF` dùng nét đứt. `HAS_WORK_ITEM`, `CONTAINS`, `EVIDENCED_BY` giữ nét liền. Kích thước và nét chỉ là hiển thị, không đổi cách tính điểm.

Chi tiết payload: `docs/FRONTEND_GRAPH_API.md`.
