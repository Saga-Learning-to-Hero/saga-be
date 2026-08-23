# SAGA Backend — Trạng thái hiện tại

**Cập nhật lần cuối:** 2026-08-23

File này mô tả những gì **thực sự đang có / đã implement** tại thời điểm hiện tại.

Không lấy kế hoạch kiến trúc tương lai làm bằng chứng rằng feature đã tồn tại.

---

## 1. Giai đoạn hiện tại

**Phase: Repository Bootstrap / Architecture Skeleton**

Project Spring Boot đã được generate và architecture skeleton ban đầu đã có.

Chưa có business feature nào được coi là đã shipped.

---

## 2. Technical baseline hiện tại

| Hạng mục | Baseline hiện tại |
| --- | --- |
| Project | Maven |
| Language | Java 21 |
| Spring Boot | 4.1.0 |
| Root package | `com.saga.be` |
| Packaging | JAR |
| Configuration format | `.properties` |
| Web | Spring Web / Spring MVC |
| Security dependency | Spring Security |
| External login dependency | OAuth2 Client |
| Relational persistence | Spring Data JPA + MySQL Driver |
| Validation | Bean Validation |
| Graph persistence | Spring Data Neo4j |
| Messaging | Spring for RabbitMQ |
| Redis | Spring Data Redis (imperative baseline) |
| Observability | Spring Boot Actuator |
| Migration | Flyway |
| Boilerplate reduction | Lombok |

---

## 3. Repository skeleton đã hoàn thành

Root Java package hiện tại:

```text
src/main/java/com/saga/be/
├── SagaBeApplication.java
├── auth/
├── config/
├── controller/
├── dto/
├── entity/
├── exception/
├── graph/
├── integration/
├── mapper/
├── messaging/
├── realtime/
├── repository/
├── scheduler/
├── security/
└── service/
```

Trong các architecture package hiện chỉ có placeholder `package-info.java`.

Chưa tạo nested feature package.

Placeholder vận hành nằm tại:

```text
infra/
├── docker/
├── neo4j/
├── rabbitmq/
└── redis/

scripts/
├── local/
├── migration/
└── seed/
```

---

## 4. Tài liệu hiện có

Repository chứa:

```text
docs/
├── FRONTEND_API_INTEGRATION.md
├── SAGA_BACKEND_ARCHITECTURE.md
├── SAGA_BACKEND_REQUIREMENTS_DEPENDENCIES_CONSTRAINTS.md
├── SAGA_CURRENT_STATE.md
└── SAGA_DECISION_LOG.md
```

`README.md` là điểm vào cho người dùng repository.

---

## 5. Infrastructure connectivity

### Local

Profile `local` đã cấu hình kết nối tới các service DEV qua environment variables (file `.env`).

**Local infrastructure connectivity = PASS**

| Service | Vai trò | Trạng thái |
| --- | --- | --- |
| MySQL | Source of Truth | CONFIGURED / CONNECTED |
| Neo4j | Graph Read Model / Projection | CONFIGURED / CONNECTED |
| Redis / Valkey | Cache / Rate Limit / Ephemeral Realtime Support | CONFIGURED / CONNECTED |
| RabbitMQ | Async Message Broker | CONFIGURED / CONNECTED |

Đây là **CONFIGURED / CONNECTED**, không phải business feature đã implement.

### Railway DEV

Profile `dev` (`application-dev.properties`) đã được chuẩn bị cho Railway.

- **Railway DEV profile = CONFIGURED**
- **Railway deployment = NOT YET DEPLOYED**

Railway không dùng file `.env`. Credential được inject bằng Environment Variables. Railway tự inject `PORT`; ứng dụng bind `server.port=${PORT:8080}`. Healthcheck dự kiến: `GET /actuator/health`.

Các phần sau **chưa tồn tại**: MySQL schema, Flyway migration, Neo4j node/relationship/projection, Redis cache/rate-limit key, RabbitMQ queue/exchange/binding/DLQ.

---

## 6. Feature đã triển khai

Chưa có.

Cụ thể, các phần sau **CHƯA TRIỂN KHAI**, dù dependency/package và connectivity đã tồn tại:

- registration;
- login;
- Google OAuth flow;
- role resolution;
- xử lý session/token;
- MySQL schema;
- Flyway migration;
- kết nối Jira;
- kết nối GitHub;
- Webhook Jira/GitHub;
- RabbitMQ topology;
- webhook inbox;
- transactional outbox;
- idempotency store;
- Redis Rate Limiting;
- Redis realtime fan-out;
- Neo4j schema;
- graph projection;
- graph query API;
- assessment algorithm;
- SSE endpoint;
- versioned realtime event;
- notification feature;
- cấu hình deployment.

---

## 7. Decision kiến trúc đã chốt

Xem `SAGA_DECISION_LOG.md`.

Các Decision quan trọng hiện tại:

- Modular Monolith trước.
- MySQL là Source of Truth.
- Neo4j là Graph Read Model có thể rebuild.
- Không dual-write trực tiếp MySQL + Neo4j.
- RabbitMQ cho xử lý event bất đồng bộ.
- Webhook GitHub/Jira cho inbound event.
- SSE cho Backend → Browser realtime.
- Baseline Spring MVC + Java 21.
- Redis là ephemeral, không phải durable truth.
- MongoDB không thuộc initial baseline.
- Backend mới không dùng Cognito làm primary authentication platform.

---

## 8. Decision còn mở / chưa chốt

### Chiến lược authentication session/token

Chưa chốt.

Chi tiết vẫn cần thiết kế tường minh:

- server-side session so với mô hình access/refresh token;
- chiến lược cookie;
- hành vi refresh/revocation;
- mô hình CSRF;
- quy tắc session theo account/device;
- hành vi logout.

Không implement chiến lược token/session chỉ vì thư viện làm việc đó tiện.

### Định danh FPT và role resolution

Business rule đã được thảo luận nhưng phải được formalize trước khi implement.

Quy tắc bảo mật quan trọng:

> Nếu email không phân loại được một cách chắc chắn, không tự promote thành Lecturer.

Identity unknown/ambiguous phải fail closed hoặc đi vào verification workflow.

### Database schema

Chưa thiết kế.

Chưa có Flyway `V1` migration.

### Graph schema

Node label, relationship type, uniqueness constraint, projection version, giới hạn traversal, và chiến lược rebuild **chưa chốt**.

### Assessment model

Template assessment theo subject/course, trọng số, và công thức đóng góp **chưa chốt**.

### Public API contract

Hiện không có endpoint nào được cam kết cho Frontend.

### Realtime contract

SSE đã được chốt về mặt kiến trúc, nhưng endpoint name, event type, version field, recovery API, heartbeat, và connection policy vẫn **TBD / Chưa chốt**.

---

## 9. Milestone tiếp theo

Thứ tự khuyến nghị:

```text
1. Freeze repository documentation/rules
2. Verify generated Maven dependency baseline
3. Design Authentication + Authorization
4. Design MySQL identity/academic core schema
5. Create Flyway V1 migration
6. Implement first auth/account vertical slice
7. Define public API/error contract
8. Design Jira/GitHub integration model
9. Implement webhook ingress + idempotency
10. Introduce RabbitMQ topology
11. Add transactional outbox
12. Design Neo4j graph model/projection
13. Add versioned SSE delivery
14. Implement Continuous Assessment pipeline
15. Load/failure testing
```

Thứ tự chỉ được đổi khi có nhu cầu dự án tường minh.

---

## 10. Definition of Done cho giai đoạn Bootstrap

Bootstrap được coi là xong khi:

- architecture skeleton đã thống nhất;
- README và tài liệu cốt lõi đã được điền;
- dependency baseline đã được review;
- file generate của project vẫn sạch;
- repository không chứa secret;
- repository có thể push an toàn lên GitHub;
- thành viên team hiểu ownership của từng package trước khi viết business code.

---

## 11. Quy tắc cập nhật

Mỗi khi một feature có ý nghĩa được shipped, cập nhật file này trong cùng PR.

Ví dụ:

```text
Auth local login implemented
Google OAuth implemented
Flyway V1 shipped
RabbitMQ topology shipped
Webhook idempotency shipped
Neo4j projection shipped
SSE v1 contract shipped
```

Không để file này vẫn ghi “chưa implement” sau khi feature đã shipped, và không đánh dấu đã implement chỉ dựa trên planning/documentation.
