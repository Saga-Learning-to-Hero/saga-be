# Trao đổi Kỹ thuật & Đề xuất Đồng bộ Đồ thị SAGA (BE & FE)
> **Tài liệu tham chiếu:** [`docs/SAGA_GRAPHS_TO_DRAW.md`](./SAGA_GRAPHS_TO_DRAW.md)  
> **Người gửi:** Phân hệ Frontend (FE)  
> **Người nhận:** Phân hệ Backend (BE) / Kỹ sư cơ sở dữ liệu Neo4j  
> **Mục đích:** Thống nhất mô hình dữ liệu đồ thị Neo4j, chuẩn hóa câu truy vấn Cypher và đặc tả 5 API Endpoint trả về định dạng Cytoscape.js để phục vụ trực quan hóa và báo cáo bảo vệ tốt nghiệp.

---

## 📌 I. ĐÁNH GIÁ CHUNG VỀ TÀI LIỆU `SAGA_GRAPHS_TO_DRAW.md`

Tài liệu `SAGA_GRAPHS_TO_DRAW.md` có tính định hướng và thực tiễn rất cao:
1. **Phân rã phạm vi hợp lý**: Giới hạn đồ thị theo từng đối tượng hẹp (1 Project / 1 Sprint / 1 Sinh viên) giúp đồ thị trực quan, không bị quá tải điểm (lag/vỡ layout trên canvas WebGL).
2. **Khắc họa trúng trọng tâm đề tài**:
   - **Graph 1 (Tổng quan)**: Chứng minh mối quan hệ giữa Ngữ cảnh học thuật (Course, Team, Project, Sprint) và Bằng chứng kỹ thuật (Task, Commit).
   - **Graph 2 (Contribution Path)**: Giải thích nguồn gốc điểm số (Traceability / XAI) cho từng sinh viên.
   - **Graph 3 (Sprint Activity)**: Minh chứng cho tính chất "Continuous Assessment" (Đánh giá liên tục qua các giai đoạn).
   - **Graph 4 (Attribution)**: Minh chứng tính toàn vẹn dữ liệu, chống gian lận gán bừa commit.
   - **Graph 5 (Peer Review)**: Giải thích hệ số đồng đẳng $P$ trong công thức đóng góp Slicing Pie.
3. **Nguyên tắc tính điểm đúng đắn**: Task Jira là **nguồn điểm**, Commit/PR là **bằng chứng đối soát** (không coi commit là điểm để tránh tình trạng sinh viên spam commit rác).

---

## ❓ II. 5 VẤN ĐỀ KỸ THUẬT CẦN BE XÁC NHẬN & ĐỒNG BỘ

Để việc truy vấn từ cơ sở dữ liệu đồ thị Neo4j lên Frontend diễn ra liền mạch, FE đề xuất thảo luận 5 nội dung kỹ thuật sau:

### 1. Chuẩn hóa Tên nhãn Quan hệ (Relationship Types) trong Neo4j
Trong tài liệu `SAGA_GRAPHS_TO_DRAW.md` có một số tên quan hệ đang hơi khác với quy chuẩn Neo4j thông dụng của đề tài:

| Quan hệ trong tài liệu | Đề xuất chuẩn hóa Neo4j | Chiều mũi tên | Ghi chú & Rationale |
| :--- | :--- | :--- | :--- |
| `ASSIGNED` | `[:ASSIGNED_TO]` | `(Student) ➔ (Task)` | Thống nhất với ngữ nghĩa phân công công việc Jira. |
| `EVIDENCED_BY` | `[:IMPLEMENTS]` | `(Commit) ➔ (Task)` | Trong kỹ nghệ phần mềm (MSR), Commit sinh ra để hiện thực hóa Task (`Commit -> Task`). FE hoàn toàn có thể hiển thị nhãn `EVIDENCED_BY` trên UI cho người xem dễ hiểu. |
| `AUTHORED_BY` | `[:AUTHORED]` | `(Identity) ➔ (Commit)` hoặc `(Student) ➔ (Commit)` | Chuẩn ngữ nghĩa tác giả mã nguồn Git. |
| `CLASSIFIED_AS` | `[:CLASSIFIED_AS]` | `(Task) ➔ (Criterion)` | Giữ nguyên như tài liệu. |
| `REVIEWS` | `[:REVIEWED]` | `(Student) ➔ (Student)` | Đi kèm thuộc tính `{ stars: 5, sprintId: "..." }`. |

👉 **Câu hỏi cho BE**: BE hiện đang đặt tên các nhãn quan hệ này trong Cypher thế nào để FE cấu hình Cytoscape CSS styles cho khớp 100%?

---

### 2. Mô hình hóa 4 Node Tiêu chí (`Criterion`: CODE, TEST, DOCUMENT, RESEARCH)
Tài liệu quy định có 4 Node cố định đại diện cho 4 tiêu chí đánh giá, và Task chỉ được nối `CLASSIFIED_AS` tới Criterion khi có nhãn `saga:*` hợp lệ.

👉 **Đề xuất kỹ thuật cho BE**:
- **Phương án A (Khuyến nghị)**: Khởi tạo sẵn 4 Node gốc trong Neo4j:
  ```cypher
  MERGE (:Criterion { id: "crit_code", name: "CODE", label: "Lập trình" })
  MERGE (:Criterion { id: "crit_test", name: "TEST", label: "Kiểm thử" })
  MERGE (:Criterion { id: "crit_doc", name: "DOCUMENT", label: "Tài liệu" })
  MERGE (:Criterion { id: "crit_research", name: "RESEARCH", label: "Nghiên cứu" })
  ```
  Khi đồng bộ Jira Issue, nếu Task có nhãn `saga:code` và đủ bằng chứng, BE chỉ cần tạo cạnh:
  ```cypher
  MATCH (t:Task { key: $taskKey }), (c:Criterion { name: "CODE" })
  MERGE (t)-[:CLASSIFIED_AS]->(c)
  ```
- **Phương án B**: Nếu trong Database BE chỉ lưu thuộc tính `weightType` trên node Task, thì trong DTO API trả về cho FE, BE cần tự bổ sung các Node và Cạnh giả lập của Criterion để FE vẽ được Graph 2 và Graph 3.

---

### 3. Mô hình hóa Thực thể `Identity` trong Graph 4 (Attribution)
Tài liệu định nghĩa: `(Commit)-[:AUTHORED_BY]->(Identity)-[:MAPS_TO]->(Student)`.
- **Mục đích**: Giải quyết trường hợp sinh viên dùng email GitHub cá nhân lạ (không phải email trường), giúp hiển thị được liên kết bị đứt đoạn và phát hiện commit chưa được gán người làm.

👉 **Câu hỏi cho BE**:
- BE hiện đang lưu Node `(:Identity { email, username, provider: "GITHUB" })` trung gian trong Neo4j không, hay đang ánh xạ trực tiếp `Commit -> Student`?
- Nếu BE chưa có Node `Identity`, khi gặp commit không khớp sinh viên nào, BE đang xử lý ra sao (bỏ qua hay vẫn lưu commit mồ côi)?

---

### 4. Tình trạng Đồng bộ Bằng chứng (`Commit` vs `PullRequest`)
Tài liệu có đề cập `PullRequest` bên cạnh `Commit`.

👉 **Câu hỏi cho BE**:
- Webhook GitHub hiện tại của hệ thống đã bắt và lưu các sự kiện Pull Request (`(:PullRequest)`) vào Neo4j chưa?
- **Khuyến nghị từ FE**: Nếu BE chưa hoàn thiện việc bóc tách dữ liệu PR từ GitHub API, ở giai đoạn này nên **thống nhất chỉ dùng `(:Commit)` làm Evidence** để tránh việc đồ thị trên slide có PR nhưng khi bấm demo thực tế lại không có dữ liệu.

---

### 5. Đặc tả 5 API Endpoint trả dữ liệu JSON Cytoscape cho FE
Thay vì để FE phải gọi nhiều API rồi tự tính toán nối đỉnh/cạnh (rất dễ gây sai lệch logic đồ thị), **đề xuất BE cung cấp 5 endpoint chuyên biệt trả về DTO chuẩn Cytoscape.js**:

#### Cấu trúc Payload DTO chuẩn:
```typescript
interface CytoscapeGraphResponse {
  nodes: Array<{
    data: {
      id: string;
      label: string;
      subLabel?: string;
      type: "STUDENT" | "TASK" | "COMMIT" | "SPRINT" | "PROJECT" | "CRITERION" | "IDENTITY";
      status?: string;       // Cho Task: TODO, IN_PROGRESS, DONE
      weightType?: string;   // CODE, TEST, DOC, RESEARCH
      isAnomaly?: boolean;   // true nếu phát hiện MSR Anomaly (Task DONE nhưng 0 commit)
      avatar?: string;       // Cho Student
      [key: string]: any;
    }
  }>;
  edges: Array<{
    data: {
      id: string;
      source: string;
      target: string;
      label: string;         // ASSIGNED_TO, IMPLEMENTS, CLASSIFIED_AS, REVIEWS...
      weight?: number;       // Cho Peer review (stars)
      isAnomaly?: boolean;
    }
  }>;
}
```

#### Danh sách 5 Endpoints đề xuất:
1. **Graph 1 — Student Activity Graph (Toàn nhóm)**:
   - `GET /api/projects/{projectId}/graph/overview`
   - *Phạm vi:* Team, Project, các Sprint, các Task và các Commit liên kết.
2. **Graph 2 — Contribution Path (Cá nhân sinh viên)**:
   - `GET /api/projects/{projectId}/students/{studentId}/graph/contribution?sprintId={sprintId}`
   - *Phạm vi:* Chỉ các Task của sinh viên đó, nối sang Criterion tương ứng và các Commit bằng chứng.
3. **Graph 3 — Sprint Activity (Theo từng Sprint)**:
   - `GET /api/projects/{projectId}/sprints/{sprintId}/graph/activity`
   - *Phạm vi:* Lát cắt hoạt động trong 1 Sprint cụ thể của nhóm.
4. **Graph 4 — Attribution / Identity (Kiểm tra gán định danh)**:
   - `GET /api/projects/{projectId}/graph/attribution`
   - *Phạm vi:* Danh sách Commit, Identity và Student (nổi bật các commit chưa map thành công).
5. **Graph 5 — Peer Review (Mạng lưới đánh giá đồng đẳng)**:
   - `GET /api/projects/{projectId}/sprints/{sprintId}/graph/peer-review`
   - *Phạm vi:* Đồ thị mạng lưới giữa các sinh viên trong nhóm, nhãn cạnh là số sao đánh giá chéo.

---

## 🚀 III. KẾ HOẠCH PHỐI HỢP TIẾP THEO

1. **BE xem xét và phản hồi**:
   - Khả năng cung cấp các endpoint theo DTO chuẩn trên.
   - Trạng thái thực tế của dữ liệu Node `Identity`, `Criterion` và `PullRequest` trong Neo4j.
2. **FE cam kết**:
   - Ngay khi BE chốt định dạng DTO hoặc dựng xong mock endpoint, FE sẽ tích hợp ngay vào component Cytoscape Canvas đã hoàn thiện (`src/features/graph/components/cytoscape-graph-canvas.tsx`).
   - Đảm bảo hỗ trợ đầy đủ: Highlight lân cận (Neighborhood Dimming), Đổi 4 thuật toán bố cục (`breadthfirst`, `cose`, `concentric`, `circle`), và Cảnh báo bất thường XAI nhấp nháy đỏ theo đúng chuẩn của Hội đồng.

---

## IV. Phản hồi Backend (đã chốt)

Ngôn ngữ đồ thị khóa tại [`docs/SAGA_GRAPHS_TO_DRAW.md`](./SAGA_GRAPHS_TO_DRAW.md) (nếu còn) / mục dưới. Graph API **đã ship** (`ProjectGraphController`). GET rebuild projection Neo4j từ MySQL rồi trả Cytoscape JSON.

### 1. Tên cạnh — trả lời câu hỏi Cypher

Hiện Cypher **đang chạy** khi FE gọi 5 GET graph (rebuild + query Neo4j). Tên cạnh Cytoscape CSS:

| Cạnh chốt | Chiều | FE đề xuất | Quyết định |
| --- | --- | --- | --- |
| `ASSIGNED_TO` | `(Student) → (Task)` | `ASSIGNED_TO` | **Nhận.** |
| `EVIDENCED_BY` | `(Task) → (Commit)` | `IMPLEMENTS` Commit → Task | **Giữ `EVIDENCED_BY`.** Task là nguồn điểm; commit là bằng chứng. Đảo mũi tên dễ hiểu nhầm commit mint điểm. FE gắn nhãn UI `EVIDENCED_BY`. |
| `AUTHORED_BY` | `(Commit) → (Identity)` | `AUTHORED` Identity → Commit | **Giữ `AUTHORED_BY`.** Graph 4 đọc trái → phải: commit thuộc identity nào, identity đã map student chưa. |
| `CLASSIFIED_AS` | `(Task) → (Criterion)` | giữ | **Nhận.** |
| `REVIEWED` | `(Student) → (Student)` `{stars, sprintId}` | `REVIEWED` | **Nhận.** |
| `MEMBER_OF` | `(Student) → (Team)` `{role}` | (không đổi) | Giữ. |
| `OWNS` | `(Team) → (Project)` | (không đổi) | Giữ. |
| `HAS_SPRINT` | `(Project) → (Sprint)` | (không đổi) | Giữ. |
| `CONTAINS` | `(Sprint) → (Task)` | (không đổi) | Giữ. |
| `MAPS_TO` | `(Identity) → (Student)` | (không đổi) | Giữ. |

`label` trên edge DTO = đúng tên cạnh chốt ở cột trái. FE style CSS theo các string này.

### 2. Criterion — Phương án A

**Chốt Phương án A.** Bốn node cố định, không nhét `weightType` rồi để FE tự bịa node.

- `id`: `crit_code` / `crit_test` / `crit_document` / `crit_research`
- `name`: `CODE` / `TEST` / `DOCUMENT` / `RESEARCH` — **không** viết tắt `DOC`
- Task chỉ có cạnh `CLASSIFIED_AS` khi đúng một nhãn `saga:*` và (nếu DOCUMENT/RESEARCH) đã có file hoặc link nộp

Khi GET graph, BE MERGE 4 node Criterion trên Neo4j rồi emit đúng node + cạnh `CLASSIFIED_AS` trong JSON.

### 3. Identity — trạng thái thật

- Neo4j **đã** có node `(:Identity)` khi GET graph (projection từ commit + `identity_map`/`authorStudent`).
- Commit **không** map thẳng bắt buộc sang Student. Projection GitHub giữ commit dù không khớp ai: `authorStudentId = null`, vẫn có `authorExternalId` / login.
- Map người qua identity đã link (GitHub account id hoặc username). Khớp thì gán student; không khớp thì commit **vẫn lưu** (commit mồ côi — đúng Graph 4).

Khi làm graph: **bắt buộc** node `Identity`. Không rút thành `Commit → Student`. Commit chưa map: `Commit -AUTHORED_BY-> Identity` và **không** có `MAPS_TO`. Đó là anomaly Graph 4 (`isAnomaly: true` trên Identity hoặc cạnh thiếu map).

### 4. Commit vs PullRequest — nhận khuyến nghị FE

Webhook GitHub hiện **chỉ xử lý `push`** → upsert Commit + link Task qua message/key. Bảng PullRequest tồn tại nhưng **không có ingest**.

**Demo / Graph 1–4: evidence chỉ `COMMIT`.** Không vẽ `PullRequest` cho đến khi webhook PR ship. `type` DTO không gồm `PULL_REQUEST` ở phase này.

### 5. Năm endpoint — nhận contract, chỉnh DTO

Đồng ý 5 path FE đề xuất. BE sẽ trả **một** `CytoscapeGraphResponse` / request, không để FE tự nối đỉnh.

Chỉnh so với bản FE:

**`type` node** (Graph 1 cần Team):

`STUDENT | TEAM | PROJECT | SPRINT | TASK | COMMIT | CRITERION | IDENTITY`

**`weightType`:** `CODE | TEST | DOCUMENT | RESEARCH | null` — không dùng `DOC`.

**`isAnomaly` — không dùng “Task DONE mà 0 commit” cho mọi loại.** Scoring không đòi commit. Chốt tín hiệu:

| `isAnomaly` | Khi nào |
| --- | --- |
| Task CODE/TEST DONE, 0 commit link | Cảnh báo evidence yếu (vẫn có điểm Task) |
| Task DOCUMENT/RESEARCH không đủ file/link | Không có cạnh `CLASSIFIED_AS` |
| Commit/Identity không `MAPS_TO` Student | Graph 4 |
| Peer `stars` thiếu / không đối xứng | Graph 5 (tuỳ FE highlight) |

**Phạm vi endpoint**

| Graph | Path | Node có mặt | Cạnh có mặt |
| --- | --- | --- | --- |
| 1 Overview | `GET /api/projects/{projectId}/graph/overview?sprintId=` | STUDENT, TEAM, PROJECT, SPRINT, TASK, COMMIT | MEMBER_OF, OWNS, HAS_SPRINT, CONTAINS, ASSIGNED_TO, EVIDENCED_BY |
| 2 Contribution | `GET /api/projects/{projectId}/students/{studentId}/graph/contribution?sprintId=` | STUDENT, TASK, CRITERION, COMMIT | ASSIGNED_TO, CLASSIFIED_AS, EVIDENCED_BY |
| 3 Sprint | `GET /api/projects/{projectId}/sprints/{sprintId}/graph/activity` | STUDENT, SPRINT, TASK, CRITERION, COMMIT | CONTAINS, ASSIGNED_TO, CLASSIFIED_AS, EVIDENCED_BY |
| 4 Attribution | `GET /api/projects/{projectId}/graph/attribution?sprintId=` | COMMIT, IDENTITY, STUDENT, TASK (nếu đã link) | AUTHORED_BY, MAPS_TO, EVIDENCED_BY, ASSIGNED_TO |
| 5 Peer | `GET /api/projects/{projectId}/sprints/{sprintId}/graph/peer-review` | STUDENT | REVIEWED `{weight: stars}` |

`sprintId` trên Graph 1, 2, 4 là **optional**. Bỏ query = cả project. Có `sprintId` = chỉ sprint đó (Graph 1 không gồm task backlog; Graph 4 chỉ commit gắn task trong sprint). Graph 3 và 5 luôn theo sprint trên path.

Graph 1 **không** trả Criterion / Identity / REVIEWED. Graph 5 **chỉ** Student + REVIEWED.

Quyền đọc: cùng `ProjectDataAuthorization.requireReader` với Task/Commit list (ACTIVE member của team, lecturer phụ trách course). ADMIN bị deny giống các projection read khác.

Payload: `{ nodes: [{ data: { id, label, type, ... } }], edges: [{ data: { id, source, target, label, weight? } }] }` — Cytoscape.js elements.

### Việc FE làm với API đã ship

Cytoscape CSS lock theo tên cạnh cột “Cạnh chốt”. Gọi 5 GET trên. Mỗi GET rebuild graph Neo4j của project rồi đọc lại.
