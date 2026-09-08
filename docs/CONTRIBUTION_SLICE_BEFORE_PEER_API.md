# API % đóng góp — hệ số slice trước peer review

Ngày: 2026-08-15. Công thức tính không đổi (`docs/CONTRIBUTION_CALCULATION_SPEC.md`). Thay đổi này chỉ **trả thêm field** trên response evaluation.

## 1. Endpoint

`GET /api/teams/{teamId}/contribution-evaluation`

GET, cookie session `SAGA_SESSION`, `credentials: "include"`. Không CSRF. Không Bearer.

Quyền: ADMIN mọi Team; LECTURER Team thuộc Course mình phụ trách; STUDENT chỉ khi là **LEADER** của đúng Team đó.

## 2. Việc FE cần

Trước đây response chỉ có `% cuối` — đã nhân peer rồi chuẩn hóa team = 100.

Giờ mỗi thành viên (và từng sprint) còn có **hệ số slice trước khi nhân peer**. Dùng cặp này khi cần hiện “điểm theo task/trọng số” tách khỏi “điểm sau khi peer review”.

Không nhân thêm `peerReviewScore` lên `finalContributionPercentage`. `% cuối` đã gồm peer.

Override giảng viên chỉ đụng `% cuối`. `sliceScore` / `sliceContributionPercentage` giữ giá trị tính từ Task.

## 3. Field mới

### 3.1 Trên từng member

| Field | Ý nghĩa |
|---|---|
| `sliceScore` | Σ slice cả dự án. `slice = Σ SP cùng tiêu chí × trọng số`. **Chưa** nhân `P`. |
| `sliceContributionPercentage` | `sliceScore / Σ slice cả team × 100`. % trước peer, tổng team = 100. |
| `finalContributionPercentage` | `% cuối` = `(Σ slice × P) / Σ adjust team × 100`. Đã nhân peer. |
| `peerReviewScore` | `P` cả dự án (`sao cá nhân / sao team`, 0..1). Chỉ để hiển thị. |

`code/test/document/researchContributionPercentage` vẫn là tỷ lệ radar theo từng tiêu chí cả project — không phải slice mix.

### 3.2 Trên từng `sprintBreakdowns[]`

| Field | Ý nghĩa |
|---|---|
| `sliceScore` | Slice của **đúng sprint đó**, chưa nhân `P_s`. |
| `sliceContributionPercentage` | `% slice trong sprint`, chuẩn hóa team = 100, chưa nhân peer sprint. |
| `contributionPercentage` | `% đóng góp sprint` sau khi nhân `P_s`. Sprint chưa peer: `P_s = 1` → trùng `% slice`. |

## 4. Công thức (nhắc lại)

```
slice_sprint(i) = (Σ SP_code)×Wc + (Σ SP_test)×Wt + (Σ SP_doc)×Wd + (Σ SP_research)×Wr

Σslice(i)  = slice_sprint1(i) + slice_sprint2(i) + …
%_slice(i) = Σslice(i) / Σ slice cả team × 100

P(i)       = sao_i / sao team          (chưa ai review → 1)
adjust(i)  = Σslice(i) × P(i)
%_final(i) = adjust(i) / Σ adjust × 100
```

Trọng số dạng tỷ lệ (`40% → 0.40`). Không chia share trong tiêu chí. Task không gắn sprint = 0.

## 5. Ví dụ

Hai người, một sprint, trọng số mặc định 25/25/25/25. Cả hai task `saga:code`. Peer: Alice 4 sao, Bob 1 sao (`P` = 0.8 / 0.2).

| | SP | sliceScore | % slice (trước peer) | × P | % cuối |
|---|---|---|---|---|---|
| Alice | 3 | 0.75 | 37.5 | × 0.8 | 70.59 |
| Bob | 5 | 1.25 | 62.5 | × 0.2 | 29.41 |

```json
{
  "studentId": "…",
  "fullName": "Alice Nguyen",
  "studentCode": "SE001",
  "peerReviewScore": 0.8,
  "sliceScore": 0.75,
  "sliceContributionPercentage": 37.5,
  "finalContributionPercentage": 70.5882,
  "sprintBreakdowns": [
    {
      "sprintId": "…",
      "sprintName": "Sprint 1",
      "sliceScore": 0.75,
      "sliceContributionPercentage": 37.5,
      "contributionPercentage": 70.5882
    }
  ]
}
```

Khi mọi người cùng `P` (hoặc chưa ai peer, `P = 1`), `% slice` trùng `% cuối`.

## 6. Gợi ý UI

- Cột / bar **trước peer:** `sliceContributionPercentage`
- Cột / bar **sau peer:** `finalContributionPercentage`
- Tooltip điểm thô: `sliceScore`
- Từng sprint: cùng cặp field trên `sprintBreakdowns[]`

Không cộng bốn `% sprint` để ra `% cuối`. Không lấy `taskContributionPercentage` thay slice — field đó đi từ story point × retrospective multiplier, khác công thức slice.

Chi tiết tính từng task: `docs/CONTRIBUTION_CALCULATION_SPEC.md`.
