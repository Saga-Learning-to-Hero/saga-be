# Kiến trúc SAGA Backend

## 1. Mục đích

Tài liệu này định nghĩa architecture baseline của SAGA Backend.

Đây là **living architecture contract**. Nội dung mô tả:

- trách nhiệm từng thành phần;
- hướng dependency được phép;
- quyền sở hữu dữ liệu;
- luồng event;
- quy tắc graph projection;
- ranh giới realtime;
- kỳ vọng về độ tin cậy.

Không được khẳng định thành phần đang lên kế hoạch như thể đã implement. Trạng thái shipped xem `SAGA_CURRENT_STATE.md`.

---

## 2. Phong cách kiến trúc

SAGA Backend hiện là **Modular Monolith**.

Root package Java:

```text
com.saga.be
```

Project dùng **technical layer tại root package**:

```text
controller/
security/
auth/
service/
integration/
messaging/
realtime/
graph/
repository/
entity/
dto/
config/
mapper/
scheduler/
exception/
```

Cấu trúc này cố ý khác Clean Architecture / Hexagonal / cây thư mục DDD theo từng feature.

Subpackage feature/domain có thể được thêm bên trong các layer này khi feature tồn tại.

Ví dụ:

```text
service/
├── assessment/
├── project/
└── task/

integration/
├── github/
└── jira/
```

Không tạo nested package suy đoán trước khi feature tương ứng được thiết kế.

---

## 3. System context tổng quát

```text
                    +----------------------+
                    |      Next.js FE      |
                    +----------+-----------+
                               |
                      REST     |     SSE
                               |
                               v
+-----------+          +-------+-------+          +-----------+
|  GitHub   | Webhook  |   SAGA BE     | Webhook  |   Jira    |
+-----+-----+--------->|  Spring Boot  |<---------+-----+-----+
      |                +-------+-------+                |
      |                        |                        |
      |                        v                        |
      |                 +-------------+                 |
      |                 | RabbitMQ    |                 |
      |                 +------+------+                 |
      |                        |                        |
      |                        v                        |
      |                 +-------------+                 |
      |                 | Business    |                 |
      |                 | Processing  |                 |
      |                 +------+------+                 |
      |                        |                        |
      |                        v                        |
      |                 +-------------+                 |
      |                 | MySQL       |                 |
      |                 | Source of   |                 |
      |                 | Truth       |                 |
      |                 +------+------+                 |
      |                        |
      |                 +------+-------+
      |                 |              |
      |                 v              v
      |             +--------+      +--------+
      |             | Neo4j  |      | Redis  |
      |             | Read   |      | Cache/ |
      |             | Model  |      | Fanout |
      |             +--------+      +--------+
      |
      +---- provider APIs are accessed only through integration/*
```

Đây là **kiến trúc mục tiêu**. Từng khối vẫn có thể chưa được implement.

---

## 4. Ranh giới trách nhiệm

### 4.1 `controller`

Trách nhiệm:

- map HTTP request/response;
- validate input ở tầng transport;
- lấy authenticated principal;
- gọi application/business service;
- chuyển kết quả thành response DTO.

Không được:

- chứa assessment algorithm;
- gọi trực tiếp client Jira/GitHub;
- gọi trực tiếp RabbitMQ để orchestration nghiệp vụ;
- thao tác trực tiếp Redis;
- thao tác trực tiếp Neo4j;
- viết persistence query.

Luồng ưu tiên:

```text
Controller
   |
   v
Service
   |
   +--> Repository
   +--> Integration
   +--> Messaging
   +--> Graph orchestration
```

---

### 4.2 `security`

Sở hữu hạ tầng security kỹ thuật:

- cấu hình Spring Security;
- authentication filter;
- authorization rule;
- principal;
- tích hợp kỹ thuật OAuth/OIDC;
- hạ tầng bảo mật session/token.

Mô hình authentication/session/token chính xác vẫn là design decision còn mở.

Security code phải fail closed. Identity unknown không được nâng lên privileged role chỉ vì không khớp student pattern.

---

### 4.3 `auth`

Sở hữu workflow tài khoản/authentication đặc thù ứng dụng.

Ví dụ trách nhiệm tương lai:

- account provisioning;
- workflow credential nội bộ;
- liên kết Google identity;
- account verification;
- workflow role-resolution;
- vòng đời password.

`auth` được tách khỏi `security` tầng thấp một cách có chủ đích.

---

### 4.4 `service`

Sở hữu business logic SAGA và orchestration use-case.

Domain tiềm năng:

- user;
- cấu trúc học thuật;
- project;
- team;
- task;
- assessment;
- notification;
- audit/history.

Business rule không nên phụ thuộc trực tiếp HTTP request object hoặc payload model đặc thù provider.

---

### 4.5 `integration`

Sở hữu giao tiếp với hệ thống bên ngoài.

Provider ban đầu:

```text
integration/
├── github/
└── jira/
```

Trách nhiệm tiềm năng trong từng provider:

- API client;
- tương tác OAuth với provider;
- xác thực/parse webhook;
- mapping DTO của provider;
- synchronization;
- xử lý Rate Limiting;
- dịch lỗi provider.

Model đặc thù provider không được rò rỉ không cần thiết sang phần còn lại của ứng dụng.

---

### 4.6 `messaging`

Sở hữu messaging bất đồng bộ và độ tin cậy khi phát/nhận.

Trách nhiệm dự kiến:

```text
messaging/
├── rabbitmq/
├── inbox/
├── outbox/
└── idempotency/
```

Cây package chính xác chỉ được tạo khi bắt đầu implement.

Concern của messaging gồm:

- webhook deduplication;
- durable queue topology;
- Publisher Confirm;
- Manual ACK phía consumer;
- chính sách Retry;
- xử lý Dead Letter Queue (DLQ);
- Idempotency phía consumer;
- phát Transactional Outbox.

RabbitMQ vận chuyển event. RabbitMQ không phải kho nghiệp vụ authoritative.

---

### 4.7 `realtime`

Sở hữu kênh realtime Backend → Browser.

Trách nhiệm dự kiến:

- vòng đời kết nối SSE;
- emitter registry;
- heartbeat;
- versioned event;
- contract xử lý duplicate/gap;
- Realtime Fan-out qua Redis khi cần nhiều backend instance.

SSE phải mang **change/delta**, không gửi lại toàn bộ graph lớn cho mọi event.

Redis có thể hỗ trợ fan-out nhưng không được xem là durable delivery.

---

### 4.8 `graph`

Sở hữu orchestration đặc thù graph.

Trách nhiệm dự kiến:

```text
graph/
├── projection/
├── query/
└── mapper/
```

Neo4j là **Read Model / Projection**.

Quy tắc:

- trạng thái nghiệp vụ authoritative vẫn nằm ở MySQL;
- graph projection phải lặp lại được / idempotent;
- dữ liệu Neo4j phải rebuild được;
- graph query phải bounded;
- load toàn bộ graph không giới hạn không được trở thành hành vi API mặc định.

---

### 4.9 `repository`

Sở hữu truy cập persistence.

Công nghệ persistence tiềm năng:

```text
repository/
├── jpa/
├── neo4j/
└── redis/
```

Cấu trúc package chỉ được giới thiệu khi repository thực sự tồn tại.

Repository không được trở thành một tầng business-service thứ hai.

---

### 4.10 `entity`

Sở hữu biểu diễn persistence.

Ví dụ tương lai:

```text
entity/
├── mysql/
├── neo4j/
└── enums/
```

Persistence entity không tự động là public API representation.

---

### 4.11 `dto`

Sở hữu shape payload cho transport/event.

Nhóm tiềm năng:

```text
dto/
├── request/
├── response/
├── webhook/
└── event/
```

Không expose persistence entity trực tiếp làm public API contract.

---

## 5. Quyền sở hữu dữ liệu authoritative

### MySQL

MySQL là Source of Truth của hệ thống cho trạng thái nghiệp vụ authoritative.

Ví dụ có thể gồm:

- account/profile;
- dữ liệu học thuật;
- snapshot project/team/task;
- cấu hình assessment;
- kết quả assessment;
- metadata tích hợp;
- metadata event/inbox/outbox;
- audit/history khi cần.

Schema chính xác được định nghĩa sau qua Flyway migration.

### Neo4j

Neo4j lưu Projection hướng graph phục vụ traversal/query/read.

Neo4j không được trở thành Source of Truth nghiệp vụ độc lập, cạnh tranh với MySQL.

### Redis

Redis dành cho concern ephemeral/tốc độ cao, ví dụ:

- Rate Limiting;
- Cache;
- phối hợp tạm thời;
- Realtime Fan-out khi nhiều application instance cần.

Delivery kiểu Redis Pub/Sub không được xem là durable history.

### RabbitMQ

RabbitMQ cung cấp buffering và vận chuyển event bất đồng bộ.

Trạng thái nghiệp vụ phải recover được mà không coi queue là primary database.

---

## 6. Pipeline xử lý Webhook (mục tiêu)

Pipeline mục tiêu:

```text
Provider Webhook
      |
      v
HTTP Ingress
      |
      +--> Verify signature/authenticity
      |
      +--> Validate payload constraints
      |
      +--> Deduplicate provider delivery ID
      |
      v
Inbox / Accepted Event
      |
      v
RabbitMQ
      |
      v
Consumer
      |
      v
Business Service
      |
      v
MySQL Transaction
      |
      +--> authoritative state update
      |
      +--> outbox record
      |
      v
ACK only after safe processing boundary
```

Yêu cầu:

- redelivery từ provider không được tạo side effect trùng;
- Retry phải bounded;
- poison message cần chiến lược Dead Letter Queue (DLQ);
- consumer phải chịu được redelivery;
- request handler phải tránh tính toán graph nặng.

---

## 7. Mô hình consistency MySQL → Neo4j

Cấm dual-write logic trực tiếp:

```text
saveToMySql();
saveToNeo4j();
```

Lời gọi thứ hai có thể fail sau khi lời gọi thứ nhất đã commit.

Pattern mục tiêu:

```text
MySQL transaction
    |
    +--> update authoritative state
    |
    +--> insert outbox event
    |
    v
commit
    |
    v
projection pipeline
    |
    v
idempotent Neo4j upsert
```

Neo4j phải expose đủ thông tin projection version/identity để reconciliation và rebuild.

---

## 8. Mô hình consistency realtime

Shape SSE event mục tiêu, mang tính khái niệm:

```json
{
  "eventId": "...",
  "scopeId": "...",
  "version": 123,
  "type": "...",
  "occurredAt": "...",
  "data": {}
}
```

Tên field và endpoint path chính xác là **TBD / Chưa chốt** và phải được ghi trong `FRONTEND_API_INTEGRATION.md` trước khi FE phụ thuộc vào chúng.

Mô hình recovery phía client:

```text
receivedVersion == currentVersion + 1
    -> apply delta

receivedVersion <= currentVersion
    -> ignore duplicate/stale event

receivedVersion > currentVersion + 1
    -> gap detected
    -> recover from authoritative API/snapshot/delta endpoint
```

SSE là kênh delivery, không phải Source of Truth.

---

## 9. Mô hình concurrency

Baseline hiện tại:

```text
Spring MVC
+
Java 21
+
Virtual Threads where appropriate
```

Không đưa stack reactive một phần vào bằng cách trộn blocking JPA/AMQP code với reactive event-loop code.

Nếu project chuyển sang WebFlux / R2DBC / reactive infrastructure, đó là thay đổi kiến trúc và phải supersede Decision hiện có trong `SAGA_DECISION_LOG.md`.

---

## 10. Quy tắc hiệu năng Graph

Graph endpoint và projection code phải được thiết kế quanh khối lượng công việc bounded.

Quy tắc:

1. Không trả về unbounded whole-system graph theo mặc định.
2. Hỗ trợ query scope như course/project/sprint/team/depth/status khi phù hợp.
3. Định nghĩa kích thước kết quả tối đa phía server.
4. Ưu tiên delta update cho thay đổi realtime.
5. Tránh rebuild/recompute toàn bộ graph vì thay đổi trạng thái một node.
6. Query Neo4j phải dùng constraint/index phù hợp khi schema đã có.
7. Projection write nên hỗ trợ batching khi có lợi.
8. Mục tiêu hiệu năng phải dựa trên load test, không dựa trên giả định.

---

## 11. Nguyên tắc lỗi và recovery

Backend cuối cùng phải chịu được:

- webhook trùng;
- event lệch thứ tự;
- timeout từ provider;
- RabbitMQ redelivery;
- consumer crash;
- Neo4j downtime;
- Redis restart;
- SSE disconnect/reconnect;
- client chậm;
- outage một phần phía provider.

Lỗi tạm thời ở projection hoặc kênh realtime không được làm hỏng trạng thái MySQL authoritative.

---

## 12. Quy trình thay đổi kiến trúc

Thay đổi làm lệch bất kỳ quy tắc kiến trúc nào trong file này cần:

1. một entry trong `SAGA_DECISION_LOG.md`;
2. lý do;
3. lựa chọn đã loại;
4. hệ quả / tác động migration;
5. cập nhật file này;
6. cập nhật `SAGA_CURRENT_STATE.md` khi đã implement.

Không thay thế thầm lặng một Decision kiến trúc đã có.
