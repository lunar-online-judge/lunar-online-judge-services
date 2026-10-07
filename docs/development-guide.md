# 开发指南（起手文档）

本文档回答"我要开始写代码，从哪开始、按什么约定写、写完怎么自测"。通读一遍即可上手第一个服务；细节以链接到的文档为准，本文不重复。

## 1. 阅读顺序

| 顺序 | 文档 | 什么时候读 |
|---|---|---|
| 1 | `README.md` | 技术栈、版本、当前决策 |
| 2 | `docs/architecture.md` | 动手前通读：服务边界、事件、状态机、安全边界 |
| 3 | `docs/services.md` | 确认每个服务做什么、已实现到哪一步 |
| 4 | `docs/api.md` | 写任何接口前：契约、令牌、错误码 |
| 5 | `docs/exam-process.md` | 实现考试/竞赛业务逻辑时必读；§10 待确认决策不能自行假设 |
| 6 | `docs/deployment.md`、`docs/middleware.md` | 跑环境、连中间件时 |
| 7 | 本文 | 开工 |

## 2. 开发环境

- JDK 21（`JAVA_HOME` 必须指向它，否则编译失败）、Maven 3.9+、Docker Compose v2；
- 构建验证：`mvn -DskipTests package`；
- 基础设施与服务启动、端口、冒烟验证：见 `docs/deployment.md`；
- 需要 Redis/RocketMQ 的功能（限流、事件）尚未接入 Starter，本地开发暂不需要它们运行。

## 3. 代码结构与约定

- 一个服务一个 Maven 模块，包根 `io.lunar.<service>`，子包分层：
  - `api/`：REST 控制器与请求/响应 DTO（用 record）；
  - `domain/`：领域模型、状态机、业务规则（不依赖 Spring Web）；
  - `infra/`：数据库访问、MQ 生产/消费、外部客户端适配器。
- 响应统一用 `lunar-common` 的 `ApiResponse.success/failure(requestId, data, error)`；`X-Request-Id` 由 `RequestIdFilter` 自动透传，控制器取请求头传入响应。
- 错误必须带稳定 `code`（大写下划线，`<域>_<原因>`），初始注册表见 `docs/api.md`，新码先登记再使用。
- 写操作必须幂等：HTTP 用 `Idempotency-Key`，事件消费用 `eventId` 去重；
- 服务之间只经 REST 与 RocketMQ 事件协作，禁止跨 schema 查库；
- 绝不在 Spring 容器内执行用户代码（评测一律走 `JudgeEngine` 抽象）；
- 学生密码只存 BCrypt 哈希，不写日志/事件/审计；
- 每完成一个阶段，更新 `docs/services.md` 对应服务的"当前状态"和"下一步"两行——文档与代码同步是这个仓库的纪律。

## 4. 实现顺序

与 `docs/architecture.md` §15 一致：**auth-service → 各服务 schema/MyBatis-Plus → contest/problem/submission 领域与状态机 → RocketMQ Outbox → GoJudgeAdapter → WebSocket → 风险与审核 → 查重 → 加固**。

进度（2026-10-07）：auth/contest/problem/submission 核心链路与 RocketMQ 事件总线、gateway 路由已落地（gateway 选型已定 Spring Cloud Gateway）；anti-cheat/media/audit、WebSocket、成绩读模型未启动。

当前焦点是 **auth-service**，实现路径见第 5 节。

## 5. 第一站：auth-service 实现路径

> **进度（2026-10-05）**：第 1–8 步已完成并通过真实库实测（SecurityConfig、MyBatis-Plus、RS256 JWT + JWKS、真实登录、失败限流、种子管理员）；第 9 步（服务间接口）按计划随 contest-service 开发。限流仍为内存实现，Redis 接入后迁移（第 8 步 TODO 保留）。

前置现状：依赖已就位（web、validation、security、oauth2-resource-server、postgresql、flyway）；`src/main/resources/db/migration/` 已有 `V1__auth.sql`（`auth_schema.users` 表）和 `V2__student_account_fields.sql`；`AuthController` 是 mock 登录。端口 8081。

按顺序做，每步可独立验证：

1. **打通数据库迁移**：`docker compose -f docker/compose.core.yml up -d postgres`，启动服务并设置 `FLYWAY_ENABLED=true`，确认两张迁移执行、`auth_schema.users` 建表。
2. **接入 MyBatis-Plus**：pom 加 `mybatis-plus-spring-boot3-starter`（版本已在根 POM dependencyManagement 管理）和 `spring-boot-starter-jdbc`（MyBatis-Plus 需要 DataSource 事务支撑），配置数据源沿用现有 `application.yml`。
3. **安全配置基线**：新增 `SecurityConfig`——`BCryptPasswordEncoder` Bean；`SecurityFilterChain` 放行 `POST /api/v1/auth/login` 与 `GET /api/v1/auth/jwks`，其余 `authenticated()`；无状态会话（`STATELESS`）；关闭 CSRF。这一步完成后服务不再是"全 401 + 随机密码"状态。
4. **用户加载**：按 `username` 查 `auth_schema.users`（MyBatis-Plus Mapper），校验 `status='ACTIVE'`。
5. **JWT 签发**：用 oauth2-resource-server 自带的 `oauth2-jose`（`JwtEncoder` + `NimbusJwtEncoder`）生成 RS256 令牌；RSA 密钥对从环境变量/配置注入（本地 dev 可用配置文件默认值，生产必须外部注入）；claims 契约见 `docs/api.md`"认证令牌"节；TTL 默认 30 分钟。
6. **JWKS 端点**：`GET /api/v1/auth/jwks` 输出公钥 JWKSet，供各资源服务用 `jwk-set-uri` 本地验签。
7. **改造登录接口**：真实校验（用户存在 + BCrypt 匹配 + 状态 ACTIVE），按 `docs/api.md` 契约返回 `accessToken/tokenType/expiresAt/role/passwordChangeRequired`；失败返回 `AUTH_INVALID_CREDENTIALS` 等错误码（当前 mock 响应字段比契约少两项，以此为准补齐）。
8. **登录失败限流**：连续失败短暂锁定（约束见 `docs/architecture.md` §2.1.2）。Redis 未接入前先用内存计数并标注 TODO，接入后迁 Redis。
9. **服务间接口**：创建学生账号、重置密码（供 contest-service 调用，幂等 + 审计），随 contest-service 开发一起做。

自测方式见 `docs/deployment.md`"冒烟验证"。建议同时把 `springdoc-openapi-starter-webmvc-ui` 加进 pom（版本已管理），获得 Swagger UI 调试页面。

## 6. 测试约定（起步）

- 各服务 pom 加 `spring-boot-starter-test`（Boot BOM 管理，当前所有模块都没有）；
- 最低要求：控制器层 `@WebMvcTest` + MockMvc 覆盖成功/失败/未认证三条路径；领域逻辑（状态机、校验规则）写纯 JUnit 单测，不启动 Spring；
- 数据库集成测试后续用 Testcontainers 引入，接入前不要写依赖本地库状态的测试。

## 7. 待确认决策与开发的阻塞关系

`docs/exam-process.md` §10 的六项待确认不阻塞当前焦点（auth-service），但在实现对应模块前必须先拿到结论：

| 待确认项 | 阻塞 |
|---|---|
| 考试结束后评测任务（继续/终止） | submission/judge 的结算逻辑 |
| 考试开始后改题范围 | problem 版本更新规则 |
| 随机公平约束（难度/总分平衡） | contest 随机组卷算法 |
| 重复登录后旧设备能否重登 | auth 会话/令牌失效策略（含 TTL 是否延长） |
| 成绩发布顺序（老师初审/管理员终发布） | contest 成绩状态机流转 |
| 成绩可见范围配置 | contest 成绩查询接口 |

其余空缺（contest/problem/submission/judge 的表结构设计）按实现顺序在做每个服务时补充设计并落 Flyway 迁移，设计写进 `docs/architecture.md` 对应章节或 `docs/services.md`，不新开碎片文档。
