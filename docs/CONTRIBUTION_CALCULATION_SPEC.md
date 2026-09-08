# Công thức tính % đóng góp (spec đã chốt)

Tài liệu này mô tả **công thức đang chạy** (`SprintFirstContributionMixer`). Chỉ nói cách tính. Không mô tả API.

**V2:** cùng mixer trong `com.saga.be.service.contribution`. Engine live-evaluate, chưa persist `assessment_run`. Evidence DOCUMENT/RESEARCH trên V2 là `task_attachment` **hoặc** `task_web_link` (Flyway V9) **hoặc** `task_file` (Flyway V10).


Bốn tiêu chí: **CODE / TEST / DOCUMENT / RESEARCH**. Trọng số bốn tiêu chí **tổng 100**.

Commit đặt tên để gắn Task, AI duyệt commit rồi mới được Done: đó là **điều kiện có bằng chứng**, không cộng điểm thêm. Điểm chỉ lấy từ Task DONE.

---

## 0) Bảng tổng hợp toàn bộ công thức

### 0.1 Điểm được công nhận của một Task

```text
taskWeight(task) = storyPoint(task) nếu storyPoint khác null
                   1.0              nếu storyPoint = null
```

Một Task chỉ có `taskWeight` được đưa vào tiêu chí khi:

```text
status = DONE
AND có đúng một reserved label
AND có sprint
AND (criterion = CODE hoặc TEST
     OR (criterion = DOCUMENT hoặc RESEARCH
         AND có ít nhất một attachment hoặc web link hoặc file SAGA))
```

Task không đạt điều kiện trên có điểm tiêu chí bằng `0`. Task không gắn sprint
không được tính vào đóng góp.

### 0.2 Tổng Story Point theo tiêu chí của một thành viên trong một sprint

```text
codeSP(i, s)     = Σ taskWeight của Task saga:code của i trong sprint s
testSP(i, s)     = Σ taskWeight của Task saga:test của i trong sprint s
documentSP(i, s) = Σ taskWeight của Task saga:document đủ evidence của i trong s
researchSP(i, s) = Σ taskWeight của Task saga:research đủ evidence của i trong s
```

Nếu không có Task hợp lệ thuộc tiêu chí thì tổng tương ứng bằng `0`.

### 0.3 Điểm slice của một thành viên trong một sprint

Trọng số dùng dạng tỷ lệ: `40% = 0.40`.

```text
slice(i, s) =
      codeSP(i, s)     × Wcode
    + testSP(i, s)     × Wtest
    + documentSP(i, s) × Wdocument
    + researchSP(i, s) × Wresearch
```

```text
Wcode + Wtest + Wdocument + Wresearch = 1.0
```

Không phân bổ lại trọng số của tiêu chí không có Task và không nhân trọng số
hai lần.

### 0.4 Hệ số peer review theo sprint

```text
P_s(i) = peerStars(i, s) / Σ peerStars(all members, s)
```

Nếu sprint chưa có peer review hoặc mẫu số bằng `0`:

```text
P_s(i) = 1.0
```

### 0.5 Điểm đã điều chỉnh và % đóng góp trong sprint

```text
adjust_sprint(i, s) = slice(i, s) × P_s(i)
totalAdjust(s)      = Σ adjust_sprint(all members, s)
```

```text
sprintContribution%(i, s) =
    adjust_sprint(i, s) / totalAdjust(s) × 100
```

Nếu `totalAdjust(s) = 0` thì sprint không tạo ra phần trăm đóng góp.

### 0.6 Tổng điểm slice và hệ số peer của toàn dự án

```text
totalSlice(i) = Σ slice(i, s) qua tất cả sprint
```

```text
P(i) = Σ peerStars(i, all sprints)
       / Σ peerStars(all members, all sprints)
```

Nếu toàn dự án chưa có peer review hoặc mẫu số bằng `0`:

```text
P(i) = 1.0
```

### 0.7 % đóng góp cuối dự án trước override

```text
adjust_final(i) = totalSlice(i) × P(i)
totalAdjustFinal = Σ adjust_final(all members)
```

```text
finalContribution%(i) =
    adjust_final(i) / totalAdjustFinal × 100
```

Đây là công thức cuối dự án; không lấy trung bình các phần trăm sprint.

### 0.8 Tỷ lệ đóng góp theo từng tiêu chí trong toàn dự án

```text
criterionPercentage(i, c) =
    totalCriterionScore(i, c)
    / Σ totalCriterionScore(all members, c) × 100
```

Trong đó:

```text
totalCriterionScore(i, CODE)     = Σ codeSP(i, s)     × Wcode
totalCriterionScore(i, TEST)     = Σ testSP(i, s)     × Wtest
totalCriterionScore(i, DOCUMENT) = Σ documentSP(i, s) × Wdocument
totalCriterionScore(i, RESEARCH) = Σ researchSP(i, s) × Wresearch
```

Nếu tổng điểm của tiêu chí `c` bằng `0` thì `criterionPercentage(i, c) = 0`.

### 0.9 Override và chuẩn hóa về tổng 100%

Gọi `O` là tập thành viên có override đã được duyệt.

```text
totalOverride = Σ override(i), i thuộc O
```

Nếu `totalOverride <= 100`:

```text
final(i) = override(i)                         nếu i thuộc O
remainingBudget = 100 - totalOverride
```

Nếu `totalOverride > 100`, chuẩn hóa các override:

```text
final(i) = override(i) / totalOverride × 100   nếu i thuộc O
remainingBudget = 0
```

Với thành viên không có override:

```text
totalBase = Σ adjust_final(i), i không thuộc O
final(i) = adjust_final(i) / totalBase × remainingBudget
```

Nếu `totalBase = 0`, phần final của thành viên không override bằng `0`.
Sau bước này:

```text
Σ final(i) = 100%
```

### 0.10 Điểm peer review hiển thị trong response

```text
peerReviewScore(i) = P(i)
```

`finalContribution%` đã bao gồm `P(i)` và đã được chuẩn hóa; client không được
nhân `peerReviewScore` thêm một lần nữa.

---

## 1) Task nào được tính

Một Task chỉ vào công thức khi **đủ hết** các điều kiện:

- `status = DONE`
- đã gắn **sprint** (task backlog / chưa vào sprint = 0 điểm)
- có **đúng một** nhãn: `saga:code` / `saga:test` / `saga:document` / `saga:research`
- DOCUMENT và RESEARCH: Task phải có **ít nhất một file đính kèm hoặc một link** (sinh viên tải/nộp qua SAGA hoặc gắn file trực tiếp trên Jira; SAGA chỉ lưu metadata file và URL). Số file/link không làm tăng điểm. Thiếu cả hai = không công nhận SP.
- CODE và TEST: không cần file đính kèm.

Không tính:

- chưa Done
- chưa gắn sprint
- không nhãn reserved, hoặc gắn hai nhãn reserved cùng lúc
- DOCUMENT/RESEARCH không có file và không có link
- commit (dù đã link Task)

`storyPoint` thiếu thì lấy **1**.

---

## 2) Hệ số peer `P`

`P = (tổng sao cá nhân được đánh giá) / (tổng sao cả team)`

- **Từng sprint:** chỉ sao của sprint đó. Sprint giữa kỳ chưa có peer review → `P_s = 1` cho mọi thành viên.
- **Cuối dự án:** tổng sao mọi sprint / tổng sao team mọi sprint. Cả dự án chưa ai review → `P = 1`.

Vì `P` là tỷ lệ (0.1, 0.4, …), luôn chia cho tổng nhóm ở bước ra %. Không dừng ở `slice × P`.

---

## 3) Tính từng sprint

### 3.1 Cộng SP theo tiêu chí, rồi mới nhân trọng số

Với mỗi thành viên, trong **một sprint**:

1. Liệt kê từng Task được công nhận.
2. **Cộng Story Point cùng tiêu chí** (cùng nhãn).
3. **Nhân trọng số của đúng tiêu chí đó** (một lần).
4. Cộng bốn kết quả → `slice` của người đó trong sprint.

```
codeSP    = tổng SP các task saga:code được công nhận
testSP    = tổng SP các task saga:test được công nhận
docSP     = tổng SP các task saga:document được công nhận (có file hoặc link)
researchSP = tổng SP các task saga:research được công nhận (có file hoặc link)

slice = codeSP × Wc + testSP × Wt + docSP × Wd + researchSP × Wr
```

`Wc, Wt, Wd, Wr` là trọng số course/team, tổng 100. Trong công thức dùng dạng tỷ lệ `40% → 0.40`.

Không làm `(tổng SP mọi loại) × một hệ số`.  
Không nhân trọng số từng task rồi làm thêm một lần nữa (không nhân kép).

Hai task code 3 SP và 2 SP:

```
codeSP = 3 + 2 = 5
phần code = 5 × 0.40 = 2.00
```

cùng kết quả với `3×0.40 + 2×0.40`, vì cùng một trọng số CODE.

### 3.2 Adjust và % sprint

```
P_s(i)      = sao_i / tổng sao team trong sprint   (chưa có peer → 1)
adjust_i    = slice_i × P_s(i)
tổng adjust = adjust_1 + adjust_2 + … + adjust_n

%_sprint(i) = adjust_i / tổng adjust × 100
```

`%_sprint` là % đóng góp **của sprint đó** (đã gồm peer nếu sprint đã review).

---

## 4) % đóng góp cuối dự án

Peer **có vào % cuối**.

1. Cộng `slice` mọi sprint của từng người (**chưa nhân peer**).
2. Nhân **P cả dự án** (`tổng sao cá nhân / tổng sao team`).
3. Chia cho tổng adjust cả nhóm, nhân 100.

```
Σslice_i     = slice_sprint1(i) + slice_sprint2(i) + …
P(i)         = tổng sao_i mọi sprint / tổng sao team mọi sprint
adjust_i     = Σslice_i × P(i)
tổng adjust  = Σ adjust mọi thành viên

%_final(i)   = adjust_i / tổng adjust × 100
```

Không trung bình đều bốn % sprint. Sprint nhiều slice nặng hơn sprint ít việc.

Không cộng bốn “tổng adjust sprint” để ra cuối dự án. Tính lại từ **Σ slice × P cả team**.

Giảng viên ghi đè % (nếu có) áp **sau** `%_final`, rồi chuẩn hóa tổng team = 100.

---

## 5) Ví dụ chi tiết — 4 thành viên, 4 sprint

Team: **An, Bình, Chi, Dũng**  
Trọng số: **CODE 40% · TEST 10% · DOCUMENT 15% · RESEARCH 35%**  
→ `Wc=0.40`, `Wt=0.10`, `Wd=0.15`, `Wr=0.35`

Peer mỗi sprint (sao nhận được): An 4, Bình 3, Chi 2, Dũng 1. Tổng 10 sao/sprint.

```
P_s(An)   = 4/10 = 0.40
P_s(Bình) = 3/10 = 0.30
P_s(Chi)  = 2/10 = 0.20
P_s(Dũng) = 1/10 = 0.10
```

Cả dự án: An 16, Bình 12, Chi 8, Dũng 4 trên 40 sao → `P` cuối giống từng sprint.

Mọi Task dưới đây đều DONE và đã gắn đúng sprint ghi ở tiêu đề.

---

### Sprint 1

| Thành viên | Task | Nhãn | SP | File đính kèm | SP công nhận |
|---|---|---|---|---|---|
| An | Login API | `saga:code` | 3 | không cần | 3 CODE |
| An | Checkout API | `saga:code` | 2 | không cần | 2 CODE |
| An | Viết unit test payment | `saga:test` | 3 | không cần | 3 TEST |
| An | Tài liệu API login | `saga:document` | 2 | có | 2 DOCUMENT |
| Bình | Hướng dẫn cài đặt | `saga:document` | 2 | có | 2 DOCUMENT |
| Bình | User guide sprint 1 | `saga:document` | 2 | có | 2 DOCUMENT |
| Bình | Khảo sát đối thủ | `saga:research` | 2 | có | 2 RESEARCH |
| Chi | Màn hình giỏ hàng | `saga:code` | 2 | không cần | 2 CODE |
| Chi | Báo cáo UX survey | `saga:research` | 3 | có | 3 RESEARCH |
| Dũng | Trang chủ | `saga:code` | 3 | không cần | 3 CODE |
| Dũng | API danh mục | `saga:code` | 1 | không cần | 1 CODE |
| Dũng | Test regression giỏ hàng | `saga:test` | 2 | không cần | 2 TEST |

**Cộng SP rồi nhân trọng số**

An:

- CODE: `3 + 2 = 5` → `5 × 0.40 = 2.00`
- TEST: `3` → `3 × 0.10 = 0.30`
- DOCUMENT: `2` → `2 × 0.15 = 0.30`
- RESEARCH: `0` → `0`
- **slice An = 2.00 + 0.30 + 0.30 = 2.60**

Bình:

- DOCUMENT: `2 + 2 = 4` → `4 × 0.15 = 0.60`
- RESEARCH: `2` → `2 × 0.35 = 0.70`
- **slice Bình = 0.60 + 0.70 = 1.30**

Chi:

- CODE: `2` → `2 × 0.40 = 0.80`
- RESEARCH: `3` → `3 × 0.35 = 1.05`
- **slice Chi = 0.80 + 1.05 = 1.85**

Dũng:

- CODE: `3 + 1 = 4` → `4 × 0.40 = 1.60`
- TEST: `2` → `2 × 0.10 = 0.20`
- **slice Dũng = 1.60 + 0.20 = 1.80**

**Adjust sprint 1** (`slice × P_s`)

| Thành viên | Slice | × P_s | Adjust |
|---|---|---|---|
| An | 2.60 | × 0.40 | 1.04 |
| Bình | 1.30 | × 0.30 | 0.39 |
| Chi | 1.85 | × 0.20 | 0.37 |
| Dũng | 1.80 | × 0.10 | 0.18 |
| **Tổng adjust** | | | **1.98** |

**% sprint 1** = adjust / 1.98 × 100

| An | Bình | Chi | Dũng |
|---|---|---|---|
| 52.53% | 19.70% | 18.69% | 9.09% |

---

### Sprint 2

| Thành viên | Task | Nhãn | SP | File đính kèm | SP công nhận |
|---|---|---|---|---|---|
| An | Webhook thanh toán | `saga:code` | 3 | không cần | 3 CODE |
| An | So sánh cổng thanh toán | `saga:research` | 2 | có | 2 RESEARCH |
| Bình | Service đơn hàng | `saga:code` | 4 | không cần | 4 CODE |
| Bình | Worker gửi mail | `saga:code` | 2 | không cần | 2 CODE |
| Bình | Test luồng đặt hàng | `saga:test` | 3 | không cần | 3 TEST |
| Bình | Tài liệu luồng đơn | `saga:document` | 2 | có | 2 DOCUMENT |
| Chi | Test API webhook | `saga:test` | 2 | không cần | 2 TEST |
| Chi | README onboard | `saga:document` | 2 | có | 2 DOCUMENT |
| Chi | Changelog sprint 2 | `saga:document` | 2 | có | 2 DOCUMENT |
| Dũng | Sửa lỗi filter | `saga:code` | 1 | không cần | 1 CODE |
| Dũng | Nghiên cứu thư viện PDF | `saga:research` | 3 | có | 3 RESEARCH |

An: CODE `3 × 0.40 = 1.20`, RESEARCH `2 × 0.35 = 0.70` → **slice = 1.90**  
Bình: CODE `(4+2)×0.40 = 2.40`, TEST `3×0.10 = 0.30`, DOCUMENT `2×0.15 = 0.30` → **slice = 3.00**  
Chi: TEST `2×0.10 = 0.20`, DOCUMENT `(2+2)×0.15 = 0.60` → **slice = 0.80**  
Dũng: CODE `1×0.40 = 0.40`, RESEARCH `3×0.35 = 1.05` → **slice = 1.45**

| Thành viên | Slice | × P_s | Adjust |
|---|---|---|---|
| An | 1.90 | × 0.40 | 0.76 |
| Bình | 3.00 | × 0.30 | 0.90 |
| Chi | 0.80 | × 0.20 | 0.16 |
| Dũng | 1.45 | × 0.10 | 0.145 |
| **Tổng adjust** | | | **1.965** |

**% sprint 2:** An 38.68% · Bình 45.80% · Chi 8.14% · Dũng 7.38%

---

### Sprint 3

| Thành viên | Task | Nhãn | SP | File đính kèm | SP công nhận |
|---|---|---|---|---|---|
| An | Fix null pointer checkout | `saga:code` | 2 | không cần | 2 CODE |
| An | Test biên payment | `saga:test` | 2 | không cần | 2 TEST |
| An | Test tích hợp webhook | `saga:test` | 2 | không cần | 2 TEST |
| An | Tài liệu lỗi đã sửa | `saga:document` | 3 | có | 3 DOCUMENT |
| Bình | API báo cáo | `saga:code` | 4 | không cần | 4 CODE |
| Bình | Spec báo cáo | `saga:document` | 5 | **không** | **0** |
| Chi | Dashboard lecturer | `saga:code` | 3 | không cần | 3 CODE |
| Chi | Biểu đồ burndown | `saga:code` | 2 | không cần | 2 CODE |
| Chi | Test dashboard | `saga:test` | 1 | không cần | 1 TEST |
| Chi | Nghiên cứu thư viện chart | `saga:research` | 2 | có | 2 RESEARCH |
| Dũng | Test export CSV | `saga:test` | 2 | không cần | 2 TEST |
| Dũng | Hướng dẫn export | `saga:document` | 4 | có | 4 DOCUMENT |

Bình “Spec báo cáo”: có nhãn `saga:document` nhưng **không file** → SP không vào DOCUMENT.

An: CODE `2×0.40 = 0.80`, TEST `(2+2)×0.10 = 0.40`, DOCUMENT `3×0.15 = 0.45` → **slice = 1.65**  
Bình: CODE `4×0.40 = 1.60`, DOCUMENT `0` → **slice = 1.60**  
Chi: CODE `(3+2)×0.40 = 2.00`, TEST `1×0.10 = 0.10`, RESEARCH `2×0.35 = 0.70` → **slice = 2.80**  
Dũng: TEST `2×0.10 = 0.20`, DOCUMENT `4×0.15 = 0.60` → **slice = 0.80**

| Thành viên | Slice | × P_s | Adjust |
|---|---|---|---|
| An | 1.65 | × 0.40 | 0.66 |
| Bình | 1.60 | × 0.30 | 0.48 |
| Chi | 2.80 | × 0.20 | 0.56 |
| Dũng | 0.80 | × 0.10 | 0.08 |
| **Tổng adjust** | | | **1.78** |

**% sprint 3:** An 37.08% · Bình 26.97% · Chi 31.46% · Dũng 4.49%

---

### Sprint 4

| Thành viên | Task | Nhãn | SP | File đính kèm | SP công nhận |
|---|---|---|---|---|---|
| An | Tối ưu query | `saga:code` | 2 | không cần | 2 CODE |
| An | Nghiên cứu cache Redis | `saga:research` | 3 | có | 3 RESEARCH |
| An | Làm UI settings | `backend` | 2 | — | **0** (không nhãn reserved) |
| Bình | Tài liệu deploy | `saga:document` | 2 | có | 2 DOCUMENT |
| Bình | So sánh Redis vs Memcached | `saga:research` | 5 | có | 5 RESEARCH |
| Chi | Test load trang chủ | `saga:test` | 3 | không cần | 3 TEST |
| Chi | Làm landing | `ui-ux` | 4 | — | **0** (không nhãn reserved) |
| Dũng | Module thông báo | `saga:code` | 4 | không cần | 4 CODE |
| Dũng | Socket realtime | `saga:code` | 2 | không cần | 2 CODE |
| Dũng | Test thông báo | `saga:test` | 2 | không cần | 2 TEST |
| Dũng | Ghi chú cấu hình mail | `saga:document` | 1 | có | 1 DOCUMENT |

An “Làm UI settings” nhãn `backend` → không vào tiêu chí nào. Chi “Làm landing” nhãn `ui-ux` → không vào tiêu chí nào.

An: CODE `2×0.40 = 0.80`, RESEARCH `3×0.35 = 1.05`, unlabeled `0` → **slice = 1.85**  
Bình: DOCUMENT `2×0.15 = 0.30`, RESEARCH `5×0.35 = 1.75` → **slice = 2.05**  
Chi: TEST `3×0.10 = 0.30`, unlabeled `0` → **slice = 0.30**  
Dũng: CODE `(4+2)×0.40 = 2.40`, TEST `2×0.10 = 0.20`, DOCUMENT `1×0.15 = 0.15` → **slice = 2.75**

| Thành viên | Slice | × P_s | Adjust |
|---|---|---|---|
| An | 1.85 | × 0.40 | 0.74 |
| Bình | 2.05 | × 0.30 | 0.615 |
| Chi | 0.30 | × 0.20 | 0.06 |
| Dũng | 2.75 | × 0.10 | 0.275 |
| **Tổng adjust** | | | **1.69** |

**% sprint 4:** An 43.79% · Bình 36.39% · Chi 3.55% · Dũng 16.27%

---

### Cuối dự án

Cộng **slice** bốn sprint (chưa nhân peer):

| Thành viên | S1 | S2 | S3 | S4 | **Σ slice** |
|---|---|---|---|---|---|
| An | 2.60 | 1.90 | 1.65 | 1.85 | **8.00** |
| Bình | 1.30 | 3.00 | 1.60 | 2.05 | **7.95** |
| Chi | 1.85 | 0.80 | 2.80 | 0.30 | **5.75** |
| Dũng | 1.80 | 1.45 | 0.80 | 2.75 | **6.80** |
| **Tổng** | | | | | **28.50** |

P cả dự án: An `16/40 = 0.40`, Bình `12/40 = 0.30`, Chi `8/40 = 0.20`, Dũng `4/40 = 0.10`.

| Thành viên | Σ slice | × P | Adjust |
|---|---|---|---|
| An | 8.00 | × 0.40 | 3.200 |
| Bình | 7.95 | × 0.30 | 2.385 |
| Chi | 5.75 | × 0.20 | 1.150 |
| Dũng | 6.80 | × 0.10 | 0.680 |
| **Tổng adjust** | | | **7.415** |

**% đóng góp cuối** = adjust / 7.415 × 100

| An | Bình | Chi | Dũng |
|---|---|---|---|
| **43.16%** | **32.16%** | **15.51%** | **9.17%** |

Tổng 100%.

---

## 6) Tóm tắt một dòng

Cộng SP cùng tiêu chí → nhân trọng số tiêu chí đó → cộng bốn tiêu chí ra `slice` → nhân `P` (sao cá nhân / sao team) → chia tổng adjust nhóm → ra %. Sprint hiện `%` của sprint đó; cuối dự án cộng `slice` rồi mới nhân `P` cả team.
