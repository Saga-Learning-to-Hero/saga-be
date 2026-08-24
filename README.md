# SAGA Backend

Backend service cho **Student Activity Graph Based Continuous Assessment Project-Based Learning (SAGA)**.

SAGA kết hợp dữ liệu học thuật/đồ án với hoạt động trên Jira và GitHub, xây dựng Graph Read Model (mô hình phục vụ truy vấn đọc theo đồ thị), và hỗ trợ cập nhật Continuous Assessment (đánh giá liên tục) theo hướng near-realtime cho Frontend.

> **Giai đoạn hiện tại:** Database V2 foundation.  
> Flyway V1 (52 tables) và JPA entities đã có. Các phần sau **chưa được triển khai**: business feature, authentication flow, tích hợp provider, messaging, graph projection, và realtime endpoint.

---

## 1. Architecture Baseline

| Hạng mục | Quyết định |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 4.1.0 |
| Web model | Spring MVC |
| Build | Maven Wrapper |
| Primary database | MySQL |
| Graph database | Neo4j |
| Async messaging | RabbitMQ |
| In-memory / ephemeral data | Redis |
| Incoming provider events | GitHub / Jira Webhook |
| Backend → Browser realtime | Server-Sent Events (SSE) |
| Schema migration | Flyway |
| Architecture style | Modular Monolith, technical layer đặt tại root package |

### Quy tắc Source of Truth

**MySQL là Source of Truth (Nguồn dữ liệu chuẩn duy nhất).**

Neo4j là **Graph Read Model / Projection có thể rebuild**. Redis là **hạ tầng ephemeral**. RabbitMQ là **event transport**, không phải database nghiệp vụ.

Ứng dụng không được dựa vào dual-write trực tiếp MySQL + Neo4j làm cơ chế consistency.

---

## 2. Hai mục tiêu cốt lõi

Kiến trúc phải bảo vệ hai đặc trưng của sản phẩm:

### Graph-Based

Hệ thống phải mô hình hóa và truy vấn được quan hệ có ý nghĩa giữa các thực thể học thuật/đồ án, ví dụ:

- student;
- course/class;
- project/topic;
- team;
- sprint/milestone;
- task;
- GitHub commit / pull request;
- hoạt động Jira;
- kết quả assessment.

Graph không chỉ phục vụ visualization. Đây là Read Model dùng để giải thích quan hệ và đường đi contribution/activity.

### Continuous Assessment

Khi có activity liên quan, hệ thống cần ingest event, xử lý bất đồng bộ, cập nhật trạng thái authoritative, cập nhật graph projection, rồi phát realtime change tới client đang kết nối.

Luồng kiến trúc mục tiêu:

```text
GitHub / Jira
      |
      | Webhook
      v
Spring Boot Webhook Ingress
      |
      v
Validation + Idempotency
      |
      v
RabbitMQ
      |
      v
Business Processing
      |
      v
MySQL (Source of Truth)
      |
      +----> Outbox / Projection Pipeline ----> Neo4j
      |
      +----> Versioned Realtime Event --------> SSE --------> Next.js
```

Phần implement sẽ được bổ sung dần. Sơ đồ trên là kiến trúc mục tiêu, **không phải bằng chứng** các thành phần đã shipped.

---

## 3. Cấu trúc repository

```text
saga-be/
├── pom.xml
├── mvnw
├── mvnw.cmd
├── docs/
├── infra/
├── scripts/
└── src/
    ├── main/
    │   ├── java/com/saga/be/
    │   │   ├── SagaBeApplication.java
    │   │   ├── auth/
    │   │   ├── config/
    │   │   ├── controller/
    │   │   ├── dto/
    │   │   ├── entity/
    │   │   ├── exception/
    │   │   ├── graph/
    │   │   ├── integration/
    │   │   ├── mapper/
    │   │   ├── messaging/
    │   │   ├── realtime/
    │   │   ├── repository/
    │   │   ├── scheduler/
    │   │   ├── security/
    │   │   └── service/
    │   └── resources/
    │       └── application.properties
    └── test/
        └── java/com/saga/be/
```

Project cố ý giữ **technical layer tại root**, không bắt mỗi feature phải có `api/application/domain/infrastructure`.

Package domain/feature lồng nhau chỉ được tạo **khi feature đó đã được thiết kế và triển khai**.

---

## 4. Trách nhiệm từng package

| Package | Trách nhiệm |
| --- | --- |
| `controller` | HTTP entry point. Phải giữ thin. |
| `security` | Authentication, authorization, filter, principal, hạ tầng OAuth/security. |
| `auth` | Logic workflow tài khoản/authentication. Chiến lược session/token **chưa chốt**. |
| `service` | Business rule và orchestration cốt lõi của SAGA. |
| `integration` | Giao tiếp với provider bên ngoài như Jira và GitHub. |
| `messaging` | RabbitMQ, inbox/outbox, idempotency, hạ tầng event bất đồng bộ. |
| `realtime` | Phát SSE, vòng đời kết nối, versioning realtime, Redis fan-out khi cần. |
| `graph` | Orchestration projection/query đồ thị và mapping đặc thù graph. |
| `repository` | Truy cập persistence. |
| `entity` | Biểu diễn persistence: MySQL entity, Neo4j node/relationship. |
| `dto` | Model request/response cho API, event, webhook. |
| `config` | Cấu hình framework và hạ tầng. |
| `mapper` | Mapping giữa API, persistence, provider và model nội bộ. |
| `scheduler` | Job định kỳ / reconciliation. |
| `exception` | Exception có kiểu và global exception handling. |

---

## 5. Vai trò của MySQL / Neo4j / RabbitMQ / Redis

| Thành phần | Vai trò |
| --- | --- |
| MySQL | Source of Truth cho trạng thái nghiệp vụ. |
| Neo4j | Graph Read Model / Projection, có thể rebuild từ dữ liệu authoritative. |
| RabbitMQ | Async Message Broker: vận chuyển và đệm event. Không phải database nghiệp vụ. |
| Redis | Cache / Rate Limiting / Realtime Fan-out. Redis **không** phải durable Source of Truth. |

---

## 6. Quy tắc không được phá

1. **Controller không chứa business logic.**
2. **Controller không gọi trực tiếp client Jira/GitHub.**
3. **Controller không thao tác trực tiếp Neo4j, RabbitMQ hoặc Redis.**
4. **MySQL là trạng thái nghiệp vụ authoritative.**
5. **Neo4j phải rebuild được từ dữ liệu/event authoritative.**
6. **Không dual-write trực tiếp MySQL và Neo4j như một logical transaction.**
7. **Xử lý Webhook phải idempotent.**
8. **RabbitMQ consumer phải chịu được redelivery.**
9. **Khi realtime được triển khai, SSE event phải hỗ trợ version/gap recovery.**
10. **Không coi Redis là durable event source.**
11. **Không đưa WebFlux / R2DBC / reactive infrastructure vào trừ khi có Decision thay thế baseline Spring MVC.**
12. **Không thêm database, broker, framework, hoặc pattern kiến trúc lớn nếu chưa cập nhật `docs/SAGA_DECISION_LOG.md`.**
13. **Không commit secret, provider token, credential, hoặc production connection string.**
14. **Không tạo package/class suy đoán cho feature chưa được thiết kế.**

---

## 7. Tài liệu kiến trúc nằm ở đâu

Đọc các file sau trước khi thay đổi kiến trúc:

- [`docs/SAGA_BACKEND_ARCHITECTURE.md`](docs/SAGA_BACKEND_ARCHITECTURE.md) — kiến trúc và luồng dữ liệu.
- [`docs/SAGA_DECISION_LOG.md`](docs/SAGA_DECISION_LOG.md) — các Decision đã chốt.
- [`docs/SAGA_CURRENT_STATE.md`](docs/SAGA_CURRENT_STATE.md) — những gì **thực sự đã implement** hiện nay.
- [`docs/SAGA_BACKEND_REQUIREMENTS_DEPENDENCIES_CONSTRAINTS.md`](docs/SAGA_BACKEND_REQUIREMENTS_DEPENDENCIES_CONSTRAINTS.md) — yêu cầu, dependency và ràng buộc.
- [`docs/FRONTEND_API_INTEGRATION.md`](docs/FRONTEND_API_INTEGRATION.md) — contract tích hợp FE khi endpoint đã được implement.

**Tài liệu phải mô tả hành vi đã shipped, không mô tả kế hoạch như thể đã xong, trừ khi section đó được đánh dấu planned/TBD.**

---

## 8. Frontend nên đọc file nào

Frontend developer thông thường chỉ cần:

```text
docs/FRONTEND_API_INTEGRATION.md
+
controller/
```

Provider client nội bộ, RabbitMQ consumer, projection worker, hạ tầng Redis, repository và entity là chi tiết implement của Backend.

Repository này không chứa Frontend code.

---

## 9. Trạng thái hiện tại

Project đang ở giai đoạn **Database V2 foundation**.

Các phần sau **CHƯA TRIỂN KHAI**:

- Authentication / Authorization
- Google OAuth flow
- chiến lược session/token
- tích hợp Jira / GitHub
- Webhook
- RabbitMQ topology
- Webhook Inbox processor / Transactional Outbox publisher
- Redis Rate Limiting / Redis realtime fan-out
- Neo4j projection / Graph API
- Assessment algorithm
- SSE endpoint / versioned realtime event

MySQL schema / Flyway V1 (52 tables) = **đã có**. Xem `docs/SAGA_V2_ERD.md`.

Trước khi giả định ứng dụng chạy được local, kiểm tra:

1. `docs/SAGA_CURRENT_STATE.md`;
2. trạng thái cấu hình local MySQL / Neo4j / RabbitMQ / Redis;
3. profile / biến môi trường đã được giới thiệu hay chưa;
4. Flyway migration đã tồn tại hay chưa.

Không thêm credential giả hoặc tắt kiểm tra security/hạ tầng chỉ để startup trông như thành công.

Lệnh build/test sẽ được ghi tại đây khi local profile đầu tiên được chốt.

---

## 10. Kỷ luật thay đổi

Pull request thay đổi bất kỳ mục nào dưới đây phải cập nhật tài liệu liên quan trong cùng thay đổi đó:

- public API contract;
- hành vi authentication/session;
- quyền sở hữu database;
- quy tắc graph projection;
- ngữ nghĩa phát event;
- hành vi tích hợp Jira/GitHub;
- trách nhiệm Redis;
- RabbitMQ topology;
- contract realtime/SSE;
- ownership của package;
- yêu cầu deploy/runtime.

Nếu thay đổi xung đột với Decision đã chốt, cập nhật Decision Log trước và giải thích vì sao Decision cũ bị supersede.
