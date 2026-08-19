# SAGA Backend Decision Log

File này ghi các architecture Decision. Không được thay đổi thầm lặng.

Giá trị trạng thái:

- **ĐÃ CHỐT KIẾN TRÚC** — baseline hiện tại.
- **ĐANG ĐỀ XUẤT** — đang thảo luận, chưa ràng buộc.
- **ĐÃ BỊ THAY THẾ** — bị một Decision sau thay thế.

Định dạng ngày: `YYYY-MM-DD`.

---

## Mục lục Decision

| ID | Decision | Trạng thái | Ngày |
| --- | --- | --- | --- |
| DEC-001 | Modular Monolith trước | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-002 | MySQL là Source of Truth | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-003 | Neo4j là Graph Read Model có thể rebuild | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-004 | Không dual-write trực tiếp MySQL + Neo4j | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-005 | RabbitMQ là broker bất đồng bộ | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-006 | GitHub/Jira inbound realtime dùng Webhook | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-007 | Backend → Browser realtime dùng SSE | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-008 | Baseline Spring MVC + Java 21; không dùng WebFlux làm baseline | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-009 | Redis là hạ tầng ephemeral, không phải durable truth | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-010 | SSE event phải hỗ trợ version/gap recovery | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-011 | Root package theo cấu trúc technical layer của SAGA | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-012 | Backend mới không dùng AWS Cognito làm primary auth platform | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-013 | MongoDB không thuộc initial backend baseline | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-014 | Thay đổi schema database dùng Flyway | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |
| DEC-015 | Public contract với FE được ghi cùng thay đổi controller | ĐÃ CHỐT KIẾN TRÚC | 2026-08-19 |

---

# DEC-001 — Modular Monolith trước

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Xây SAGA Backend theo Modular Monolith trước khi cân nhắc microservices.

## Lý do

Hệ thống đã chứa nhiều concern phức tạp:

- authentication;
- tích hợp GitHub/Jira;
- messaging bất đồng bộ;
- graph projection;
- realtime delivery;
- logic assessment.

Tách các phần này thành service deploy độc lập trước khi biết ranh giới scale thật sẽ tăng độ phức tạp vận hành và consistency mà chưa có nhu cầu được chứng minh.

## Hệ quả

- một Spring Boot deployable vẫn là baseline;
- ranh giới package/module vẫn phải được tôn trọng;
- worker/thành phần realtime có thể tách sau nếu số liệu tải chứng minh được nhu cầu.

---

# DEC-002 — MySQL là Source of Truth

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Trạng thái nghiệp vụ authoritative được lưu trong MySQL.

## Hệ quả

Neo4j, Redis và RabbitMQ không được trở thành kho authoritative cạnh tranh.

---

# DEC-003 — Neo4j là Graph Read Model có thể rebuild

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Neo4j dùng cho workload traversal/query/read trên graph và lưu Projection của trạng thái authoritative.

## Hệ quả

- thao tác projection phải idempotent;
- dữ liệu Neo4j phải recover/rebuild được;
- payload lớn hoặc metadata nghiệp vụ không liên quan không nên copy vào Neo4j nếu không có lý do graph-query.

---

# DEC-004 — Không dual-write trực tiếp MySQL + Neo4j

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Không implement thao tác nghiệp vụ theo kiểu:

```text
write MySQL
then
write Neo4j
```

rồi giả định cả hai thành công một cách atomic.

Dùng thay đổi MySQL authoritative cộng với pipeline outbox/projection.

## Lý do

Fail sau khi MySQL đã commit nhưng trước khi Neo4j được cập nhật sẽ tạo trạng thái inconsistent, không có bảo đảm ACID xuyên database.

---

# DEC-005 — RabbitMQ là broker bất đồng bộ

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Dùng RabbitMQ cho workload xử lý event/queue hiện tại.

Kafka không thuộc baseline.

## Hệ quả

Khi implement phải tính tới:

- durable topology;
- Publisher Confirm;
- Manual ACK phía consumer;
- Retry bounded;
- xử lý dead-letter;
- Idempotency phía consumer.

Nếu replay event dài hạn, throughput rất cao, hoặc nhiều consumer stream độc lập trở thành yêu cầu đã được chứng minh, việc chọn broker có thể xem lại qua một Decision mới.

---

# DEC-006 — GitHub/Jira inbound realtime dùng Webhook

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Ingest event GitHub/Jira dùng Webhook của provider, không lấy periodic polling làm cơ chế event chính.

## Hệ quả

Webhook ingress phải implement validation authenticity, ràng buộc payload, xử lý provider delivery ID, và Idempotency.

API của provider vẫn có thể được gọi khi ứng dụng cần enrichment/reconciliation.

---

# DEC-007 — Backend → Browser realtime dùng SSE

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Dùng Server-Sent Events (SSE) làm kênh realtime chính Backend → Browser.

## Lý do

Yêu cầu chủ đạo là phát event từ server tới client cho thay đổi graph, assessment, task và notification.

## Hệ quả

Nếu feature tương lai cần messaging hai chiều, latency thấp, khối lượng lớn, WebSocket có thể được xem lại qua một Decision mới.

---

# DEC-008 — Baseline Spring MVC + Java 21; không dùng WebFlux làm baseline

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Dùng Spring MVC với Java 21 và Virtual Threads khi phù hợp.

Không đưa WebFlux vào làm programming model mặc định.

## Lý do

Stack persistence và messaging hiện tại chủ yếu là imperative/blocking.

## Hệ quả

Reactive dependency như WebFlux, R2DBC và Reactive Redis không thuộc baseline.

---

# DEC-009 — Redis là hạ tầng ephemeral

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Redis có thể dùng cho:

- Rate Limiting;
- Cache;
- phối hợp tạm thời;
- Realtime Fan-out khi nhiều instance cần.

Redis không phải kho event/history authoritative.

## Hệ quả

Restart/disconnect Redis không được hủy trạng thái nghiệp vụ authoritative.

---

# DEC-010 — SSE event phải hỗ trợ version/gap recovery

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Realtime event làm thay đổi trạng thái client nhìn thấy phải hỗ trợ thứ tự version và recovery.

## Hệ quả

Frontend phải phát hiện được:

- event duplicate/stale;
- version bị thiếu;
- tình huống reconnect.

Chi tiết recovery endpoint/contract vẫn **TBD / Chưa chốt** cho đến khi realtime API đầu tiên được implement.

---

# DEC-011 — Root package theo cấu trúc technical layer của SAGA

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Giữ các root package theo technical layer kiểu SAGA đã thống nhất:

```text
controller
security
auth
service
integration
messaging
realtime
graph
repository
entity
dto
config
mapper
scheduler
exception
```

Không bắt mỗi feature phải theo `api/application/domain/infrastructure`.

## Lý do

Cấu trúc quen với team và giúp FE/API discovery thẳng, đồng thời vẫn cho phép domain subpackage bên trong từng technical layer.

---

# DEC-012 — Không dùng AWS Cognito làm primary auth platform

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

SAGA backend mới sẽ tự sở hữu trạng thái authentication/tài khoản, không dùng AWS Cognito làm primary authentication platform.

Google OAuth/OIDC có thể dùng làm cơ chế xác minh identity/login bên ngoài.

## Lưu ý

Điều này **không** có nghĩa availability của Google không bao giờ ảnh hưởng tới lần login bằng Google.

Thiết kế session/token nội bộ chính xác vẫn là **ĐANG ĐỀ XUẤT / TBD / Chưa chốt** và không được invent ngầm.

---

# DEC-013 — MongoDB không thuộc initial baseline

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Không thêm MongoDB trong giai đoạn kiến trúc ban đầu.

Yêu cầu audit/history nên được thiết kế trước trên mô hình lưu trữ authoritative hiện có.

## Hệ quả

Thêm MongoDB sau này đòi hỏi yêu cầu đã được chứng minh và một Decision entry mới.

---

# DEC-014 — Thay đổi schema database dùng Flyway

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Thay đổi schema MySQL được version qua Flyway migration.

## Hệ quả

Không đổi schema shared/prod thủ công nếu chưa có migration.

---

# DEC-015 — Thay đổi contract FE phải được ghi tài liệu

**Trạng thái:** ĐÃ CHỐT KIẾN TRÚC  
**Ngày:** 2026-08-19

## Quyết định

Khi public endpoint, auth contract, SSE event, error model, hoặc request/response payload được implement hoặc thay đổi, cập nhật:

```text
docs/FRONTEND_API_INTEGRATION.md
```

trong cùng thay đổi đó.

## Hệ quả

Chỉ có controller code thì chưa đủ làm tài liệu contract cho Frontend.

---

## Thêm Decision mới

Dùng template:

```text
# DEC-XXX — Title

Trạng thái:
Ngày:

## Bối cảnh

Vì sao cần một Decision?

## Quyết định

Chúng ta chọn gì?

## Các lựa chọn khác

Chúng ta loại phương án nào?

## Hệ quả

Điều gì trở nên dễ hơn / khó hơn?

## Migration

Cần đổi gì nếu Decision này thay thế Decision cũ?
```
