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
- 需要 Redis/RocketMQ 的功能**已接入**（auth 的登录失败限流用 Redis，submission/anti-cheat/judge 用 RocketMQ）；两者都是可选依赖——本地不启动它们也能跑通主干（限流 fail-open 放行、Outbox 事件暂不投递）。

## 3. 代码结构与约定

- 一个服务一个 Maven 模块，包根 `io.lunar.<service>`，子包分层：
  - `api/`：REST 控制器；`api/dto/`：请求/响应 DTO（用 record）；
  - `domain/`：领域模型、状态机、业务规则（不依赖 Spring Web）；`domain/dto/`：领域层读模型（视图/分页/结果，用 record）；
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

进度（2026-10）：auth / contest / problem / submission 核心链路、RocketMQ 事件总线、gateway 路由、anti-cheat 与 audit 均已落地，并补齐 Nacos 注册 + 配置中心与 Actuator 健康检查。**仍未实现**：WebSocket、成绩读模型（排名/部分分）、随机题目分配、媒体/截图链路、代码查重。逐项现状见 `docs/services.md` 与 `docs/architecture.md` §15。

后续开发的入手点见 `docs/services.md`（各服务"下一步"）与 `BACKEND_IMPROVEMENT_PLAN.md`（BE-01～BE-13，含优先级与验收）。

## 5. 第一站：auth-service 实现路径（**已完成，保留作历史记录**）

> **状态（2026-10）**：第 1–9 步全部完成——真实登录（BCrypt）、RS256 JWT + JWKS、Redis 失败限流（Lua 原子计数 + TTL + fail-open）、学生账号服务间接口都已落地并有单测。以下步骤保留为当时的实施路径，新服务可参照同样的推进方式。

前置现状（当时）：依赖已就位（web、validation、security、oauth2-resource-server、postgresql、flyway）；`src/main/resources/db/migration/` 已有 `V1__auth.sql`（`auth_schema.users` 表）和 `V2__student_account_fields.sql`；`AuthController` 起初是 mock 登录（本路径第 7 步将其改造为真实校验）。端口 8081。

按顺序做，每步可独立验证：

1. **打通数据库迁移**：`docker compose -f docker/compose.yml up -d postgres`，启动服务并设置 `FLYWAY_ENABLED=true`，确认两张迁移执行、`auth_schema.users` 建表。
2. **接入 MyBatis-Plus**：pom 加 `mybatis-plus-spring-boot3-starter`（版本已在根 POM dependencyManagement 管理）和 `spring-boot-starter-jdbc`（MyBatis-Plus 需要 DataSource 事务支撑），配置数据源沿用现有 `application.yml`。
3. **安全配置基线**：新增 `SecurityConfig`——`BCryptPasswordEncoder` Bean；`SecurityFilterChain` 放行 `POST /api/v1/auth/login` 与 `GET /api/v1/auth/jwks`，其余 `authenticated()`；无状态会话（`STATELESS`）；关闭 CSRF。这一步完成后服务不再是"全 401 + 随机密码"状态。
4. **用户加载**：按 `username` 查 `auth_schema.users`（MyBatis-Plus Mapper），校验 `status='ACTIVE'`。
5. **JWT 签发**：用 oauth2-resource-server 自带的 `oauth2-jose`（`JwtEncoder` + `NimbusJwtEncoder`）生成 RS256 令牌；RSA 密钥对从环境变量/配置注入（本地 dev 可用配置文件默认值，生产必须外部注入）；claims 契约见 `docs/api.md`"认证令牌"节；TTL 默认 30 分钟。
6. **JWKS 端点**：`GET /api/v1/auth/jwks` 输出公钥 JWKSet，供各资源服务用 `jwk-set-uri` 本地验签。
7. **改造登录接口**：真实校验（用户存在 + BCrypt 匹配 + 状态 ACTIVE），按 `docs/api.md` 契约返回 `accessToken/tokenType/expiresAt/role/passwordChangeRequired`；失败返回 `AUTH_INVALID_CREDENTIALS` 等错误码（当时 mock 响应字段比契约少两项；本步已完成）。
8. **登录失败限流**：连续失败短暂锁定（约束见 `docs/architecture.md` §2.1.2）。**最终实现**为 Redis + Lua（原子计数 + TTL），Redis 不可用时 fail-open 放行（见 `docs/middleware.md`）。
9. **服务间接口**：创建学生账号、重置密码（供 contest-service 调用，幂等 + 审计）——**已完成**，路由为 `/api/v1/internal/students`（鉴权见 `docs/api.md` 端点总表）。

自测方式见 `docs/deployment.md`"冒烟验证"。建议同时把 `springdoc-openapi-starter-webmvc-ui` 加进 pom（版本已管理），获得 Swagger UI 调试页面。

## 6. 测试约定（起步）

- 各服务 pom 加 `spring-boot-starter-test`（Boot BOM 管理；**现已全部加上，`lunar-common` 也有了**）；
- 最低要求：控制器层 `@WebMvcTest` + MockMvc 覆盖成功/失败/未认证三条路径；领域逻辑（状态机、校验规则）写纯 JUnit 单测，不启动 Spring；
- 数据库集成测试后续用 Testcontainers 引入，接入前不要写依赖本地库状态的测试。

## 7. 待确认决策与开发的阻塞关系

`docs/exam-process.md` §10 的六项待确认不阻塞**当时**的焦点（auth-service，已完成），但在实现对应模块前必须先拿到结论：

| 待确认项 | 阻塞 |
|---|---|
| 考试结束后评测任务（继续/终止） | submission/judge 的结算逻辑 |
| 考试开始后改题范围 | problem 版本更新规则 |
| 随机公平约束（难度/总分平衡） | contest 随机组卷算法 |
| 重复登录后旧设备能否重登 | auth 会话/令牌失效策略（含 TTL 是否延长） |
| 成绩发布顺序（老师初审/管理员终发布） | contest 成绩状态机流转 |
| 成绩可见范围配置 | contest 成绩查询接口 |

其余空缺（contest/problem/submission/judge 的表结构设计）按实现顺序在做每个服务时补充设计并落 Flyway 迁移，设计写进 `docs/architecture.md` 对应章节或 `docs/services.md`，不新开碎片文档。

## 8. 构建与启动的两个硬坑（2026-11 补）

```bash
# -pl 不会自动安装父 POM 与兄弟模块，否则报 "Could not find artifact io.lunar:lunar-common"
mvn -N install -DskipTests
mvn install -DskipTests -pl lunar-common
```

- 端口与数据库端口**已有默认值**（各服务 8080–8089、`POSTGRES_PORT=5433`），只需注入密钥类配置：
  `AUTH_JWT_PRIVATE_KEY`/`AUTH_JWT_KEY_ID`（auth）、`REDIS_PASSWORD`（auth/judge，默认 `change-me-redis`）、
  `GO_JUDGE_BASE_URL`（judge）、`AI_PROVIDER_*` + `AI_EGRESS_APPROVED`（ai，默认不启用）。
- **改完 `lunar-common` 必须 `mvn install` 并重启所有服务**，否则服务跑旧 jar（这个坑造成过三次假故障）。

## 9. 加功能前必须满足（防止再次横向膨胀）

2026-11 的审计结论是：**复杂度不是来自单个服务，而是横向铺得太宽**——两个后期加入的服务占了 37% 的代码、几乎零测试。因此加东西前先过这一遍：

1. **新服务/新概念必须先有测试**；没有测试的服务不允许进入默认运行清单。
2. **新事件类型必须同时给出消费者与幂等策略**（只生产不消费的事件等于往 broker 丢垃圾）。
3. **新端点必须明确四件事**：所需角色、资源范围（越权是 404 还是 403）、失败码、是否写审计。
4. **上线阻断项未清零前不加新能力**（清单见 `BACKEND_IMPROVEMENT_PLAN.md`）。
5. **优先复用 lunar-common 的既有能力**（`AccessScope`、`ApiResponse`、`ProductionConfigGuard`、`EventEnvelope`），不要在服务里复制一份——Outbox 与 ScopeGuard 就曾各复制了 3~5 份。

## 10. 已知的坑（都踩过，写下来避免重犯）

- **`spring-boot-starter-web` 不传递 bean-validation**：用到 `@Valid`/`@Pattern`/`@Size` 的服务必须显式声明 `spring-boot-starter-validation`，否则校验**静默失效**（400 变 200、脏数据落库）。
- **MyBatis-Plus 分页插件不注册时 `selectPage` 不带 LIMIT**，`total` 恒为 0；`pageSize` 传负数等于全表导出。
- **JWT 的 `aud` 单元素会被解成 String**：用 `Jwt#getAudience()`，不要用泛型校验器强转（会 500）。
- **`Start-Job` 的子进程随父进程退出**：后台起服务要用工具提供的后台作业。
- **Windows 下 bash 脚本必须保持 ASCII**：Git Bash 里 curl 传中文会变成非法 JSON。
- **`$pid` 在 PowerShell 里是只读自动变量**，不要赋值。
- **PowerShell 里用正则做批量替换要当心回溯**：`(?:.*\r?\n)*?` 这类模式在大文件上会挂死，优先用逐行过滤或 `String.Replace`。
