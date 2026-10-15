# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 结构；版本号语义化。
当前阶段为开发期，服务端能力按实现顺序逐步落地（见 `docs/development-guide.md`）。

## [Unreleased]

### 重构

- **全局异常处理统一到 lunar-common**（原来 6 份重复实现，其中 4 份内嵌在 Controller 里）
  - 新增 `ApiException` 抽象基类（`code` + `httpStatus`），六个域异常（`AuthException` / `UserException` / `ContestException` / `ProblemException` / `SubmissionException` / `ReviewException`）改为继承它，删掉各自复制的字段与访问器
  - 新增 `ApiExceptionHandler`（`@RestControllerAdvice`，由 `WebCommonAutoConfiguration` 注册为 Bean）：处理所有 `ApiException` 子类 + `MethodArgumentNotValidException`，错误响应格式全平台一致
  - 删除 auth / contest / submission 三个独立的 `ApiExceptionHandler` 类，以及 `MonitorController` / `AuditController` / `ProblemController` / `InternalStudentController` 内嵌的 Handler
  - 新增 `RequestIds.currentRequestId()` 取代 6 份复制的 `currentRequestId()`；新增 `Pagination.DEFAULT_PAGE` / `DEFAULT_PAGE_SIZE` 与 `Role.AUTHORITY_PREFIX` 收敛散落的魔法字面量
- **按 java-code-style 规范整改全部服务**（`C:\Users\10559\.agents\skills\java-code-style`）
  - 清除内联全限定名、单字母/无意义变量名（含纯类型名 `entity`）、通配符 import、未使用 import、类型不明显的 `var`、超 120 字符长行
  - 分页列表与重置密码响应由 `Map<String, Object>` 改为具名 record（JSON 字段与取值不变）；`ContestService.list` / `ProblemService.list` 不再把 MyBatis `Page` 泄漏给调用方
  - 提取魔法值常量：判题 verdict 与沙箱状态名、题目默认限制、outbox 批量大小、详情截断长度等
  - 风格扫描器命中 189 处 → 2 处（剩余 2 处为 `ApiResponse` 的 `data` 字段：`docs/api.md` 已定的 wire 契约名，按"项目约定优先"保留）；103 个测试全绿
  - 控制器内嵌的请求/响应 record 全部抽成独立类型，落在各服务的 `api.dto` 包（21 个：auth 6 / contest 7 / problem 2 / submission 2 / anti-cheat 2 / judge 1 / audit 1），Controller 只留端点与装配；`StudentResponse.of` / `ContestResponse.of` 由包级改为 `public` 以跨包调用，`docs/development-guide.md` 的包结构说明同步补上 `api/dto/`
  - domain 层 Service 内嵌的 22 个 record（视图/分页/结果）同样抽成独立类型，收进各服务的 `domain.dto` 包（auth 3 / contest 7 / problem 6 / submission 1 / anti-cheat 3 / audit 2）；原先靠同包可见性写 `ProblemService.ProblemView` 这类限定名的引用方（10 个 Controller、3 个 `api.dto`、8 个测试）一律改为显式 import，同时清掉 4 个失效 import——JSON 字段与行为不变；嵌套类型扫描 58 处 → 36 处（剩余为 `XxxException` 子类与 config/infra 辅助类型，本轮不动），`docs/development-guide.md` 包结构说明补上 `domain/dto/`

### 修复

- **判题 verdict 词表漂移导致真机结果被误判**：`GoJudgeAdapter` 输出短名（TLE/MLE/OLE/RE），而 `SubmissionCreatedConsumer` 白名单与 `SubmissionService.TERMINAL_STATUSES` 只认长名，真机下所有超时/超内存/超输出/运行时错误都会被归一成 `SYSTEM_ERROR`（Mock 引擎只产 ACCEPTED，测试与本地演示均未暴露，且消费者此前零测试覆盖）
  - 词表收敛为 `lunar-common` 的 `JudgeVerdict` 枚举（枚举名即事件与 `submissions.status` 的契约值），成为全平台唯一权威：adapter 直接映射枚举、消费者用 `parse()` 判定、submission 的终态校验由枚举派生；三份手写字符串清单（adapter 的 8 个常量、消费者的白名单 switch、`TERMINAL_STATUSES`）全部删除
  - `docs/services.md` 中与 `docs/architecture.md` §8、库表列注释相矛盾的 `TLE、MLE、RE、OLE` 表述已改正
  - 新增 `SubmissionCreatedConsumerTest` 钉住发布契约：契约长名原样通过，短名与 `PENDING` 兜底 `SYSTEM_ERROR`
  - 无需数据迁移：短名在落库前已被归一，`submissions.status` 中不存在 `TLE` 等取值

- **judge-service 设置 `GO_JUDGE_BASE_URL` 时启动失败**（既有缺陷，全量 API 测试暴露）：`GoJudgeAdapter` 同时有容器装配构造器（带 `@Value`）与单测注入构造器（包级 `GoJudgeTransport`），前者未标注 `@Autowired`，Spring 无法选择 → `Failed to instantiate: No default constructor found`。后果是真实 go-judge 链路从未真正启动过（也解释了 verdict 词表漂移为何长期未被发现）。补 `@Autowired` 后真实引擎可正常启用

- **添加学生响应缺少文档声明的 `passwordChangeRequired`**（全量 API 测试发现）：`docs/api.md"添加学生"` 的响应契约含该字段，实现返回的却是名单视图 `CandidateView`（含加入人/加入时间、无该字段）。现为 `add()` 引入与文档一致的 `EnrollmentResult`（studentId/studentNo/username/displayName/enrollmentStatus/passwordChangeRequired）：新建账号时取 auth 响应值（true），账号复用（学生已在其他竞赛报名）时为 false——本次未下发任何密码；加入人/加入时间仍由名单查询返回，与文档分工一致。`EnrollmentServiceTest` 新增两条断言覆盖新建与复用两种分支

### 新增

- **服务间通信改走 Nacos 服务发现**（`docs/middleware.md`"Nacos"节）
  - 8 个服务启动注册到 Nacos `DEFAULT_GROUP`；依赖收敛为根 POM 的 `spring-cloud-alibaba.version=2025.0.0.0`（与 spring-cloud 2025.0.3 同代）+ 各服务 `spring-cloud-starter-alibaba-nacos-discovery`
  - 网关 6 条路由由 `http://localhost:端口` 改为 `lb://<服务名>`（`*_SERVICE_URI` 可覆盖回固定地址排障），因此 gateway 额外引入 `spring-cloud-starter-loadbalancer`
  - 服务间三个客户端（`AuthInternalClient` / `UpstreamClient` / `AuditClient`）改为注入 `@LoadBalanced RestClient.Builder`，baseUrl 默认值由 `http://localhost:端口` 改为服务名；该 Builder 由新增的 `LoadBalancedRestClientAutoConfiguration`（lunar-common）统一提供，条件为负载均衡器在类路径，不按服务名寻址的服务整体退避
  - 注册 IP 固定 `127.0.0.1`（`NACOS_DISCOVERY_IP` 可覆盖）：自动探测会误选本机虚拟网卡（实测 VMware VMnet1 的 192.168.48.1），故与 RocketMQ `brokerIP1=127.0.0.1` 采取同一策略
  - `fail-fast=false`：Nacos 不可达时服务照常启动（仅不注册），`NACOS_FAIL_FAST=true` 切回严格模式
  - JWKS 验签链路（`AUTH_JWK_SET_URI`）**保持固定地址**：auth 是唯一签发方且单实例，改造需自建 `JwtDecoder` 替换 Boot 自动配置，收益不抵风险，列为后续项
  - 实测结论：SCA 客户端 3.0.3 + Nacos 服务端 2.4.2 + public 命名空间**注册与订阅均正常**（SCA 文档对此组合有升级服务端的提示，本项目未触发），因此未升级镜像、未改命名空间

- **登录失败限流迁到 Redis**（`docs/middleware.md`"Redis"节）
  - auth-service 引入 `spring-boot-starter-data-redis`；`LoginRateLimiter` 由单实例内存 `ConcurrentHashMap` 改为 Redis 存储（`auth:login:fail:*` / `auth:login:lock:*`），公开方法签名不变、`AuthService` 零改动
  - 计数与锁定用 **Lua 脚本原子执行**（INCR → 首次设 PEXPIRE → 达阈值写锁标记并重置计数）：拆成多条命令时"INCR 成功但没设上 TTL"会留下永不过期的计数键，即旧实现的两个缺陷之一；另一处修复是锁到期后计数随锁定重置，不再"下次失败立刻再锁"
  - **fail-open**：Redis 不可用时放行登录并打 warn（限流属短期状态层，其不可用不应让全站无法登录）；连接/命令超时设短（500ms / 1000ms）以保证快速失败
  - 实测：实例 A 连续失败 5 次后，**另一个独立 JVM 实例 B 首次尝试即 429**（内存实现下不可能）；停掉 Redis 后登录 0.13s 返回 200 且日志有 warn，恢复后限流立即生效；锁标记 TTL 与配置的 5 分钟一致
  - `LoginRateLimiterTest` 改为对真实 Redis 跑（TTL 过期与多实例共享由服务端控制，假时钟覆盖不了），Redis 不可达时跳过而非失败；新增 `LoginRateLimiterContextTest` 装配守卫，把"两个构造器漏标 `@Autowired`"这类启动期失败拦在测试阶段（`GoJudgeAdapter` 踩过同一坑，已实测移除注解后该守卫会失败）
  - **幂等键与会话状态有意不迁**（理由写入 `docs/middleware.md`）：幂等键以 DB 唯一约束为权威——Redis 抖动或 TTL 过期会导致同键重试新建 submission 并重复触发评测，违反 `docs/api.md` 契约；会话 `status` 是提交通道且 TERMINATED 为终态，Redis 全丢会让超时扫描误判在考会话为 EXPIRED

- **8 个服务引入 Spring Boot Actuator，暴露 `/actuator/health`**（`docs/deployment.md`"健康检查"节）
  - 只通过 HTTP 暴露 `health`（其余端点保持不可达），只返回 `UP`/`DOWN`（`show-details: never`）；health 匿名可访问——否则编排与运维探测必须带令牌，加了等于没用
  - **健康语义按依赖强度区分**：PostgreSQL 计入健康（硬依赖）；**Nacos 与 Redis 不计入**——`NACOS_FAIL_FAST=false` 与限流 fail-open 的语义就是"它们不可用时服务照常工作"，计入会让编排误杀可用实例（auth-service 显式关闭 `management.health.redis.enabled`）
  - judge-service 此前无 `SecurityConfig`，靠 Spring Security 默认行为（全端点 401），会把 health 一并挡掉；新增最小 `SecurityConfig` 把原有基线显式化（用 `HttpStatusEntryPoint` 保持 401 语义而非默认回退 403）并放行 health
  - gateway 类路径无 spring-security，health 直接可达；注意它经 OpenResty 对外可达（仅状态、无细节），其余服务的 health 不经过网关
  - `docs/deployment.md` 冒烟验证新增健康检查步骤，并更新"无健康检查端点"的已知限制表述

- **接入 Nacos 配置中心（Spring Cloud Alibaba Nacos Config）**
  - 8 个服务统一引入 config starter + `spring.config.import=optional:nacos:<服务名>.yml`；**只托管"运行时可变项"**——部署期配置（端口、连接串、服务地址、go-judge 超时）与密钥仍留在 git 与环境变量，自举参数（Nacos 自身地址与命名空间）必须留本地
  - 真正热更新的两项：`auth.login.max-failures` / `lock-minutes`（考试期间可临时调紧锁定策略）、`exam.session.heartbeat-timeout-minutes`（网络抖动时放宽）。`LoginRateLimiter` 与 `ExamSessionService` 改为 `@ConfigurationProperties`（可变 JavaBean）+ **每次调用时读取**——`@Value` 不参与刷新，构造期读入等于固化
  - 调度间隔（`outbox.publish-interval-ms`、`exam.status.sweep-interval-ms`）同样纳管，但 `@Scheduled` 在启动时解析，明确标注"重启生效"
  - **前置修复**：`docker/compose.platform.yml` 给 nacos 挂上命名卷 `nacos-data`——此前无卷，写入的配置在容器重建后会全部丢失（已实测强制重建后配置仍在）
  - **坑一（关键）**：配置必须放非 public 命名空间。nacos-client 3.x 默认按 `public` 取配置，而 Nacos 2.x 的 public 命名空间真实 ID 是空字符串，gRPC 拉取返回 `not-found`；服务端 `config-client-request.log` 中 `tenant=public → not-found` 与 `tenant=null → ok` 两行是直接证据。HTTP API 会做命名空间别名，**用 curl 测不出来**，客户端侧只表现为 `[Nacos Config] config[…] is empty`。改用独立命名空间 `lunar-dev`（服务发现不受影响，继续留在 public）
  - **坑二**：`spring.config.import` 导入的配置**优先级低于本地 yml**——被托管的键若同时在本地 yml 里定义（auth 的 `auth.login.*` 原本就是），Nacos 的值会被永远盖住。已清理本地重复项，离线兜底改由属性类的字段默认值承担
  - 实测热更新：Nacos 里 `max-failures` 由 2 改为 3 后**未重启即生效**（失败 2 次时第 3 次仍可尝试、失败 3 次后第 4 次锁定，与改前的"2 次即锁"形成对照），日志链路 `Receive Nacos config change` → `Refresh keys changed: [auth.login.max-failures]` 完整可见
  - 新增 `LoginRateLimiterContextTest.propertiesBindFromEnvironment` 钉住 `@DurationUnit(MINUTES)` 语义（若误按毫秒绑定，锁定会静默变成 3 毫秒）
  - 已知瑕疵：暂无托管配置的服务（problem / judge / audit / gateway）启动时会打一条 `config[…] is empty` 警告，属预期；Nacos 2.4.2 内嵌 Derby 下命名空间列表接口有 SQL 语法错误（反引号），控制台命名空间页可能报错，不影响客户端读写

- **Nacos 服务端升级到 3.0.3（与捆绑客户端同补丁线）**——不必再靠自定义命名空间绕开 2.x 的坑
  - 起因：SCA 2025.0.0.0 捆绑的 nacos-client 3.0.3 按 Nacos 3.x 语义解析命名空间，服务端停在 2.4.2 时**配置拉取静默失败**（`public` 在 2.x 的真实 ID 是空字符串，gRPC 返回 not-found）。上一轮用独立命名空间 `lunar-dev` 绕过，本轮升级服务端后默认命名空间即可用，**配置与服务发现都回到 `public` + `DEFAULT_GROUP`**
  - 选 3.0.3 的理由：与客户端同补丁线（最适配）、本地镜像已有缓存无需下载、3.0.x 尚未引入 3.3 的"auth 默认开启"变更
  - compose 变更：镜像 `v2.4.2 → v3.0.3`；补上 3.x 启动脚本**强制要求**的 `NACOS_AUTH_TOKEN` / `NACOS_AUTH_IDENTITY_KEY` / `NACOS_AUTH_IDENTITY_VALUE`（开发用值，生产须换强随机值）；控制台映射到宿主机 **8849**（容器内是 8080，与网关端口冲突）；数据卷换成 `nacos-data-v3`（3.x 的 Derby 结构与 2.x 不兼容）
  - 3.x 鉴权模型：刻意不设 `NACOS_AUTH_ENABLE`，沿用镜像默认的 `auth.enabled=false` → **客户端 API 免凭据**（与 2.x 一致）；**控制台另有自己的登录且取消了默认 `nacos/nacos`**，首次访问需初始化管理员密码
  - 3.x 的 API 变化：v1 配置 API 已移除、v3 admin API 需控制台凭据 → 配置发布改走**官方客户端**：新增 `docker/nacos/config/*.yml`（配置内容的 git 真源，回应"配置要跟 git 走"）与 `docker/nacos/publish-configs.sh`（推送脚本，免鉴权、不受 HTTP API 版本差异影响）；v1 命名 API 仍可用，`docs/deployment.md` 的冒烟命令不受影响
  - 实测：8 个服务注册 + 4 个服务加载配置 + 热更新（改 Nacos 不重启即生效，日志 `Receive Nacos config change` → `Refresh keys changed: [auth.login.max-failures]`）+ 142 条端到端断言全绿
  - 过程记录：升级前先用独立容器对 3.0.3 做探测（端口/鉴权/命名空间/发布途径），确认无阻塞后才切生产编排；期间发现并绕开两个 3.x 细节——控制台/管理 API 需登录、v1 配置 API 已移除

### 计划中

- RocketMQ Outbox 发布器与消费（接通 submission → judge 事件链路，替代判决回写过渡端点）
- gateway-service 路由选型与实现；anti-cheat / media / audit 服务落地
- WebSocket 推送（考试状态、评测结果、风险告警）
- Redis 其余用途（令牌吊销、接口频控、会话在线状态镜像、评测短期锁、截图频控）；登录限流已迁，幂等键与会话状态经评估不迁（见上）
- 题目归属竞赛校验（依赖随机分配表）与提交频率阈值（待 exam-process §10 确认）

## [0.5.0] - 2026-10-08

### 新增

- **audit-service 审计账本**
  - 追加式账本 `audit_schema.audit_logs`（仅插入，无更新/删除接口；不记录密码等敏感明文）
  - 服务间写入端点（转发操作者令牌，自动记录 actor/role，系统动作标 SYSTEM）+ 管理端分页查询（动作/操作者/竞赛过滤）
  - 接入样本：anti-cheat 审核动作经 AuditClient 写账本（尽力而为，不阻断主流程）
  - 服务端口 8087；gateway 新增 audit 路由（internal 写入经网关不可达）

## [0.4.0] - 2026-10-07

### 新增

- **anti-cheat-service 风险事件与人工审核**
  - 考生客户端上报（severity 服务端规则表计算，不采信上报值；未知类型保守 MEDIUM）
  - 分页查询（竞赛/学生/等级/审核状态过滤）；审核状态机 OPEN→ACKNOWLEDGED→CONFIRMED/DISMISSED（条件更新防并发、终态保护、动作留痕）
  - 事件入库即写 `lunar-risk-event-created` Outbox（gateway WebSocket 告警的未来消费方）
  - 服务端口固定 8086；gateway 新增 monitor 路由
- **gateway-service 统一入口落地**
  - 技术选型定为 Spring Cloud Gateway（响应式）：需 WebSocket 入口能力与限流/重试生态
  - 路由四前缀：auth / contests / problems / submissions（上游地址 `*_SERVICE_URL` 可覆盖），JWT 与 `X-Request-Id` 透传
  - `/api/v1/internal/**` 刻意不暴露（经网关 404），服务间调用内网直连
  - 真机验证：OpenResty:80 → 网关 → 四服务全链路、internal 隔离、未认证 401

## [0.2.0] - 2026-10-07

### 新增

- **RocketMQ 事件总线接通，评测链路端到端自动化**
  - submission-service：`OutboxPublisher` 定时扫描未发布事件行投递 RocketMQ（成功后标记，失败重试）；`JudgedConsumer` 消费 `lunar-submission-judged` 更新成绩读模型（终态保护幂等吞重复消息）
  - judge-service：`SubmissionCreatedConsumer` 消费 `lunar-submission-created` → JudgeEngine 执行 → 发布 `lunar-submission-judged`（verdict 归一化，ACCEPTED=100 分）
  - Mock 引擎升级为"提交即判对"，无 go-judge 亦可全链路演示；真机验证：提交后无需人工干预，状态自动流转至 ACCEPTED，积压消息在消费组上线后自动补消费
  - rocketmq-spring-boot-starter 2.3.0 接入 submission/judge；judge-service 新增 application.yml（rocketmq 配置）

### 修复

- 无（本切片为纯新增）

## [0.1.0] - 2026-10-06

开发期首个可用切片：认证、考试生命周期、学生管理、考生会话、公告、题库、提交与评测适配
全部为真实实现（落库 + JWT 验签 + 角色控制），81 个单元测试，全部端到端实测通过。

### 新增

- **auth-service（认证与用户）**
  - 真实登录：MyBatis-Plus 加载用户、BCrypt 校验、账号状态检查；失败限流（连续 5 次锁定 5 分钟）
  - RS256 JWT 签发（claims 契约见 `docs/api.md`）与 JWKS 公钥分发（`GET /api/v1/auth/jwks`，标准 RFC 7517 格式）
  - 服务间接口：学生账号创建/查询/密码重置（TEACHER/ADMIN 令牌转发，幂等靠唯一约束，审计落结构化日志）
  - 开发种子管理员（Flyway 占位符守卫，生产 `AUTH_DEV_SEED=false` 关闭）
- **contest-service（考试管理）**
  - 考试生命周期：创建/审核/发布/归档 + 状态时间窗口自动推进（PUBLISHED→RUNNING→FINISHED，条件更新防并发）
  - 学生名单：添加（账号不存在经 auth 创建、存在复用）、分页查询、重置密码、软删除移出、重新加入激活
  - 考生会话：进入/恢复（断线重连保留剩余时间）、心跳、心跳超时过期、管理端终止；名单/窗口/状态三重校验
  - 公告：发布（重要/需确认标记）、考生拉取即送达、确认幂等
- **problem-service（题库）**
  - 题目版本模型：创建即 v1 草稿，发布后不可变，改动建新版本（支持基于已有版本复制）
  - 判题限制（CPU/内存/输出）与语言白名单；**测试点与考生视角严格隔离**（公开 API 不返回测试点）
- **submission-service（提交）**
  - 提交链路：会话有效 + 竞赛进行中（服务间判定）+ 语言白名单校验；判题限制随题目快照
  - 幂等：`Idempotency-Key` 同键同指纹返回首次结果、异指纹 409
  - Outbox（与业务事实同事务）与判决回写（终态保护）
- **judge-service（评测）**
  - `GoJudgeAdapter`：go-judge REST 协议适配（编译/测试点执行/verdict 归一化），C/C++/Java/Python 语言模板
  - `GO_JUDGE_BASE_URL` 设置即启用真实引擎，未设置回落 MockJudgeEngine
- **公共与基础设施**
  - `lunar-common`：统一响应、`RequestIdFilter` 自动注册（响应头与请求体回显 X-Request-Id）、JudgeEngine 抽象
  - Docker Compose 四层编排：core（PostgreSQL/Redis/MinIO）、platform（Nacos/RocketMQ/Dashboard）、edge（OpenResty）、judge（go-judge）
  - OpenResty 边缘反向代理（健康检查、WS 升级头、请求追踪透传）；RocketMQ Dashboard（与 broker 共享网络命名空间）
  - 各服务 Swagger UI（Bearer JWT Authorize 方案声明）；`docs/api.http` 接口手测集

### 修复

- JWKS 端点改为标准顶层 `{"keys":[...]}` 格式（原 ApiResponse 包装导致资源服务无法解析）
- 多服务共库的 Flyway 版本冲突：确立"每服务独立 schema + 独立迁移历史表"约定
- go-judge 在 Docker Desktop 启动即 fatal：补 `cgroup: host`（WSL2 下 private cgroup 命名空间路径为空）
- MyBatis-Plus 3.5.9+ 分页拦截器需独立 `mybatis-plus-jsqlparser` 依赖（否则编译失败/分页 total 恒 0）
- 认证入口点 401 语义、`Instant` 截断秒级、服务间调用 Bearer 前缀重复与 ApiResponse 解包等交互缺陷

### 文档

- 部署实测结论（端口 5433 避让本机服务、启动顺序、冒烟验证）、错误码注册表扩充、接口手测集

### 已知限制

- go-judge 在 Docker Desktop 上嵌套 cgroup 内存限制不可靠（本地沙箱联调受限；生产 Linux 节点无此问题）
- 登录限流与幂等键为数据库/内存实现，Redis 接入后迁移；RocketMQ 未接（Outbox 已落库未发布）
- gateway / anti-cheat / media / audit 为占位模块；「题目属于竞赛」「提交次数上限」待关联表与阈值确认
