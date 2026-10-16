# 中间件清单

本文档列出项目依赖的全部中间件与外部服务，说明各自的用途、功能、接入状态和使用约束。版本与镜像以 `docker/compose.core.yml`、`docker/compose.platform.yml` 和根 POM 为准（`docker/compose.ai.yml` 已废弃，只剩一段说明性注释，见"LLM 推理服务"节）；端口与启动见 `docs/deployment.md`；数据流细节见 `docs/architecture.md`。

## 总览

| 中间件 | 镜像/版本 | 端口 | 用途 | 接入状态 |
|---|---|---|---|---|
| PostgreSQL | `postgres:16` | 5432（本地 Compose 映射 5433） | 唯一业务事实源，多 schema 隔离 | Compose 已提供；驱动 + Flyway 已在 8 个有库服务引入（auth / contest / problem / submission / judge / anti-cheat / audit / ai） |
| Redis | `redis:7-alpine` | 6379 | 短期状态：限流计数（令牌、幂等、心跳、锁**有意不迁**，见下）、事件消费去重 | Compose 已提供；已接入 auth-service（登录失败限流）与 judge-service（`lunar-submission-created` 消费去重） |
| RocketMQ | `apache/rocketmq:5.3.1` | 9876 | 服务间异步事件总线（规划 7 个 Topic；代码中已出现 6 个事件类型：`lunar-submission-created`、`lunar-submission-judged`、`lunar-risk-event-created`、`lunar-risk-event-reviewed`、`lunar-audit-recorded`、`lunar-ai-finding-created`） | 已接入：submission / judge / anti-cheat / ai 经 Outbox 生产，judge 消费 `lunar-submission-created`；submission 与 analytics 消费 `lunar-submission-judged`；analytics 消费 `lunar-risk-event-created` / `lunar-risk-event-reviewed` / `lunar-ai-finding-created`；audit 消费 `lunar-audit-recorded`（全仓 4 个消费者，rocketmq-spring 2.3.0） |
| Nacos | `nacos/nacos-server:v3.0.3` | 8848 / 9848 / 8849（控制台） | 服务发现 + 配置中心 | 已接入：10 个服务均注册到 Nacos 并按服务名寻址，5 个服务有托管配置 |
| MinIO | `minio/minio:latest` | 9000 / 9001 | 截图对象存储 | Compose 已提供；SDK 未接入 |
| OpenResty | `openresty/openresty:1.29.2.4-0-alpine` | 80 | 边缘反向代理：唯一对外入口，转发 gateway | Compose 已提供；回源宿主机 gateway，gateway 的 8 条路由（含 `/api/v1/ai/**`、`/api/v1/analytics/**`）已实现 |
| go-judge | `criyle/go-judge`（本仓库 Dockerfile 基于它构建，未固定 digest） | 5050 | 用户代码沙箱执行（唯一执行环境） | Compose 已提供；`GoJudgeAdapter` 已实现，C++ 编译/运行与五类 verdict 已真机实测（测试点传输仍缺，见"go-judge"节） |
| LLM 推理（云厂商 OpenAI 兼容端点） | 外部服务：云厂商 OpenAI 兼容 API（无镜像、无本地容器） | 由 Provider 决定（`https://…`） | 大模型推理：代码评审、风险研判、出题草稿；语义查重默认走代码指纹、不需要模型 | **只接入云端 API 模型**：地址由 `AI_PROVIDER_BASE_URL` 显式给（无默认值，缺了服务起不来）；ai-service 以 LangChain4j 1.22.0 + `langchain4j-open-ai` 接入（见"LLM 推理服务"节）。本地推理栈已废弃 |
| JPlag | 独立 worker 进程 | — | 代码查重引擎 | 未接入 Maven Reactor，集成前审查许可证 |
| SFU（WebRTC） | 未选型 | — | 屏幕/摄像头实时流转发 | 未选型、未部署 |

## PostgreSQL

**用途**：所有业务数据的唯一事实源；Elasticsearch 移除后，风险事件和审计的检索查询也落在这里。

**功能**：

- 单实例部署（首期不做主从高可用），单库多 schema 隔离：`auth_schema`、`contest_schema`、`problem_schema`、`submission_schema`、`judge_schema`、`anti_cheat_schema`、`media_schema`、`audit_schema`、`ai_schema`；
- 承载用户账号与凭据哈希、竞赛与报名关系、题目与版本、提交与成绩读模型、评测任务、风险事件与审核、截图元数据、审计账本、AI 作业与结论（`ai_schema`）；
- Outbox 事件表与业务事实在同一个本地事务提交，保证"落库成功则事件必达"；
- 各服务表结构迁移统一用 Flyway 管理（`FLYWAY_ENABLED` 开关，默认关闭）。

**约束**：服务禁止跨 schema 直接查询；高负载 schema 以后可迁独立实例；生产环境需要独立备份策略。检索密集的字段（时间、竞赛、类型）建表时需配好索引。

## Redis

**用途**：短期状态与协调层，存放可以丢失重建的数据。

**功能**（括号内为当前状态）：

- 限流计数：**登录失败锁定已迁到 Redis**（auth-service，键 `auth:login:fail:*` / `auth:login:lock:*`，TTL 即锁定时长）；接口频控未开发；
- 事件消费去重：**judge-service 已接入**（键 `judge:consumed:*`，TTL 默认 24 小时，`judge.consumer.consumed-event-ttl-hours` 可托管调参）。judge 的库只有 `judge_schema.outbox_events` 一张表（没有可做去重闸门的业务表），幂等只能落在短期状态层；**有意采用"先查、处理成功后再登记"而不是先 SETNX 占坑**——先占坑会在评测中途失败后让重投看到坑位已占而直接 ack，判决永久丢失（fail-open：Redis 不可用时不去重，仍然评测）；
- 令牌失效状态（登出/踢下线）：未开发；
- 写操作 `Idempotency-Key` 幂等键：**仍以 PostgreSQL 唯一约束为权威，不迁 Redis**（理由见下）；
- 考生会话心跳与会话在线状态：**未迁**，`contest_schema.exam_sessions` 仍是权威（理由见下）；
- WebSocket 节点路由、评测任务短期锁、截图频率控制：均未开发。

**约束**：Redis 不是成绩、审计或任何业务事实源——Redis 数据丢失不允许造成业务数据丢失。

**接入状态**：`spring-boot-starter-data-redis` 目前由 auth-service（登录失败限流）与 judge-service（消费去重）引入（连接参数见 `docs/deployment.md` 的 `REDIS_HOST` / `REDIS_PORT` / `REDIS_TIMEOUT_MS`）。计数与锁定标记用 `StringRedisTemplate` + Lua 脚本**原子执行**——若拆成多次命令，"INCR 成功但没设上 TTL"会留下永不过期的计数键，导致锁到期后下一次失败立刻再次锁定（旧内存实现即有该缺陷）。**fail-open**：Redis 不可用时登录限流放行并打 warn 日志——限流属短期状态层，其不可用不应让全站无法登录。

**有意不迁 Redis 的两项**（非遗漏，改动前须先推翻这里的理由）：

- **幂等键**：`docs/api.md` 把"同一键 + 相同请求内容返回首次结果、不同内容 409 `SUBMISSION_DUPLICATE`"写成了对外契约，且 submission 行本身是成绩读模型。若以 Redis 为幂等事实源，Redis 抖动或 TTL 过期会导致同键重试**新建 submission 并重复触发评测**——等于把数据库的正确性保证换成更弱的保证。Redis 至多可做加速缓存，DB 唯一约束必须保留为最后防线。
- **会话状态**：`exam_sessions.status` 是提交通道（submission-service 经 `canSubmit` 依赖它），且 TERMINATED 是终态。Redis 全丢会让超时扫描把在考会话误判为 EXPIRED、直接阻断考生提交。可迁的只有"在线心跳/在线标记"这类可重建信息，且 DB 仍须为权威。

## RocketMQ

**用途**：服务间异步事件总线，解耦提交、评测、风控、截图、查重链路。

**功能**：

- 首期 7 个 Topic：`lunar-submission-created`、`lunar-submission-judged`、`lunar-risk-event-created`、`lunar-screenshot-requested`、`lunar-screenshot-created`、`lunar-plagiarism-requested`、`lunar-plagiarism-completed`（生产者/消费者矩阵见 `docs/architecture.md` 第 5 节）；
- **代码中实际已出现 6 个事件类型**（以生产者为准）：上表 7 个里的 `lunar-submission-created`（submission）、`lunar-submission-judged`（judge，submission 的内部回写端点也发同一类型）、`lunar-risk-event-created`（anti-cheat / media），以及**不在首期 7 个之内**的 `lunar-risk-event-reviewed`（anti-cheat 审核流转）、`lunar-audit-recorded`（submission / anti-cheat / ai 的审计留痕）、`lunar-ai-finding-created`（ai 的结论事件）。消费者的落地情况只有 3 条：judge 消费 `lunar-submission-created`、submission 消费 `lunar-submission-judged`、audit 消费 `lunar-audit-recorded`；其余事件暂无消费者；
- 事件统一信封：`eventId`、`eventType`、`aggregateId`、`occurredAt`、`schemaVersion`、`traceId`；
- 与 PostgreSQL Outbox 配合实现可靠发布。**当前实现**：发布器按 `published_at is null` 扫描并投递，失败累计 `attempts` 并按指数退避重试，超过上限（默认 10 次）置 `dead_lettered_at` 转死信。submission / judge / anti-cheat / ai 四个服务都是这套模式（各自的 `<schema>.outbox_events`）；**死信的人工重放接口目前只有 submission-service 提供**（`/api/v1/submissions/admin/dead-letters`），其余三个服务只能直接查表处理。

**约束**：消费者必须按 `eventId` 幂等；重复消息不能把提交状态从终态改回运行态。

**上线格式（2026-10 已落地）**：消息体是**完整事件信封**（`eventId` / `eventType` / `aggregateId` / `occurredAt` / `schemaVersion` / `traceId` + 内层 `payload`）。此前只发内层 payload，`eventId` 不随消息传输，消费侧的"按 eventId 幂等"根本无法实现；现在 submission 用 `consumed_events` 表闸门、judge 用 Redis 去重。消费端同时兼容旧的裸 payload 格式（灰度期），细节见 `docs/api.md`"事件信封"。

**本地部署**：Compose 已包含 NameServer + broker（同为 `apache/rocketmq:5.3.1`）。broker 配置在 `docker/rocketmq/broker.conf`：单主 `ASYNC_MASTER`、堆内存调到 512m、`brokerIP1=127.0.0.1`（Spring 服务跑在宿主机的前提——否则 broker 向 NameServer 注册容器内网 IP，宿主机客户端连不上；服务容器化后注释掉该行即可）、开发期 `autoCreateTopicEnable=true`（生产必须关闭并显式建 Topic）。

## LLM 推理服务（云厂商 OpenAI 兼容端点）

**用途**：ai-service 的大模型调用出口。代码评审、作弊风险辅助研判、出题草稿三类作业要调模型；语义查重默认只用纯 Java 的 MinHash 代码指纹，不依赖它。

**本项目只接入云端 API 模型**（2026-10 起不再支持本地 Ollama 这类跑在本机的模型运行时）：

- **Provider 地址必须显式配置**：base-url **没有默认值**（`AiProviderProperties` 的字段默认是空串），未配置 `AI_PROVIDER_BASE_URL`（或配置中心 `ai.provider.base-url`）时 `AiPrivacyGuard` 在启动期直接抛 `IllegalStateException`——服务起不来，而不是回落到某个"碰巧能通"的地址。此前的默认值 `http://localhost:11434/v1` 已删除：默认值意味着"不配置也能跑"，而"跑起来用的到底是哪个端点"恰恰是数据出境这件事上最不能猜的一项；
- **换供应商只换 base-url**：各家云厂商都提供 OpenAI 兼容协议（`/chat/completions`、`/embeddings`），因此换 Provider 只是换 base-url（外加 api-key 与模型名）；
- **不引任何厂商专属 SDK**：ai-service 只引 `langchain4j-open-ai`，刻意不引 `langchain4j-ollama`、也不引各家官方 SDK，避免形成多条会分叉的调用路径；
- **模型名走配置，不绑死某一家**：`chat-model` / `embedding-model` 的默认值只是中性占位（`gpt-4o-mini` / `text-embedding-3-small`），**按所选厂商的模型名填**；
- **模型构建与热更新**：`AiModelFactory` 按**配置指纹**（mode / base-url / 模型名 / 温度 / 最大输出 / 密钥哈希）缓存 `ChatModel` 与 `EmbeddingModel`，配置一变自然换新实例。**刻意不做成单例 Bean**：Provider 参数是配置中心托管的运行时可变项，"改完即生效"是本仓库对托管配置的承诺，启动期固化成静态 Bean 后配置中心就只剩"改完重启"的价值；
- **重试归作业所有**：客户端 `maxRetries(0)`，重试由 `ai_jobs` 的 attempts + 指数退避负责。让框架在内部悄悄重试会让"尝试次数"失真，日志里的失败次数也就不可信；
- **不记录请求/响应体**（`logRequests`/`logResponses` = false）：题面与源码会因此进日志，而仓库明确要求学生代码不落日志；
- 连接/读超时在启动期固化（默认 10s / 120s，建在 RestClient 上），改后需重启。

**为什么不用官方 Spring Boot starter**：`langchain4j-spring-boot-starter` 走 `-beta` 版本线（1.22.0-betaNN），与仓库的 1.22.0 正式版本线不一致；更关键的是那套 starter 产出**静态 Bean**，与上面"每轮作业按当前配置取模型"的热更新需求直接冲突。当前只用 BOM + `langchain4j`、`langchain4j-open-ai` 两个模块，依赖面最小。

**已废弃的本地推理栈（`docker/compose.ai.yml`）**：

- 该文件**只剩注释**（有意保留占位而不是删除：直接删会让照旧执行的人只看到"文件不存在"这种易误判的报错）；YAML 只有注释是合法的，而 compose 会拒绝它——**本机实测（2026-10）**：`docker compose -f docker/compose.ai.yml up -d` 与 `… config` 都以 `empty compose file` 退出（exit 1），既不建工程也不起容器。这正是我们要的效果：照旧文档执行的人会立刻停下，而不是在不知情的情况下又起一套本机推理；
- 无 11434 端口、无 `ollama pull`、无 `lunar-ollama` 容器、无 `ollama-models` 命名卷；
- **它没有被任何其它 compose 文件 `include`/`extends`**：`docker/compose.full.yml` 只收 `compose.core.yml` / `compose.platform.yml` / `compose.edge.yml` / `compose.judge.yml`，因此废弃它不影响组合启动；
- 需要"数据不出境"的部署时，正确做法是自建**内网的 OpenAI 兼容网关**并把 base-url 指向它（此时地址判内网、不需要出境授权）——它仍是 API 模型，**不要**回到本机模型运行时。

**数据边界（数据出境门禁）**：`AiPrivacyGuard` 的判据是 **base-url 的地址**，不是 `ai.provider.mode`——把 mode 写成 LOCAL 却填了公网地址，是最容易发生的数据出境方式。**这条逻辑本轮原样保留、不因"只接云端"而放宽**：自建内网网关同样属于"API 模型"，而且按地址判定是后面几项授权/复核机制的基础（没有地址判据，"出境"就无从定义）。

- **地址缺失 → 失败**：base-url 为空/空白时直接抛 `IllegalStateException`，不看授权、不看密钥（"没配"不能靠一次授权兜过去）；
- 出境授权**只从环境变量 `AI_EGRESS_APPROVED` 读**。此前它与其他 Provider 参数同属 `ai.provider.*`，于是配置中心的 `ai-service.yml` 里写一行 `egress-approved: true` 就能静默打开出境；安全开关绝不能和普通可调参数共用一个可被配置中心覆盖的绑定前缀（环境变量也无法经配置中心注入）；
- **每次构建模型前复核**（`AiModelFactory` 构建前调 `verifyCurrentProvider`）。只在启动期检查是不够的：base-url 可热更新，把它改成公网地址根本不会重启，启动期检查就被绕过；
- 内网判据：回环、私有网段（10. / 192.168. / 172.16-31. / 169.254.、IPv6 的 fc/fd/fe80）、`.local` 与 `.internal` 后缀、**不含点的短名**（如 compose 里的 `llm-gateway`）；地址无法解析时按"非内网"处理（fail closed）。不合规时抛 `IllegalStateException`：启动期让服务起不来，运行期让该次作业失败（记 `INTERNAL_ERROR`），两种情况都不会把数据发出去。指向非内网地址且已授权时还要求 api-key 非空。

**配置中心：放什么、不放什么**（配置内容的真源是 `docker/nacos/config/ai-service.yml`）：

- **放**（`@ConfigurationProperties` + 每次调用时读取，改完即生效）：`ai.provider.chat-model` / `embedding-model` / `max-tokens` / `temperature`、`ai.worker.lease-seconds` / `max-attempts`、`ai.limits.max-jobs-per-day` / `minhash-threshold` / `minhash-shingle-size`；
- **`ai.provider.base-url` 是例外**：它仍是可绑定的配置键（配置中心显式加一行同样生效、且热更新），但 **git 真源 `docker/nacos/config/ai-service.yml` 里刻意不提供该键**——那里写死一个地址，等于让"某个碰巧能通的端点"变成仓库里的默认事实标准。地址由部署方通过 `AI_PROVIDER_BASE_URL` 显式声明（Spring Boot 宽松绑定，无需在 yml 里中转）；
- **不放**：`ai.provider.api-key`（密钥只从环境变量注入，既不入库也不入配置中心）、`ai.provider.mode` 与出境授权（决定"数据是否出境"的安全开关，只从环境变量来，不该被一次配置中心改动静默打开）、`connect-timeout-seconds` / `read-timeout-seconds`（建在 RestClient 上，启动期固化）、`ai.worker.concurrency` / `poll-interval-ms`（线程池大小与 `@Scheduled` 周期，启动期固化）、`ai.limits.embedding-enabled`（需要 Provider 支持 embedding 模型 + 数据库有 pgvector 扩展，属部署期开关；**当前语义查重只用 MinHash，尚未调用向量化通道**）；
- 运维核对入口：`GET /api/v1/ai/provider`（仅 ADMIN）回显 mode / 地址主机 / 模型名 / 向量检索开关，**不回显 api-key**——"把学生代码发出去之前必须能核对"的就是这几项（见 `docs/api.md`"AI"节）。

## Nacos

**用途**：服务发现 + 配置中心（均已接入）。

**功能**：

- **服务注册与发现**：10 个服务启动时注册到 `DEFAULT_GROUP`，调用方按服务名寻址——gateway 的 8 条路由用 `lb://<服务名>`，服务间调用走 **Spring 原生声明式 HTTP 接口**（`@HttpExchange`：`AuthServiceApi` / `ContestServiceApi` / `ProblemServiceApi` / `ProblemInternalApi`、ai-service 取数用的 `SubmissionInternalApi` / `ContestInternalApi` / `AntiCheatInternalApi`，以及**多个服务共用的审计写入 `AuditServiceApi` 与服务身份令牌 `ServiceTokenApi`（定义在 lunar-common，因为契约完全相同）**，由各服务的 `ServiceClientConfig` 经 `HttpServiceClientFactory` 装配成 Bean），baseUrl 默认服务名、`*_BASE_URL` 可覆盖为固定地址排障，实例由 Nacos 注册表 + Spring Cloud LoadBalancer（`@LoadBalanced RestClient.Builder`）解析；
- **配置中心**：托管"运行时可变"的服务私有配置，改值后无需重启即生效。

### 配置中心：放什么、不放什么

dataId = `<spring.application.name>.yml`，分组 `DEFAULT_GROUP`，命名空间 `public`（与服务发现一致；`NACOS_CONFIG_NAMESPACE` 可按需覆盖）。

| 服务 | 键 | 改后是否热生效 |
|---|---|---|
| auth-service | `auth.login.max-failures`、`auth.login.lock-minutes` | **是** |
| contest-service | `exam.session.heartbeat-timeout-minutes`（考试期间网络抖动时可临时放宽） | **是** |
| contest-service | `exam.status.sweep-interval-ms` | 否，重启生效 |
| submission-service | `exam.submission.window-seconds`、`exam.submission.max-per-window`（提交频率限制，docs/exam-process.md §7） | **是** |
| submission-service / anti-cheat-service | `outbox.publish-interval-ms` | 否，重启生效 |
| ai-service | `ai.provider.chat-model`、`embedding-model`、`max-tokens`、`temperature` | **是** |
| ai-service | `ai.provider.base-url`（**git 真源里刻意不提供该键**，需部署方显式给出；配置中心另行添加时同样热生效） | **是** |
| ai-service | `ai.worker.lease-seconds`、`ai.worker.max-attempts`、`ai.limits.max-jobs-per-day`、`minhash-threshold`、`minhash-shingle-size` | **是** |

ai-service **不放**的键与理由见下文"不放"清单与"LLM 推理服务"节。

**不放**（有意为之）：

- **自举参数**：Nacos 自身的连接信息（`server-addr` / `namespace`）必须留在本地 yml——"从哪里取配置"不可能从配置中心拿；`NACOS_SERVER_ADDR` 仍是环境变量。
- **部署期配置**：端口、数据库连接串、服务地址（`*_BASE_URL` / `*_SERVICE_URI`）、judge 的 go-judge 超时——随版本发布走，留在 git，也确实是启动固化的。
- **密钥**：`AUTH_JWT_PRIVATE_KEY`、`AI_PROVIDER_API_KEY` 等按 `docs/deployment.md` 从环境变量注入，既不入库也不入配置中心；**安全开关同理**——AI 的数据出境授权 `AI_EGRESS_APPROVED` 只认环境变量，配置中心（乃至本地 yml）都改不动它。
- **ai-service 的其余项**：`ai.provider.api-key` 与 `mode`、`ai.provider.connect-timeout-seconds` / `read-timeout-seconds`、`ai.worker.concurrency` / `poll-interval-ms`、`ai.limits.embedding-enabled` 一律不放配置中心，逐项理由见"LLM 推理服务"节；`ai.provider.base-url` 是另一种情况——它**可以**由配置中心给（仍是可绑定键、热生效），但 git 真源里刻意不写，地址只能来自部署环境。

**热更新的边界**：只有 `@ConfigurationProperties`（可变 JavaBean）+ **每次调用时读取**才能热更新。`@Value` 不参与刷新；构造期读进字段等于固化（那样配置中心就只剩"改完重启"的价值）。`@Scheduled(fixedDelayString=…)` 与 HTTP 客户端超时都在启动时解析/固化，改后需重启——上表已逐项标注。

**离线兜底**：`spring.config.import` 用 `optional:` 前缀，Nacos 不可达时服务仍以 `@ConfigurationProperties` 的字段默认值启动（与 `NACOS_FAIL_FAST=false` 一致）。**例外是 `ai.provider.base-url`**：它没有字段默认值，缺了就启动失败（`AiPrivacyGuard` 的 fail-closed 检查，见"LLM 推理服务"节）——地址不允许有"兜底值"。**注意：导入的配置优先级低于本地 yml**——被托管的键不能再写在本地 yml，否则本地值会永远盖住 Nacos 的值（本项目已按此清理过 auth 的 `auth.login.*`）。环境变量优先级高于两者，仍可临时覆盖。

**配置内容的真源在 git**：`docker/nacos/config/<服务名>.yml` 是这些配置的版本化真源，用 `bash docker/nacos/publish-configs.sh` 推送到 Nacos（走官方 nacos-client，免鉴权、不受 HTTP API 版本差异影响）。推荐流程：改仓库里的 yml → 提交 → 跑脚本。控制台 UI 也能改（适合临时调参），但那样只有 Nacos 自己的变更历史，不在 git 里。

**为什么服务端用 3.x**：SCA 2025.0.0.0 捆绑 nacos-client 3.0.3，其命名空间语义对标 Nacos 3.x；服务端停在 2.4.2 时**配置拉取会静默失败**——nacos-client 默认按 `public` 取配置，而 2.x 的 public 命名空间真实 ID 是空字符串，gRPC 返回 `not-found`（服务端 `config-client-request.log` 里 `tenant=public → not-found` 与 `tenant=null → ok` 两行是直接证据；HTTP API 会做命名空间别名，**用 curl 测不出来**，客户端侧只表现为 `[Nacos Config] config[…] is empty`）。升级到 **3.0.3（与捆绑客户端同补丁线，最适配）** 后默认命名空间即可用，配置与服务发现都放 `public` + `DEFAULT_GROUP`。

**实测要点（踩过的坑，勿重复）**：

- 3.x 镜像的启动脚本**强制要求** `NACOS_AUTH_TOKEN`（Base64）、`NACOS_AUTH_IDENTITY_KEY`、`NACOS_AUTH_IDENTITY_VALUE` 三个环境变量（2.x 无此要求），缺任一容器直接退出；开发用值已写在 compose 里，生产必须换强随机值。
- 刻意**不设** `NACOS_AUTH_ENABLE`：沿用镜像默认的 `nacos.core.auth.enabled=false`，客户端 API 免凭据（与 2.x 时期一致）。**控制台另有自己的登录**，且 3.x 取消了默认 `nacos/nacos`——首次访问控制台需**初始化管理员密码**（用户名预填 `nacos`，密码留空会随机生成）。
- **3.x 的 API 变化**：v1 配置 API 已移除（`POST /nacos/v1/cs/configs` 返回 404），发布配置走控制台或用 `docker/nacos/publish-configs.sh`；v3 的 admin API 需要控制台登录凭据。**v1 的命名（naming）API 仍可用**，`docs/deployment.md` 里的冒烟命令不受影响。
- 暂无托管配置的服务（problem / judge / audit / gateway）启动时会打一条 `config[…] is empty` 警告，属预期（`optional:` 导入 + 该服务确实没有托管配置），不是错误。
- **配置数据已持久化**：`docker/compose.platform.yml` 给 nacos 挂了命名卷 `nacos-data-v3`（此前无卷，容器重建即丢；3.x 的 Derby 结构与 2.x 不兼容，故换了新卷；已实测强制重建后配置仍在）。

### 服务发现的约束

- 依赖：根 POM 的 `spring-cloud-alibaba.version=2025.0.0.0`（Spring Cloud Alibaba BOM，须与 spring-cloud 同代，2025.0.3 对应它）；各服务引 `spring-cloud-starter-alibaba-nacos-discovery`，**按服务名发起调用或路由的服务（gateway / auth / contest / submission / anti-cheat / ai）另需 `spring-cloud-starter-loadbalancer`**——SCA 的 starter 不以非 optional 方式传递它，缺了会在 `lb://` 处直接抛 `NoLoadBalancerClientException`；服务间声明式调用**不需要额外依赖**：接口用 Spring Framework 自带的 `@HttpExchange`，寻址只靠上面这个 loadbalancer 提供的 `@LoadBalanced RestClient.Builder`。
- 注册 IP 固定 `127.0.0.1`（`spring.cloud.nacos.discovery.ip`）：本机多网卡（VMware VMnet / WSL 虚拟网卡）会被自动探测误选（实测选到 VMnet1 的 192.168.48.1），处理方式与 RocketMQ `brokerIP1=127.0.0.1` 一致；**服务容器化后须改为容器可达地址**。
- `spring.cloud.nacos.discovery.fail-fast=false`：Nacos 不可达时服务照常启动（仅不注册），`NACOS_FAIL_FAST=true` 可切回严格模式。
- 本地 Compose 以 standalone 模式运行（`nacos/nacos-server:v3.0.3`），映射 **8848 客户端/API + 9848 客户端 gRPC + 8849 控制台**（容器内控制台是 8080，宿主 8080 被网关占用故映射到 8849）。9848 必须映射：Nacos 客户端的注册/订阅/心跳走 gRPC，只开 8848 会报 `Server check fail, please check server xxx, port 9848 is available`。控制台地址 `http://localhost:8849/index.html`（首次访问初始化管理员密码）。
- 命名空间与分组：**服务发现与配置中心都在 `public` + `DEFAULT_GROUP`**（3.x 下 public 是正常可用命名空间，不再需要自定义命名空间）。

## MinIO

**用途**：监考截图的对象存储，首期唯一存储用途。

**功能**：

- 保存三种来源的截图：风险事件触发（`RISK_EVENT`）、定时（`SCHEDULED`）、手动（`MANUAL`）；
- 对象使用不可猜测键；下载走短期签名 URL；
- 元数据（对象键、SHA-256、来源、会话、创建时间、过期时间）存 PostgreSQL，过期后清理任务删除对象和元数据；申诉中的对象可锁定保留；
- 明确不保存连续屏幕/摄像头视频。

**约束**：Compose 中镜像为 `latest` 未固定版本，生产部署前必须锁定 digest 或明确版本；SDK 尚未接入。

## OpenResty

**用途**：边缘反向代理，平台的唯一对外入口——所有客户端流量先到 OpenResty，再转发给 gateway-service。

**功能**：

- 监听 80 端口，把全部请求转发给 gateway-service（upstream，`docker/openresty/nginx.conf`）；
- 请求追踪：透传客户端 `X-Request-Id`，缺失时由边缘生成，与 gateway 的追踪约定衔接；
- WebSocket 升级与 `X-Forwarded-*` 头设置，读写超时 300s，支撑考生端长连接（心跳、实时状态）；
- `client_max_body_size 10m` 覆盖代码提交等请求体；
- `GET /healthz` 边缘自检端点，直接返回 200，不回源。

**约束**：gateway-service 目前跑在宿主机，upstream 为 `host.docker.internal:8080`（Compose 里已加 `host-gateway` 兼容 Linux）；服务容器化后改为服务名并加入同一 Compose 网络。OpenResty 只做转发和边缘通用处理，不做鉴权、限流或业务路由——这些是 gateway-service 的职责；后续若接入 Lua 扩展（OpenResty 的能力），需单独评审。Windows 本机 80 端口被占用时改 Compose 映射端口。

## go-judge

**用途**：用户代码的唯一执行环境——独立 Linux 评测节点上的沙箱执行服务。

**功能**：

- 隔离执行编译与运行，支持 C、C++、Java、Python、Go、JavaScript/TypeScript（TS 先经 `tsc` 生成 JS）；
- 资源限制：CPU 时间、墙钟、内存、进程数、输出大小、文件大小；
- 返回退出码、stdout、stderr、耗时、内存和限制错误，由 judge-service 归一化为标准 verdict；
- 平台通过 `lunar-common` 的 `JudgeEngine` 抽象和 `GoJudgeAdapter`（已实现，见下）调用，业务代码不绑定 go-judge 协议。

**约束**：评测节点禁止出网；禁止访问数据库、消息队列和宿主机敏感目录；必须与业务服务分节点或分隔离运行时；评测镜像固定版本并扫描；源码和测试数据只在评测任务临时目录存在，任务结束即清理。当前仓库内由 `MockJudgeEngine` 本地替代，通过 `GO_JUDGE_BASE_URL` 等环境变量切换。

**本地部署与代码接入**（2026-10-05 完成）：

- Compose：`docker/compose.judge.yml` 提供单节点 go-judge（`criyle/go-judge`，5050 端口，`privileged: true` + `shm_size: 256m` 为官方推荐的本地运行方式），独立默认网络，不与 core/platform/edge 共网；镜像未固定版本，生产前必须锁定；
- 代码：judge-service 的 `io.lunar.judge.infra.GoJudgeAdapter` 实现 `JudgeEngine`，翻译为 `POST /run` 协议（status 为字符串枚举，time 纳秒 / memory 字节，编译产物走 `copyOutCached` → `copyIn fileId`）；`GO_JUDGE_BASE_URL` 设置即启用并优先于 mock profile，未设置时回落 `MockJudgeEngine`；
- 执行语义：go-judge `/run` 是同步接口，适配器在 `submit` 内一次性完成编译 + 全部测试点（首个失败即停），异步任务状态机（`judge_task` 表 + 重试/超时补偿）仍待评测链路落地；
- 测试点约定（**已落地**）：submission 提交时经 `GET /api/v1/internal/problems/{problemId}/versions/{versionId}/testcases` 取测试点，按 `<name>.in` / `<name>.out` 组装进 `lunar-submission-created` 事件的 `testFiles` 字段，judge 侧解析为 `JudgeSubmissionCommand.testFiles`（同名 `<name>.out` / `<name>.ans` 为期望输出，按文件名排序）；总字节超上限（默认 1 MiB，`PROBLEM_TESTDATA_MAX_BYTES`）时上游返回 413 `PROBLEM_TESTDATA_TOO_LARGE`——测试点要进 MQ，超限的消息会永远发不出去；
- 语言模板：已内置 **C / C++ / Java / Python / JavaScript / TypeScript** 六种（`LanguageTemplate`，硬编码过渡，后续迁 problem-service 配置）；Java 多编译产物（内部类）暂不支持；TypeScript 用 `tsc --target es2020 --module commonjs --typeRoots /usr/share/nodejs/@types main.ts` 生成 `main.js` 再由 `node` 运行（**tsc 把编译诊断写到 stdout**，适配器已做回落读取）；Go 待补；**`criyle/go-judge` 官方镜像不含任何编译器**（2026-10 实测：Debian 13 基础镜像内 gcc/g++/python3/javac 全无，编译直接报 `execve: handle: g++: executable file not found in $PATH`），因此 `docker/compose.judge.yml` 改为用 `docker/go-judge/Dockerfile` 构建带工具链的镜像（gcc / g++ / python3 / openjdk-21-jdk-headless / nodejs / node-typescript）；生产部署前锁定基础镜像 digest；
- **真机实测（2026-10，本机 Docker Desktop）**：补上工具链后，C++ 的"编译 → 按测试点运行 → 输出比对"全链路可用——`ACCEPTED` / `WRONG_ANSWER` / `COMPILE_ERROR` / `RUNTIME_ERROR` / `TIME_LIMIT_EXCEEDED` 五类 verdict 均按预期归一化（用与 `GoJudgeAdapter` 完全相同的 `/run` 协议逐步验证）。两条真机教训：①**编译命令必须显式设置 `procLimit`**，缺省（0）时编译器无法派生 `cc1plus`/`as`/`ld`，会以 `posix_spawn: Resource temporarily unavailable` 失败、把所有提交误判成 COMPILE_ERROR；②数值限制字段**没有"未设置=不限制"的语义**（实测 `memoryLimit` 不设即直接 `Memory Limit Exceeded`），因此每条命令都要显式给全 cpu/clock/memory/proc 四项；
- **多语言与沙箱挂载（2026-10 实测）**：go-judge 本身与语言无关——它只接受 `args` 并在沙箱里执行（`g++ …` / `javac …` / `python3 …`），"语言"这个概念只存在于我们自己的 `LanguageTemplate`。一个实例能支持多少语言取决于两件事：①镜像里装了什么工具链；②官方 `mount.yaml`（镜像内 `/opt/mount.yaml`）把哪些路径暴露进沙箱。该 mount.yaml 已为多语言预留：`/bin`、`/lib`、`/lib64`、`/usr`、`/etc/ld.so.cache`、`/etc/alternatives`（多版本编译器）、**`/etc/java-21-openjdk`**（注释即写 "java 21 wants /etc/java-21-openjdk"）、`/etc/fpc.cfg`（Free Pascal）、`/etc/mono`、`/var/lib/ghc`、`/dev/{null,random,urandom,zero,full}`、`proc: true`（"java & ghc wants /proc/self/exe"），以及 `/w` 与 `/tmp` 两个 128m tmpfs（`workDir: /w`、`uid/gid: 1536`）。**绑定源必须在 go-judge 启动时就存在，否则该挂载被静默跳过**——实测：在运行中的容器里补装 JDK 后，Java 仍报 `InternalError: Error loading java.security file`（沙箱内 `/etc` 只有 `alternatives` 与 `ld.so.cache`），**重启容器后 Java 立即正常**。所以工具链要装进**镜像**，不是运行时装。
- **一个实例跑多语言实测**：同一个容器内 C++（g++ 编译 + 运行）、Python（python3 解释执行）、Java（javac 编译 + java 运行）、JavaScript（`node main.js`）、TypeScript（`tsc` → `node main.js`）全部 `Accepted` 且输出正确；Java 编译 `procPeak=25`、运行 `procPeak=18`（编译命令的 128 进程上限够用），编译耗时约 2.1s；node/tsc/`@types/node` 都在 `/usr` 下、不需要额外挂载（Java 依赖上面那条 `/etc/java-21-openjdk` 挂载）。
- **加一种语言 = 两步**：`LanguageTemplate` 加模板（c/cpp/java/python 已在，TS/Go/JS 待补）+ 镜像里装对应工具链（该语言若需要额外路径，还要在 mount.yaml 补绑定并重启容器）；编译期资源要按语言单独校准（JVM 启动有固定开销，时间上限不能照 C++ 给 2s）。

## JPlag

**用途**：代码查重引擎，运行在独立 worker 进程中（规划中的 `plagiarism-worker` 模块）。

**功能**：

- 消费 `lunar-plagiarism-requested`，按竞赛/题目/语言聚合提交（首期同语言比较），生成相似度报告；
- 生产 `lunar-plagiarism-completed`，anti-cheat-service 保存相似度、双方、报告地址和阈值命中状态；
- 相同题目的提交做主查重，不同题目高相似只作辅助风险证据；结果不自动取消成绩。

**约束**：查重必须在独立 worker 执行，主服务不在 HTTP 请求线程中运行 JPlag；具体版本和许可证在集成前审查（README 当前架构决策已记录）。

## SFU（WebRTC 媒体服务器，未选型）

**用途**：屏幕/摄像头实时流的 RTP 转发节点。

**功能**：

- 接收考生客户端推送的媒体流，向被授权的监考员/老师转发；
- 媒体信令（SDP/ICE 交换）由 media-service 完成，RTP 数据只在客户端与 SFU 之间流动，不经过 gateway、业务服务或数据库；
- 断流检测产生风险事件（由客户端心跳与服务端状态共同判断）。

**约束**：连续视频首期不保存、不回放；SFU 选型未定，接入前 media-service 只有信令与授权职责。

## 暂不使用的组件

- **Elasticsearch**：已从 Compose 和文档中移除。风险事件、评测日志摘要、查重关系和审计的检索首期直接查询 PostgreSQL，后续检索需求增长时再评估引入（决策记录见 `docs/architecture.md` §5 和 README）。
- **Judge0 / DOMjudge / DMOJ**：明确不引入的第三方评测平台，评测统一走 go-judge + 自有 `JudgeEngine` 抽象（见 `docs/architecture.md` §9）。
