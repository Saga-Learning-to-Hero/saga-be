# SAGA Backend — Yêu cầu, Dependency và Ràng buộc

Tài liệu này định nghĩa baseline mà contributor Backend và AI coding tool phải tuân thủ.

---

# 1. Yêu cầu sản phẩm cốt lõi

## 1.1 Graph-Based

Backend phải hỗ trợ quan hệ hướng graph giữa hoạt động học thuật/đồ án.

Graph model cần giải thích quan hệ contribution/activity, không chỉ đóng vai trò dữ liệu UI trang trí.

Yêu cầu đọc/query graph phải ảnh hưởng tới:

- thiết kế Neo4j projection;
- ID ổn định;
- relationship type;
- giới hạn query;
- khả năng truy vết assessment.

---

## 1.2 Continuous Assessment

Event liên quan từ provider/user nên được phản ánh vào trạng thái assessment/graph một cách liên tục hoặc near-realtime.

Điều này đòi hỏi:

- event ingestion;
- xử lý bất đồng bộ đáng tin cậy;
- Idempotency;
- cập nhật trạng thái authoritative;
- cập nhật Projection;
- realtime notification;
- recovery sau lỗi tạm thời.

“Realtime” không có nghĩa là “bỏ qua consistency/reliability”.

---

## 1.3 High Performance / Low Latency

Công việc hiệu năng phải dựa trên bằng chứng đo đạc.

Yêu cầu chung:

- giữ xử lý HTTP webhook ngắn;
- đưa công việc nặng ra khỏi request thread của provider;
- bound graph query;
- tránh N+1 database access;
- dùng batch operation khi phù hợp;
- không recomputation toàn bộ graph cho thay đổi nhỏ;
- dùng timeout với provider bên ngoài;
- dùng Backpressure / giới hạn concurrency;
- load test trước khi tinh chỉnh pool size.

Không công nghệ nào được coi là “nhanh” chỉ vì nó có trong stack.

---

# 2. Dependency baseline

Dependency ban đầu từ Spring Initializr:

| Dependency | Trách nhiệm |
| --- | --- |
| Spring Web | Baseline Spring MVC REST/SSE |
| Spring Security | Hạ tầng authentication và authorization |
| OAuth2 Client | Tích hợp login/provider OAuth/OIDC bên ngoài |
| Spring Data JPA | Persistence MySQL |
| MySQL Driver | JDBC driver |
| Validation | Hỗ trợ validation request/domain |
| Spring Data Neo4j | Persistence projection/query Neo4j |
| Spring for RabbitMQ | Messaging RabbitMQ |
| Spring Data Redis | Truy cập Redis theo kiểu imperative |
| Spring Boot Actuator | Endpoint health/metrics/vận hành |
| Flyway Migration | SQL schema có version |
| Lombok | Giảm boilerplate |

Spring Boot dependency management là nguồn mặc định cho phiên bản dependency tương thích.

Không pin thủ công version module Spring nếu không có lý do tương thích cụ thể.

---

# 3. Công nghệ không thuộc baseline

Các mục sau **không thuộc baseline hiện tại**:

```text
Spring WebFlux
Spring Data R2DBC
Spring Data Reactive Redis
MongoDB
Kafka
Microservices
AWS Cognito as primary auth platform
```

Thêm một trong các mục trên đòi hỏi một architecture Decision.

---

# 4. Ràng buộc lưu trữ

## 4.1 MySQL

MySQL là kho authoritative.

Yêu cầu:

- thay đổi schema dùng chung đi qua Flyway;
- không để schema production lệch thủ công;
- index phải được biện minh bằng query pattern;
- tránh unbounded OFFSET pagination cho dataset lớn khi cursor/keyset pagination phù hợp hơn;
- tránh `SELECT *` khi không cần;
- ngăn pattern truy cập N+1;
- batch write khi burst event khiến từng write đơn lẻ không hiệu quả.

---

## 4.2 Neo4j

Neo4j là Graph Read Model.

Ràng buộc:

- không copy mọi cột MySQL sang Neo4j;
- chỉ project property phục vụ graph/query;
- dùng ID nghiệp vụ/external ổn định;
- projection write phải idempotent;
- graph query phải bounded;
- không expose accidental full-system traversal;
- projection phải rebuild được.

---

## 4.3 Redis

Trách nhiệm ban đầu được phép:

- Rate Limiting;
- Cache;
- phối hợp tạm thời;
- Realtime Fan-out khi nhiều backend instance cần.

Giả định bị cấm:

> Redis chứa bản copy duy nhất của business event/state mà hệ thống phải sống sót sau sự cố.

---

## 4.4 RabbitMQ

RabbitMQ là event transport/buffering.

Yêu cầu độ tin cậy khi đã cấu hình:

- durable topology khi cần;
- persistent message khi cần;
- Publisher Confirm;
- Manual ACK phía consumer;
- Retry bounded;
- Dead Letter Queue (DLQ);
- consumer idempotent;
- prefetch/concurrency tinh chỉnh theo đo đạc.

Redelivery của consumer không được tạo side effect nghiệp vụ trùng.

---

# 5. Ràng buộc Webhook

Webhook endpoint hướng tới provider, không hướng tới Frontend.

Yêu cầu:

1. validate authenticity/signature của provider;
2. validate content type và giới hạn payload;
3. ghi nhận provider delivery/event ID;
4. deduplicate;
5. tránh xử lý nghiệp vụ nặng trong HTTP ingress;
6. chuyển việc sang xử lý bất đồng bộ;
7. chỉ trả success sau khi đạt durability boundary đã định nghĩa khi implement;
8. log đủ dữ liệu correlate để debug, không log secret.

Redelivery từ provider là hành vi kỳ vọng, không phải điều kiện ngoại lệ.

---

# 6. Ràng buộc consistency

Pattern bị cấm:

```java
mysqlRepository.save(...);
neo4jRepository.save(...);
```

khi cả hai write được coi là một atomic business action.

Mô hình mục tiêu:

```text
MySQL business transaction
    +
Outbox record
    |
    v
Asynchronous projection
    |
    v
Neo4j
```

Nếu Neo4j không available, dữ liệu MySQL authoritative vẫn phải đúng.

---

# 7. Ràng buộc realtime

SSE là quyết định transport realtime hiện tại.

Yêu cầu khi đã implement:

- versioned event;
- xử lý event duplicate/stale;
- phát hiện gap;
- recovery API;
- chiến lược reconnect;
- heartbeat;
- bảo vệ slow client;
- kích thước payload event bounded.

Delivery qua SSE/Redis không thay thế trạng thái authoritative.

---

# 8. Ràng buộc bảo mật

## 8.1 Secret

Không bao giờ commit:

- password;
- DB credential;
- OAuth client secret;
- token Jira/GitHub;
- webhook secret;
- encryption key;
- signing key;
- file môi trường production.

`.env.example` sau này chỉ được chứa tên biến.

---

## 8.2 Authentication

Chiến lược session/token nội bộ **chưa chốt**.

Không implement ngầm.

Mọi thiết kế được chấp nhận phải giải quyết:

- lưu credential;
- expiry của session/token;
- logout;
- revocation;
- CSRF nếu dùng cookie-based auth;
- tính toàn vẹn OAuth state/redirect;
- liên kết tài khoản provider;
- hành vi disable/lock tài khoản.

---

## 8.3 Role resolution

Gán role là vấn đề nhạy cảm về bảo mật.

Quy tắc:

- dùng business rule đã verified, tường minh;
- identity mơ hồ phải fail closed;
- “không khớp Student” không tự động nghĩa là “Lecturer”;
- privileged role cần bằng chứng mạnh hơn việc thiếu một pattern khác;
- mọi rule tự phân loại phải test được.

---

# 9. Ràng buộc cấu trúc code

## Controller

Được phép:

- transport validation;
- lấy principal;
- gọi service;
- map response.

Không được phép:

- business algorithm;
- gọi provider client;
- orchestration RabbitMQ trực tiếp;
- truy cập Redis trực tiếp;
- thao tác Neo4j trực tiếp.

## Service

Sở hữu orchestration nghiệp vụ và rule.

## Integration

Sở hữu hành vi với provider bên ngoài.

## Repository

Sở hữu truy cập persistence.

## Messaging

Sở hữu vận chuyển event và độ tin cậy.

## Graph

Sở hữu orchestration projection/query graph.

## Realtime

Sở hữu realtime delivery Backend → Browser.

---

# 10. Ranh giới DTO / Persistence

Không trả persistence entity trực tiếp làm public API contract ổn định.

Dùng DTO cho:

- validation request;
- response contract;
- webhook payload;
- event payload.

DTO của provider nên được tách khỏi business model nội bộ khi thực tế cho phép.

---

# 11. Ràng buộc Migration

Flyway sở hữu lịch sử schema MySQL dùng chung.

Khi bắt đầu làm schema:

```text
src/main/resources/db/migration/
```

sẽ chứa migration có version, ví dụ:

```text
V1__initial_schema.sql
V2__add_...
```

Không tạo file migration suy đoán, rỗng.

Không rewrite migration đã apply trên môi trường dùng chung; hãy tạo migration mới.

---

# 12. Yêu cầu testing

Test nên bám ownership package giống production.

Nhóm kỳ vọng theo thời gian:

- unit test cho business rule;
- integration test controller/security;
- integration test repository;
- test Idempotency của webhook;
- test RabbitMQ redelivery;
- test reconciliation của projection;
- test SSE reconnect/gap;
- contract test cho provider client;
- load/failure test cho pipeline then chốt.

Test mặc định không được phụ thuộc dịch vụ Jira/GitHub thật.

---

# 13. Yêu cầu observability

Hệ thống cuối cùng phải expose đủ telemetry để trả lời:

- Bao nhiêu webhook được accept/reject?
- Bao nhiêu duplicate được phát hiện?
- Queue depth/lag là bao nhiêu?
- Bao nhiêu message nằm trong DLQ?
- Latency xử lý của worker là bao nhiêu?
- Latency query MySQL là bao nhiêu?
- Độ trễ Neo4j projection là bao nhiêu?
- Bao nhiêu SSE client đang kết nối?
- Client recover từ version gap với tần suất nào?
- Tỷ lệ lỗi/timeout của provider là bao nhiêu?

Không khẳng định hiệu năng nếu chưa đo.

---

# 14. Cổng thay đổi kiến trúc

Trước khi đưa vào bất kỳ mục nào sau:

- database mới;
- message broker mới;
- framework mới;
- runtime mới;
- microservice;
- reactive stack;
- auth provider mới;
- thay đổi Source of Truth;
- thay đổi realtime transport;

cập nhật `SAGA_DECISION_LOG.md` với:

- vấn đề;
- quyết định;
- các lựa chọn khác;
- hệ quả;
- tác động migration.

Kiến trúc phải thay đổi có chủ đích, không vô tình thay đổi qua dependency.
