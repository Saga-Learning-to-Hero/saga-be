# Hướng dẫn Frontend — Luồng graph (Cytoscape)

Playbook kéo **5 API đồ thị** khi FE vẽ canvas Cytoscape.js. FE **không** tự nối Task/Commit từ list API khác thành graph — một GET trả đủ `{ nodes, edges }`. Đây **không** phải % đóng góp hay điểm; công thức grade vẫn ở `GET /api/teams/{teamId}/contribution-evaluation`.

Quyết định mô hình (tên cạnh, Criterion, Identity, không vẽ PR): `docs/GRAPHS_TO_DRAW.md` §IV. Contract tổng: `docs/FRONTEND_API_INTEGRATION.md` §7. Evidence file/link: `docs/FRONTEND_TASK_EVIDENCE_API.md`. Peer review sao: `docs/FRONTEND_PEER_REVIEW_API.md`.

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

**GET không CSRF.** Năm endpoint graph đều GET.

GET **không** rebuild Neo4j mỗi lần. Mutation Jira/GitHub/evidence/peer-review đánh dấu project dirty → debounce ~400ms → **một** rebuild → SSE `GRAPH_CHANGED`. Đổi sprint / đổi loại graph chỉ đọc projection đã có.

Gọi GET khi mở màn. Đổi sprint/mode: gọi lại GET (nhẹ). Cập nhật realtime: nghe `GRAPH_CHANGED` rồi GET lại. Có `If-None-Match` / `304`.

Kéo list task/sprint vẫn dùng `TASKS_CHANGED` / `SPRINTS_CHANGED` như cũ — **đừng** lấy full graph trên mọi event list.

---

## 1. Lấy `projectId` (và sprint / student) trước

### Student (mọi thành viên ACTIVE, không chỉ Leader)

```text
GET /api/student/courses
GET /api/student/courses/{courseId}/team      → projectId
GET /api/projects/{projectId}/sprints         → id UUID từng sprint
GET /api/projects/{projectId}/graph/overview  → node STUDENT (lấy studentId từ id)
```

- `projectId == null` → Leader chưa tạo project. Không gọi graph; hiện “nhóm chưa setup project”.
- 404 team → chưa vào team.
- Sprint dùng **`id` UUID SAGA**, không dùng `externalSprintId` Jira.
- `studentId` trên path Graph 2 là UUID **StudentProfile**, **không** phải `GET /api/auth/me` → `user.id` (đó là UserAccount).

Cách lấy `studentId` an toàn: từ Graph 1, node `type === "STUDENT"`, cắt prefix:

```text
student:3f2a…  →  3f2a…
```

Cùng UUID với `assignee.studentId` trên task, `candidates[].studentId` peer review, `members[].studentProfileId` phía lecturer.

### Lecturer

```text
GET /api/lecturer/courses/{courseId}/teams    → projectId, members[].studentProfileId
GET /api/projects/{projectId}/sprints
GET /api/projects/{projectId}/graph/overview
```

ADMIN **không** gọi được (403) — cùng policy đọc Task/Commit.

---

## 2. Map màn hình → API

| Màn hình | Gọi |
| --- | --- |
| Tổng quan nhóm (Graph 1) | `GET /api/projects/{projectId}/graph/overview` |
| Tổng quan **một sprint** | thêm `?sprintId={sprintId}` |
| Đường đóng góp 1 SV (Graph 2) | `GET /api/projects/{projectId}/students/{studentId}/graph/contribution` |
| Graph 2 theo sprint | thêm `?sprintId={sprintId}` |
| Lát cắt sprint (Graph 3) | `GET /api/projects/{projectId}/sprints/{sprintId}/graph/activity` |
| Attribution / commit mồ côi (Graph 4) | `GET /api/projects/{projectId}/graph/attribution` |
| Graph 4 theo sprint | thêm `?sprintId={sprintId}` |
| Mạng peer review (Graph 5) | `GET /api/projects/{projectId}/sprints/{sprintId}/graph/peer-review` |

Graph 3 và 5 **bắt buộc** `sprintId` trên path. Không có query “cả project”.

---

## 3. Quyền

Cùng `ProjectDataAuthorization.requireReader` với `GET /api/projects/{projectId}/tasks`.

| Caller | Kết quả |
| --- | --- |
| STUDENT member ACTIVE | 200 — xem graph **cả team** (kể cả contribution teammate nếu biết `studentId`) |
| STUDENT không thuộc team | `403 INTEGRATION_FORBIDDEN` |
| LECTURER đúng course | 200 |
| LECTURER sai course | `403 LECTURER_COURSE_FORBIDDEN` |
| ADMIN | `403 ACCESS_DENIED` |
| Chưa login | 401 |

`studentId` không phải member ACTIVE của project → `404 ROSTER_STUDENT_NOT_FOUND`.

`sprintId` không thuộc project hoặc đã xóa mềm → `404 PROJECT_NOT_FOUND` (“Sprint was not found for this project.”).

---

## 4. Request

```http
GET /api/projects/{projectId}/graph/overview
GET /api/projects/{projectId}/graph/overview?sprintId={uuid}

GET /api/projects/{projectId}/students/{studentId}/graph/contribution
GET /api/projects/{projectId}/students/{studentId}/graph/contribution?sprintId={uuid}

GET /api/projects/{projectId}/sprints/{sprintId}/graph/activity

GET /api/projects/{projectId}/graph/attribution
GET /api/projects/{projectId}/graph/attribution?sprintId={uuid}

GET /api/projects/{projectId}/sprints/{sprintId}/graph/peer-review
```

Không CSRF. Không body.

Optional:

```http
If-None-Match: "graph-{projectId}-{revision}"
```

Response headers: `ETag`, `X-Graph-Revision`. Cùng revision **và cùng query** → `304` (không body). Không có query subgraph mới: body vẫn `{ nodes, edges }` (không `meta`), ETag vẫn `graph-{projectId}-{revision}`. Có filter: ETag thêm hash view (`graph-{projectId}-{revision}-{16 hex}`).

### 4.2 Subgraph filters (progressive loading)

Đồ thị lớn: **đừng** tải full rồi ẩn trên Cytoscape. GET lại cùng endpoint với query dưới đây. **Không** rebuild Neo4j khi đổi filter/focus — chỉ đọc projection hiện có.

| Param | Bắt buộc | Default | Ghi chú |
| --- | --- | --- | --- |
| `focusNodeId` | không | — | `task:{id}`, `student:{id}`, … đúng prefix mục 5.1. Phải nằm **trong** graph đã scoped (project + `sprintId` nếu có). Sai scope → `400 REQUEST_INVALID`. |
| `depth` | không | `1` | Neighborhood vô hướng từ `focusNodeId` (hoặc từ anomaly). Chỉ `1`–`3`. **Chỉ có `depth` thì bị bỏ qua** — phải kèm focus / type / paging. |
| `nodeTypes` | không | Overview/Activity: không COMMIT. Graph khác: mọi type | CSV enum canonical: `STUDENT,TEAM,PROJECT,SPRINT,TASK,COMMIT,CRITERION,IDENTITY`. Sai enum → 400. |
| `edgeTypes` | không | mọi label | CSV: `MEMBER_OF,OWNS,HAS_SPRINT,CONTAINS,ASSIGNED_TO,EVIDENCED_BY,CLASSIFIED_AS,AUTHORED_BY,MAPS_TO,REVIEWED`. |
| `anomaliesOnly` | không | `false` | `true` = anomaly **kèm neighborhood** (không trả node cô lập nếu chúng còn cạnh). |
| `maxNodes` | không | — | `1`–`2000`. Cắt theo thứ tự ổn định trong cùng revision. |
| `cursor` | không | — | Token `revision:lastNodeId` từ `meta.nextCursor`. Alias: `continuationToken`. Sai revision → 400. |
| `includeCommits` | không | `false` trên Graph 1 và 3 | `true` = vẽ đủ SHA như trước (mạng nhện). Graph 2/4/5 không dùng default compact. |
| `usedCriteriaOnly` | không | `false` | Graph 2: `true` = chỉ Criterion có cạnh `CLASSIFIED_AS`. Mặc định vẫn đủ 4 CODE/TEST/DOCUMENT/RESEARCH. |

Ví dụ overview (mặc định đã gọn) + drill-down task:

```http
GET /api/projects/{projectId}/graph/overview?sprintId={sprintId}
GET /api/projects/{projectId}/graph/overview?includeCommits=true
GET /api/projects/{projectId}/graph/overview
    ?sprintId={sprintId}
    &focusNodeId=task:{taskId}
    &depth=1
    &nodeTypes=TASK,COMMIT,STUDENT
    &edgeTypes=ASSIGNED_TO,EVIDENCED_BY
    &maxNodes=200
```

Khi **bất kỳ** param subgraph nào active, body thêm `meta` (node/edge **shape không đổi**):

```json
{
  "nodes": [],
  "edges": [],
  "meta": {
    "revision": "4",
    "totalNodes": 850,
    "totalEdges": 1200,
    "returnedNodes": 200,
    "returnedEdges": 310,
    "truncated": true,
    "nextCursor": "4:task:…"
  }
}
```

Quy tắc:

- Không bao giờ trả cạnh nếu `source` hoặc `target` vắng trong `nodes`.
- `truncated: true` → lấy trang sau cùng filter + `cursor=meta.nextCursor`.
- Cursor **chỉ** valid khi `X-Graph-Revision` / `meta.revision` còn đúng. SSE `GRAPH_CHANGED` → bỏ cursor, GET lại từ đầu.
- `sprintId` (query hoặc path) vẫn lọc MySQL/Neo4j scope trước; `nodeTypes`/`edgeTypes` lọc tiếp trên lát đó.

---

## 4.1 Realtime — `GRAPH_CHANGED`

`GET /api/projects/{projectId}/events` (SSE, session cookie). Event **mới**:

```json
{
  "type": "GRAPH_CHANGED",
  "projectId": "...",
  "revision": "12",
  "reason": "TASKS_CHANGED,COMMITS_CHANGED",
  "occurredAt": "2026-09-15T13:00:00Z"
}
```

Chỉ phát **sau** khi Neo4j rebuild xong. `reason` có thể gộp nhiều mutation. **Không** chứa nodes/edges.

Peer review nộp xong còn có `PEER_REVIEW_CHANGED` (list/form). Graph 5: nghe `GRAPH_CHANGED` rồi GET graph.

Luồng FE:

1. Mở canvas → GET graph.
2. Lưu `ETag` / `X-Graph-Revision`.
3. SSE: `GRAPH_CHANGED` → GET graph kèm `If-None-Match`.
4. Đổi sprint/mode → GET graph (không đợi SSE). `304` nếu chưa có projection mới.

Event `TASKS_CHANGED` / `COMMITS_CHANGED` / … vẫn cho **bảng** task/commit, không bắt buộc refetch graph.

---

## 5. Response — Cytoscape elements

`200`. Project chưa có task/commit/sprint vẫn 200, `nodes` / `edges` có thể `[]` — **không** 404.

Field `null` **bị ommit** (`JsonInclude.NON_NULL`). Đừng `=== false` trên `isAnomaly` khi key vắng.

```ts
interface CytoscapeGraphResponse {
  nodes: Array<{ data: CytoscapeNodeData }>;
  edges: Array<{ data: CytoscapeEdgeData }>;
  meta?: {
    revision: string;
    totalNodes: number;
    totalEdges: number;
    returnedNodes: number;
    returnedEdges: number;
    truncated: boolean;
    nextCursor?: string;
  };
}

interface CytoscapeNodeData {
  id: string;           // xem bảng prefix — không phải UUID trần
  label: string;
  subLabel?: string;
  type:
    | "STUDENT"
    | "TEAM"
    | "PROJECT"
    | "SPRINT"
    | "TASK"
    | "COMMIT"
    | "CRITERION"
    | "IDENTITY";
  status?: string;      // TASK: TODO | IN_PROGRESS | IN_REVIEW | DONE | BLOCKED
  weightType?: "CODE" | "TEST" | "DOCUMENT" | "RESEARCH";
  isAnomaly?: boolean;
  avatar?: string;
  role?: string;        // STUDENT: LEADER | MEMBER | …
  storyPoint?: number;  // TASK
}

interface CytoscapeEdgeData {
  id: string;
  source: string;       // = node.data.id
  target: string;
  label:
    | "MEMBER_OF"
    | "OWNS"
    | "HAS_SPRINT"
    | "CONTAINS"
    | "ASSIGNED_TO"
    | "EVIDENCED_BY"
    | "CLASSIFIED_AS"
    | "AUTHORED_BY"
    | "MAPS_TO"
    | "REVIEWED";
  weight?: number;      // Graph 5: số sao
  isAnomaly?: boolean;  // hiện không set trên cạnh; anomaly nằm ở node
}
```

Gắn thẳng vào Cytoscape:

```js
cy.json({ elements: payload }); // hoặc cy.add(payload.nodes.concat(payload.edges))
```

Style CSS theo `node[type = "TASK"]` và `edge[label = "EVIDENCED_BY"]` — **đúng string trên**, không dùng `IMPLEMENTS` / `AUTHORED` / `DOC`.

### 5.1 `id` node (prefix)

| `type` | `id` |
| --- | --- |
| STUDENT | `student:{studentProfileId}` |
| TEAM | `team:{teamId}` |
| PROJECT | `project:{projectId}` |
| SPRINT | `sprint:{sprintId}` |
| TASK | `task:{taskId}` |
| COMMIT | `commit:{gitCommitId}` |
| IDENTITY | `identity:{projectId}:GITHUB:{githubLoginOrSubject}` |
| CRITERION | `crit_code` / `crit_test` / `crit_document` / `crit_research` |

Label hiển thị: TASK = Jira key; COMMIT = SHA 7 ký tự; CRITERION = `CODE`…; IDENTITY = GitHub username (`unmapped` nếu trống).

### 5.2 Chiều cạnh (source → target)

| `label` | Chiều | Graph |
| --- | --- | --- |
| `MEMBER_OF` | Student → Team | 1 |
| `OWNS` | Team → Project | 1 |
| `HAS_SPRINT` | Project → Sprint | 1 |
| `CONTAINS` | Sprint → Task | 1, 3 |
| `ASSIGNED_TO` | Student → Task | 1–4 |
| `EVIDENCED_BY` | **Task → Commit** | 1–4 |
| `CLASSIFIED_AS` | Task → Criterion | 2, 3 |
| `AUTHORED_BY` | Commit → Identity | 4 |
| `MAPS_TO` | Identity → Student | 4 |
| `REVIEWED` | Student → Student | 5 |

Không đảo `EVIDENCED_BY`. Task là nguồn điểm; commit là bằng chứng.

Phase này **không** có node `PULL_REQUEST`.

---

## 6. Từng graph — node/cạnh và filter sprint

### Graph 1 — Overview

Không Criterion, không Identity, không `REVIEWED`.

Không `sprintId`: cả project, **gồm task backlog** (task không nằm sprint). Có `sprintId`: chỉ sprint đó, **không** backlog.

Mặc định **không trả COMMIT** (đó là thứ làm canvas thành mạng nhện). Còn STUDENT / TEAM / PROJECT / SPRINT / TASK. `meta.totalNodes` vẫn đếm cả commit bị ẩn — click task rồi GET `focusNodeId=task:…&depth=1&nodeTypes=TASK,COMMIT,STUDENT`. Muốn bản cũ đủ SHA: `includeCommits=true`.

### Graph 2 — Contribution path

Luôn trả **4 node Criterion** (kể cả chưa có cạnh). Chỉ task **DONE** đã gán sinh viên đó **và** đang thuộc một sprint. Backlog / task chưa DONE không vào graph này.

Ẩn tiêu chí chưa dùng: `usedCriteriaOnly=true` — chỉ còn Criterion có `CLASSIFIED_AS`. Bỏ param = đủ 4 node như cũ.

Drill-down evidence: `focusNodeId=task:{taskId}&depth=1&nodeTypes=TASK,COMMIT&edgeTypes=EVIDENCED_BY`.

`weightType` trên task: `CODE` \| `TEST` \| `DOCUMENT` \| `RESEARCH`. Không viết `DOC`. Task DOCUMENT/RESEARCH thiếu file/link → **không** có cạnh `CLASSIFIED_AS`.

### Graph 3 — Sprint activity

Mọi task trong sprint (mọi status) + Criterion + assignee. **Commit mặc định không vẽ hết** — tải dần theo task: `focusNodeId=task:{taskId}&depth=1&nodeTypes=TASK,COMMIT&edgeTypes=EVIDENCED_BY&maxNodes=200`. Đủ SHA một lần: `includeCommits=true`.

### Graph 4 — Attribution

`Commit → Identity → Student?`. Identity / Commit `isAnomaly: true` khi **chưa** `MAPS_TO` Student (commit mồ côi — vẫn vẽ).

Không `sprintId`: mọi commit của project. Có `sprintId`: chỉ commit đã `EVIDENCED_BY` task trong sprint đó.

Highlight: node IDENTITY/COMMIT `isAnomaly`, hoặc Identity không có cạnh `MAPS_TO` đi ra.

Ưu tiên anomaly + context: `anomaliesOnly=true` (Identity chưa map, commit mồ côi — **kèm** student/task/commit kề, không chỉ chấm đỏ đơn lẻ).

### Graph 5 — Peer review

Chỉ STUDENT + `REVIEWED`. `edge.data.weight` = tổng sao. Mọi member ACTIVE vẫn là node dù chưa ai chấm (cạnh `[]`).

Team nhỏ: **không cần** `maxNodes`/`cursor`. Filter vẫn dùng được nếu cần.

BE **không** set `isAnomaly` trên cạnh REVIEWED. FE tự highlight nếu thiếu chiều ngược / `weight` thấp — không đổi % đóng góp.

---

## 7. `isAnomaly` (XAI)

| Chỗ | Khi nào `true` |
| --- | --- |
| TASK | `status === "DONE"` và tiêu chí CODE/TEST nhưng **0 commit** link. Task vẫn có điểm — chỉ cảnh báo evidence yếu. |
| COMMIT / IDENTITY | Author GitHub chưa map StudentProfile. |
| DOCUMENT/RESEARCH thiếu file/link | Không `isAnomaly` trên task vì thiếu classify; **không** có `CLASSIFIED_AS`. |
| Task DONE 0 commit nhưng không phải CODE/TEST | **Không** anomaly (scoring không đòi commit). |

Nhấp nháy đỏ: `data.isAnomaly === true`.

---

## 8. Gợi ý UI

1. Mở project → Graph 1 (không sprint) làm “bản đồ nhóm”.
2. Dropdown sprint = `GET /api/projects/{projectId}/sprints` → gọi lại Graph 1 kèm `sprintId`, hoặc Graph 3 / 5.
3. Click STUDENT → Graph 2 với UUID sau `student:`.
4. Tab “Attribution” → Graph 4 (cùng `sprintId` nếu đang filter).
5. Tab “Peer review” → Graph 5 (cần sprint).

Layout: `breadthfirst` / `cose` / `concentric` / `circle` là việc FE. Click node → **GET lại** với `focusNodeId` + `depth=1` (đừng layout full 800 node). Neighborhood dimming local: `cy.$id(id).neighborhood()` chỉ khi payload đã nhỏ.

Sau webhook / sync: SSE `GRAPH_CHANGED` rồi GET graph (`If-None-Match`). Không refetch graph trên mọi `TASKS_CHANGED`.

---

## 9. Lỗi thường gặp

| HTTP | `code` | Khi nào | UI |
| --- | --- | --- | --- |
| 401 | (unauthenticated) | Hết session | Về login |
| 403 | `INTEGRATION_FORBIDDEN` | Student không thuộc team | Ẩn canvas |
| 403 | `LECTURER_COURSE_FORBIDDEN` | GV sai course | Ẩn canvas |
| 403 | `ACCESS_DENIED` | ADMIN hoặc role khác | Không dùng graph cho admin |
| 404 | `ROSTER_STUDENT_NOT_FOUND` | `studentId` không phải member ACTIVE | Toast; đừng dùng `user.id` |
| 404 | `PROJECT_NOT_FOUND` | Sai `sprintId` (hoặc sprint đã xóa) | Bỏ filter sprint |
| 400 | `REQUEST_INVALID` | `focusNodeId` ngoài scope, enum type sai, `depth`/`maxNodes`/`cursor` invalid | Toast; đừng gửi cursor sau `GRAPH_CHANGED` |
| 5xx | — | Neo4j sai DB name (`neo4j` trên Aura) hoặc Aura down. Health **không** check Neo4j | Railway: xóa `NEO4J_DATABASE=neo4j` hoặc để trống |

CORS: origin FE phải nằm `SAGA_AUTH_FRONTEND_ORIGINS`. Local: `http://localhost:3000` + `credentials: "include"`.

---

## 10. Việc FE không làm

- Không tính % / story point tổng từ graph. Graph là **giải thích**, không phải SoT điểm.
- Không vẽ `IMPLEMENTS` ngược Commit → Task.
- Không bịa node Criterion từ `weightType` — BE đã trả 4 node trên Graph 2 và 3.
- Không nối `Commit → Student` tắt Identity.
- Không đợi node Pull Request.
- Không gọi 5 graph song song lúc mount nếu chưa cần.
- Không tải full graph rồi gom/ẩn trên Cytoscape — dùng `nodeTypes` / `focusNodeId` / `maxNodes`.
