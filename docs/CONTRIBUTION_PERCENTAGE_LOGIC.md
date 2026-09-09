# Logic tính % đóng góp (Contribution) — SAGA Backend

Tài liệu này mô tả **đúng theo code hiện tại** cách hệ thống tính `finalContributionPercentage` cho từng sinh viên trong team.

**V2 mapping (SAGA-BE-V2):** cùng công thức DEC-092. Path bỏ `/api/v1`. Session cookie `SAGA_SESSION`. Mode cũ `TEAM` = V2 `PROJECT_GROUP`. Override ghi thẳng `contribution_override` (không workflow APPROVED). DOCUMENT/RESEARCH evidence = `task_attachment` **hoặc** `task_web_link` (V9) **hoặc** `task_file` (V10). API: `GET /api/teams/{teamId}/contribution-evaluation`. Chi tiết FE: `docs/FRONTEND_API_INTEGRATION.md` §18.


**Cập nhật (DEC-088 — Course-default + optional exclusive Team override, supersedes DEC-087's
Course-only model):** Contribution weight config nay có đúng hai mode loại trừ nhau trên
`Course.contributionConfigMode`: **`COURSE`** (mọi Team thuộc Course dùng chung một bộ trọng số
của Course) hoặc **`TEAM`** (mọi Team hiện tại của Course bắt buộc có `ProjectGroupWeightConfig`
riêng hợp lệ — thiếu một Team là "chưa hoàn tất", không bao giờ fallback im lặng về Course).
`PUT /api/projects/{projectId}/group-weights` **đã được hồi sinh** (từng bị DEC-087 xóa). Criteria
universe đổi từ CODE/TEST/DOCUMENT/DESIGN sang **CODE/TEST/DOCUMENT/RESEARCH** — DESIGN không còn
là Contribution criterion active (vẫn là ProjectType catalog value độc lập). Evidence từng gán
DESIGN (task keyword + `DocumentType.DESIGN`) nay gộp thẳng vào `DOCUMENT` — xem section 3.1/3.2.

**Cập nhật (DEC-092 — slice tuyệt đối × P, cộng slice rồi nhân P cả dự án):** `% sprint` = `(slice × P_s) / Σ adjust sprint`. `slice = Σ SP cùng tiêu chí × trọng số` (không chia share trong tiêu chí, không phân bổ lại trọng số slice trống). `% cuối` = `(Σ slice × P cả dự án) / Σ adjust team`. Sprint nhiều việc nặng hơn. Sprint giữa kỳ chưa peer: `P_s = 1`. Task không gắn sprint **không tính điểm**. `peerReviewScore` vẫn là hệ số peer cả dự án (hiển thị). Chi tiết + ví dụ: `docs/CONTRIBUTION_CALCULATION_SPEC.md`.

**Cập nhật (DEC-091 — sprint-first contribution, superseded by DEC-092):** `% đóng góp` từng sprint rồi trung bình đều — **không còn**. DEC-092 cộng slice theo khối lượng rồi nhân P cả dự án.

**Cập nhật (DEC-090 — labels-only classification + attachment gate for DOCUMENT/RESEARCH):**
Task chỉ vào tiêu chí Contribution khi có đúng một reserved label
(`saga:code`/`saga:test`/`saga:document`/`saga:research`). Không còn fallback keyword/title/type.
Task không nhãn hoặc nhãn xung đột không vào criterion nào (sprint/`adjustedSprintScore` vẫn đếm
story point). DOCUMENT và RESEARCH **chỉ công nhận story point khi Task có ít nhất một Jira
attachment hoặc một link sinh viên nộp** — đó là điều kiện, không cộng thêm điểm. CODE/TEST không cần file/link.
Document SAGA không phải nguồn điểm. GitHub attachment vẫn chưa ingest.

**Cập nhật (DEC-089 — Task-is-sole-numeric-authority + reserved Contribution markers):** Task
classify TEST/RESEARCH qua exact reserved marker trên `Task.labels`. Khi evidence là commit liên
kết Task, **Task là numeric authority duy nhất** — commit không còn cộng điểm bổ sung. DEC-090
gỡ keyword fallback mà DEC-089 từng giữ.

---

## 1) Điểm vào (entry points)

### API chính để xem kết quả đóng góp team
- `GET /api/v1/teams/{teamId}/contribution-evaluation`
- Controller: `TeamContributionController#getContributionEvaluation`
- Service thực thi chính: `TeamContributionService#evaluate`
- Quyền đọc: ADMIN mọi Team; LECTURER chỉ Course mình phụ trách; STUDENT chỉ exact
  `RoleInTeam.LEADER` của chính Team. MEMBER/MENTOR/cross-Team Leader bị từ chối.

### API override % đóng góp theo từng sinh viên
- `POST /api/v1/teams/{teamId}/contribution-override`
- Controller: `TeamContributionController#requestContributionOverride`
- Service: `TeamContributionService#requestContributionOverride`

### API quản lý trọng số slice theo course

Official FE:

- `GET /api/v1/courses/{courseId}/contribution-slice-weights`
- `PUT /api/v1/courses/{courseId}/contribution-slice-weights` (Lecturer exact instructor; CSRF; no `lecturerId`)

Luồng gửi đơn / Admin duyệt trọng số đã gỡ. Lecturer sửa trực tiếp, không xin quyền Admin.

Service: `CourseContributionWeightService`. Direct PUT chỉ có hiệu lực khi Course đang ở `COURSE`
mode (xem section 5). Team-scope: `PUT /api/projects/{projectId}/group-weights`
(`ProjectGroupWeightConfigService`, ADMIN hoặc đúng LECTURER phụ trách Course — không mở cho
Student/Leader), `PUT /api/v1/courses/{courseId}/contribution-config-mode` (mode switch, atomic,
audit toàn bộ Team trước khi activate `TEAM`), `GET /api/v1/courses/{courseId}/contribution-team-weights`
(team-menu read).

---

## 2) Nguồn dữ liệu dùng để tính

Trong `TeamContributionService#evaluate(teamId)`:

1. Lấy `Team`, `Project`, danh sách `TeamMember`.
2. Nếu team chưa gắn project hoặc không có member -> trả `members = []`.
3. Lấy dữ liệu thô:
   - `Task` theo project.
   - `TaskAttachment` theo project (metadata Jira; DOCUMENT/RESEARCH cần ≥1 file để công nhận story point).
   - `PeerReview` theo danh sách reviewee thuộc team + project.
   - `PolicyOverrideRequest` đã `APPROVED` cho loại `TEAM_CONTRIBUTION_OVERRIDE` trong cùng class.

---

## 3) Cách tính điểm thô theo từng sinh viên

Hệ thống duy trì 4 cụm điểm/metrics per-student: `codeScore`, `testScore`, `documentScore`,
`researchScore`, cộng `adjustedSprintScore` (task score đã nhân hệ số retrospective/peer theo
sprint — **công thức numeric không đổi**, xem 3.3/3.4).

### 3.0 Labels-only classification (`TaskContributionClassifier`, DEC-090)

Mỗi Task DONE được route vào đúng MỘT trong bốn `ContributionCriterion` (CODE/TEST/DOCUMENT/RESEARCH).
Authority duy nhất là reserved label trên `Task.labels` (`ReservedContributionMarkerClassifier`):

- `saga:code` -> CODE, `saga:test` -> TEST, `saga:document` -> DOCUMENT, `saga:research` -> RESEARCH.
- Exact string match sau khi trim, case-sensitive, không substring/fuzzy/AI (`saga:test-extra`,
  `SAGA:TEST` đều KHÔNG match).
- **>1 marker xung đột** (ví dụ `saga:test` + `saga:research`) -> `AMBIGUOUS`: không vào criterion nào.
- **Không có reserved marker** (kể cả Task có label business như `backend`/`ui-ux`, hoặc title/type
  gợi ý): không vào criterion nào. **Không** fallback keyword/title/`TaskType`.

Dù Task không vào criterion, `adjustedSprintScore`/`taskContributionPercentage` (section 3.3/3.4)
vẫn cộng storyPoint như bình thường.

DOCUMENT/RESEARCH: story point (hoặc 1.0) **chỉ được cộng vào criterion khi Task có ≥1 Jira
attachment hoặc ≥1 link sinh viên nộp**. Số lượng file/link không làm tăng điểm. Không có cả hai → story point không vào
DOCUMENT/RESEARCH (sprint score vẫn đếm). CODE/TEST luôn công nhận story point, bỏ qua attachment/link.
Metadata attachment được upsert cùng Jira issue (`V38__add_task_attachment.sql`);
link nộp qua SAGA lưu `task_web_link` (`V39__add_task_web_link.sql`).
thiếu field `attachment` trên response Jira = danh sách rỗng (xóa snapshot cũ, không đụng web link). Không tải nội dung
file, không persist content URL. GitHub attachment không ingest.

### 3.1 Điểm từ commit — Task là numeric authority duy nhất (DEC-089)

**Không còn per-commit scoring loop.** Trước DEC-089, một commit liên kết Task (qua field
`commit.task`, chưa từng được production upsert path nào ghi — audit xác nhận field này chết trong
thực tế) sẽ CỘNG THÊM điểm vào slice của Task đó, tạo double-count tiềm ẩn nếu Task cũng DONE và
được tính riêng qua 3.3. Product decision: khi evidence có authoritative link tới một Task, **Task
là numeric scoring authority duy nhất** — commit chỉ là supporting/provenance evidence, không mint
điểm riêng, kể cả khi có 1 hay nhiều commit cùng liên kết một Task (`commitCountByStudent` vẫn đếm
đúng số commit cho mục đích evidence/warning, chỉ phần cộng điểm bị xóa).

### 3.2 Điểm DOCUMENT / RESEARCH

Không đọc bảng `Document`. DOCUMENT chỉ từ Task DONE `saga:document` có ≥1 Jira attachment.
RESEARCH chỉ từ Task DONE `saga:research` có ≥1 Jira attachment. Story point (hoặc 1.0) được công
nhận nguyên vẹn; số file không làm tăng điểm. Thiếu file thì không vào tiêu chí.

### 3.3 Điểm từ task DONE

Lọc task theo assignee là sinh viên và `status == DONE`.

Với từng task DONE:
- `taskWeight = storyPoint` nếu có, ngược lại `1.0` — **công thức numeric không đổi bởi DEC-089**.
- Cộng `taskWeight` vào đúng MỘT criterion theo `TaskContributionClassifier` khi story point được
  công nhận (DOCUMENT/RESEARCH cần ≥1 attachment, section 3.0); nếu không có marker / AMBIGUOUS /
  DOCUMENT-RESEARCH thiếu file thì không cộng vào criterion nào (nhưng vẫn cộng `taskWeight` vào
  `taskScoreBySprint` bên dưới).
- Gom `taskWeight` theo từng sprint để tính breakdown (luôn thực hiện, không điều kiện theo
  classification).

### 3.4 Hệ số retrospective theo sprint

Được tính khi build sprint breakdown:

- `retrospectiveMultiplier = studentSprintPeerScore / totalSprintPeerScore`
- Nếu tổng peer score của sprint `<= 0` thì multiplier = `1.0`

Sau đó:
- `adjustedTaskScore (per sprint) = taskScore * retrospectiveMultiplier`
- `adjustedSprintScore` của sinh viên = tổng `adjustedTaskScore` của mọi sprint có dữ liệu.

---

## 4) Peer review ảnh hưởng vào % đóng góp như thế nào

### 4.1 Nguồn `starRating` peer review

Trong `PeerReviewService#submit`:

- Nếu request gửi `criteriaRatings`:
  - Bắt buộc rate đủ rubric, không trùng.
  - `peerReview.starRating = tổng star của tất cả criteria`.
- Nếu không gửi `criteriaRatings`:
  - Bắt buộc có `starRating` tổng.

=> Luồng contribution chỉ dùng `peerReview.starRating` đã lưu.

### 4.2 Peer coefficient toàn project (hiển thị)

Trong `evaluate`:

- `peerScoreByStudent = tổng starRating của các review mà sinh viên là reviewee`
- `totalPeerScore = tổng peerScoreByStudent toàn team`
- `peerCoefficient(student)` (field `peerReviewScore`):
  - Nếu `totalPeerScore > 0`: `peerScoreByStudent / totalPeerScore`
  - Nếu `totalPeerScore == 0`: `1.0`

Hệ số này **không** nhân lần nữa vào `% cuối`. Peer từng sprint (`retrospectiveMultiplier` / `P_s`) mới là hệ số đi vào công thức contribution của sprint đó (section 6).

---

## 5) Slice weights (Code/Test/Document/Research)

### 5.1 Lấy trọng số

`sliceWeights = ContributionSliceWeightResolver.resolve(team)`

Authority (CONFIRMED_SOURCE, **mode-aware, fail-closed**): resolver đọc `team.course.contributionConfigMode`.

- `COURSE` mode: luôn `ContributionSliceWeights.fromCourse(team.course)`. Mọi Team thuộc cùng Course
  dùng chung đúng một bộ trọng số.
- `TEAM` mode: tra `ProjectGroupWeightConfig` theo đúng `projectId` của Team, xác nhận đúng Team sở
  hữu row đó, rồi `normalizeConfigured(codeWeight, testWeight, documentWeight, researchWeight)`.
  Thiếu override hoặc override thuộc Team khác -> ném `IntegrationException(TEAM_WEIGHT_CONFIG_INCOMPLETE)`,
  **không bao giờ** fallback về Course weights. Team mới tạo sau khi TEAM mode đã active cũng phải
  có override riêng — Contribution không tính được cho Team đó cho tới khi có override.

- Nếu course null hoặc weights null (chỉ áp dụng khi resolve thất bại trước khi biết mode) ->
  fallback mặc định 25/25/25/25 (equal quarters).
- `normalizeConfigured(...)` luôn chuẩn hóa để tổng = 100 (Course scale) hoặc 1.0 (Team/Project scale).

Lecturer `PUT /api/v1/courses/{courseId}/contribution-slice-weights` ghi bốn cột Course, chỉ có
hiệu lực khi Course đang ở `COURSE` mode. `PUT /api/projects/{projectId}/group-weights` ghi
`ProjectGroupWeightConfig`, có hiệu lực khi Course ở `TEAM` mode (ghi được bất kỳ lúc nào như
Không đổi nguồn trọng số. Công thức evaluate (DEC-092, section 6): cộng SP cùng tiêu chí rồi nhân trọng số đã cấu hình — **không** `normalizeForActiveSlices` trong sprint.

### 5.2 Trọng số slice trống không được phân bổ lại

`normalizeForActiveSlices` vẫn tồn tại trên `ContributionSliceWeights` nhưng **evaluate không gọi**. Slice không có Task được công nhận đóng góp `0` vào `slice`; trọng số các tiêu chí khác giữ nguyên (CODE 40% vẫn là 0.40 dù sprint không có TEST).

`TEST`/`RESEARCH` nhận điểm khi có Task DONE đúng marker (DEC-089/090). DOCUMENT/RESEARCH còn cần attachment Jira. GitHub attachment và commit-via-traceability vẫn chưa là nguồn điểm.

---

## 6) Công thức tính contribution (DEC-092)

Ký hiệu:
- `Wc, Wt, Wd, Wr`: trọng số Course/Team dạng tỷ lệ (`40% → 0.40`). Tổng 1.0. **Không** phân bổ lại khi một tiêu chí không có Task.
- `P_s`: `sao_i / tổng sao sprint`. Sprint chưa có peer → `P_s = 1`.
- `P`: `tổng sao_i mọi sprint / tổng sao team mọi sprint`. Cả dự án chưa peer → `P = 1`.

### 6.1 Từng sprint

Với mỗi sprint có ít nhất một Task DONE được công nhận tiêu chí:

```
slice_i = (Σ SP_code)×Wc + (Σ SP_test)×Wt + (Σ SP_doc)×Wd + (Σ SP_research)×Wr
adjust_i = slice_i × P_s(i)
%_sprint(i) = adjust_i / Σ adjust × 100
```

Cộng SP **cùng tiêu chí** rồi mới nhân trọng số một lần. Không `(điểm sv / tổng team)×100` rồi mix. Không nhân kép trọng số.

Sprint không có tiêu chí được công nhận bị **bỏ qua**. Task unlabeled / Document-Research thiếu file vẫn vào `taskScore` của breakdown; `adjustedTaskScore = taskScore × P_s`.

Task không gắn sprint **không tính điểm**.

`sprintBreakdowns[].contributionPercentage` là `%_sprint` của đúng sinh viên đó.

### 6.2 % đóng góp cuối dự án

```
Σslice_i   = slice_sprint1(i) + slice_sprint2(i) + …
adjust_i   = Σslice_i × P(i)
%_final(i) = adjust_i / Σ adjust × 100
```

Không trung bình đều bốn % sprint. Sprint nhiều slice nặng hơn. Override giảng viên áp sau `%_final`, rồi chuẩn hóa tổng team = 100.

Radar `code/test/document/researchContributionPercentage` vẫn là tỷ lệ **cả project** của từng slice.

### 6.3 Peer review cuối

`peerReviewScore` = `P` toàn project (section 4.2). `% cuối` **đã nhân** `P` rồi chuẩn hóa theo tổng adjust team — không nhân thêm lần nữa trên client.

Response evaluation cũng trả hệ số slice **trước** peer: `sliceScore` (Σ slice) và `sliceContributionPercentage` (`slice / Σ slice team × 100`). Cùng cặp field trên từng `sprintBreakdowns[]`. Override giảng viên không đụng hai field này.

---

## 7) Override và chuẩn hóa về tổng 100%

Hệ thống merge `baseAdjustedContribution` với override đã duyệt bằng `normalizeContributionsWithOverrides`.

### 7.1 Nếu tất cả member đều bị override

- Nếu tổng override <= 0:
  - chia đều 100.
- Ngược lại:
  - chuẩn hóa theo tỷ lệ override để tổng = 100.

### 7.2 Nếu chỉ override một phần member

1. Tính tổng override (`totalOverride`).
2. Nếu `totalOverride > 100`:
   - scale toàn bộ override theo tỷ lệ để tổng override = 100.
   - nhóm không override nhận 0.
3. Nếu `totalOverride <= 100`:
   - giữ nguyên giá trị override.
   - phần ngân sách còn lại `remainingBudget = 100 - totalOverride`.
   - phân bổ cho nhóm không override theo tỷ lệ `baseAdjustedContribution`.
   - nếu tổng base của nhóm này = 0 thì chia đều.

=> `finalContributionPercentage` luôn được normalize về tổng 100% toàn team.

---

## 8) Warning rules

Sau khi có `finalContributionPercentage`, hệ thống tạo warning:

1. `NO_PEER_REVIEW`
   - peerReviewCount = 0 và final >= 50
2. `LOW_PEER_REVIEW`
   - peerCoefficient <= 0.6 và final >= 40
3. `INSUFFICIENT_EVIDENCE`
   - evidenceCount <= 1 và final >= 60
4. `NO_EVIDENCE`
   - commit = 0, document = 0, peerReview = 0

`evidenceCount` tăng khi có dữ liệu ở các nhóm:
- commit
- task done
- document (bao gồm evidence từng gán DESIGN, nay gộp vào document)
- peer review

---

## 9) Quy tắc validation cho thay đổi slice weight theo course/team

Official Course direct update (`CourseContributionWeightService#updateCurrentWeights` / `validateDirectWeights`):

- Body `{codeWeight, testWeight, documentWeight, researchWeight}` — không `lecturerId`
- Bắt buộc đủ bốn field, mỗi field `>= 0`
- Tổng phải xấp xỉ `100` với tolerance `0.01`
- Actor = `SagaPrincipal.localProfileId`; LECTURER exact Course instructor
- Ghi trực tiếp Course `code_contribution_weight` / `test_contribution_weight` /
  `document_contribution_weight` / `research_contribution_weight`

Team/Project override (`ProjectGroupWeightConfigService#update`):

- Body `{groupId, codeWeight, testWeight, documentWeight, researchWeight, note?}` — 0..1 scale
- `groupId` phải đúng Team sở hữu `projectId`, ngược lại `GROUP_PROJECT_MISMATCH`
- Mỗi field `>= 0` và `<= 1`; tổng phải đúng bằng `1.0` (không tolerance)
- Actor: ADMIN hoặc đúng LECTURER phụ trách Course sở hữu Team — không mở cho Student/Leader

Mode switch (`CourseContributionWeightService#switchConfigMode`):

- Chuyển sang `TEAM` chỉ khi audit xác nhận **mọi** Team hiện tại của Course đã có
  `ProjectGroupWeightConfig` hợp lệ — thiếu một Team -> 409 `TEAM_MODE_CONFIGURATION_INCOMPLETE`,
  mode giữ nguyên `COURSE` (atomic, không có trạng thái partial)
- Chuyển về `COURSE` không xoá `ProjectGroupWeightConfig` của Team nào — giữ historical/inactive

Legacy request path (`validateRequestedWeights`) **không mở rộng với `testWeight`/`researchWeight`**
(deprecated, không còn consumer FE mới) — vẫn chỉ nhận `{codeWeight, documentWeight}` cộng giá trị
`design` cũ (nếu có). Nếu admin duyệt request: `code`/`document` ghi trực tiếp, giá trị `design` cũ
ghi verbatim vào cột inactive `design_contribution_weight` (giữ lại, không discard), `test` ép về 0
— an toàn vì `ContributionSliceWeights.fromCourse` luôn renormalize theo tổng active slice.

---

## 10) Các lưu ý kỹ thuật quan trọng

1. `taskContributionPercentage` trong response là tỉ lệ từ `adjustedSprintScore` (task theo sprint đã nhân retrospective multiplier), không phải trực tiếp từ raw slice. `finalContributionPercentage` là `(Σ slice × P) / Σ adjust` (DEC-092), khác `taskContributionPercentage`.
2. `peerReviewScore` field hiện trả `peerCoefficient` toàn project (0..1). `% cuối` đã dùng đúng hệ số này rồi chuẩn hóa team; client không nhân thêm. `sliceContributionPercentage` là % cùng công thức nhưng **chưa** nhân P.
3. Nếu team không có project hoặc rỗng member thì API trả danh sách members rỗng.
4. Override team-level hiện được lưu dạng `APPROVED` ngay khi tạo request trong `requestContributionOverride`.
5. Tất cả phép tính cuối cùng vẫn đưa về budget 100%.
6. `sprintBreakdowns[].sliceContributionPercentage` là % slice **trước** peer trong sprint; `contributionPercentage` là % đã mix + peer **trong sprint đó**.

---

## 11) Mapping file mã nguồn (để audit nhanh)

- Main evaluate flow:
  `src/main/java/com/saga/be/service/TeamContributionService.java`
- Slice weight normalization:
  `src/main/java/com/saga/be/service/contribution/ContributionSliceWeights.java`
- Slice weight source resolution (mode-aware, fail-closed COURSE/TEAM):
  `src/main/java/com/saga/be/service/contribution/ContributionSliceWeightResolver.java`
- Sprint mix (slice × P, rồi Σ slice × P cả dự án):
  `src/main/java/com/saga/be/service/contribution/SprintFirstContributionMixer.java`
- Course slice-weight GET/PUT, mode switch, team-menu read, legacy request/approval:
  `src/main/java/com/saga/be/service/CourseContributionWeightService.java`
- Team/Project weight override:
  `src/main/java/com/saga/be/service/ProjectGroupWeightConfigService.java`
- Peer review submit (cách tạo starRating):
  `src/main/java/com/saga/be/service/PeerReviewService.java`
- Endpoints:
  `src/main/java/com/saga/be/controller/TeamContributionController.java`
  `src/main/java/com/saga/be/controller/CourseContributionWeightController.java`
  `src/main/java/com/saga/be/controller/ProjectGroupWeightConfigController.java`

