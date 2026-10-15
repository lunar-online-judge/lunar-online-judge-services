# 中间件清单

本文档列出项目依赖的全部中间件与外部服务，说明各自的用途、功能、接入状态和使用约束。版本与镜像以 `docker/compose.core.yml`、`docker/compose.platform.yml` 和根 POM 为准；端口与启动见 `docs/deployment.md`；数据流细节见 `docs/architecture.md`。

## 总览

| 中间件 | 镜像/版本 | 端口 | 用途 | 接入状态 |
|---|---|---|---|---|
| PostgreSQL | `postgres:16` | 5432 | 唯一业务事实源，多 schema 隔离 | Compose 已提供；驱动 + Flyway 已引入（auth） |
| Redis | `redis:7-alpine` | 6379 | 短期状态：令牌、幂等、限流、心跳、锁 | Compose 已提供；Starter 未接入 |
| RocketMQ | `apache/rocketmq:5.3.1` | 9876 | 服务间异步事件总线（7 个 Topic） | 已接入：submission/judge 生产与消费 lunar-submission-created/judged（rocketmq-spring 2.3.0） |
| Nacos | `nacos/nacos-server:v2.4.2` | 8848 / 9848 | 服务发现与配置中心（预留） | Compose 已提供；Starter 未接入 |
| MinIO | `minio/minio:latest` | 9000 / 9001 | 截图对象存储 | Compose 已提供；SDK 未接入 |
| OpenResty | `openresty/openresty:1.29.2.4-0-alpine` | 80 | 边缘反向代理：唯一对外入口，转发 gateway | Compose 已提供；回源宿主机 gateway，gateway 未实现路由 |
| go-judge | `criyle/go-judge`（latest，未固定） | 5050 | 用户代码沙箱执行（唯一执行环境） | Compose 已提供；`GoJudgeAdapter` 已实现（真实容器未实测） |
| JPlag | 独立 worker 进程 | — | 代码查重引擎 | 未接入 Maven Reactor，集成前审查许可证 |
| SFU（WebRTC） | 未选型 | — | 屏幕/摄像头实时流转发 | 未选型、未部署 |

## PostgreSQL

**用途**：所有业务数据的唯一事实源；Elasticsearch 移除后，风险事件和审计的检索查询也落在这里。

**功能**：

- 单实例部署（首期不做主从高可用），单库多 schema 隔离：`auth_schema`、`contest_schema`、`problem_schema`、`submission_schema`、`judge_schema`、`anti_cheat_schema`、`media_schema`、`audit_schema`；
- 承载用户账号与凭据哈希、竞赛与报名关系、题目与版本、提交与成绩读模型、评测任务、风险事件与审核、截图元数据、审计账本；
- Outbox 事件表与业务事实在同一个本地事务提交，保证"落库成功则事件必达"；
- 各服务表结构迁移统一用 Flyway 管理（`FLYWAY_ENABLED` 开关，默认关闭）。

**约束**：服务禁止跨 schema 直接查询；高负载 schema 以后可迁独立实例；生产环境需要独立备份策略。检索密集的字段（时间、竞赛、类型）建表时需配好索引。

## Redis

**用途**：短期状态与协调层，存放可以丢失重建的数据。

**功能**（括号内为当前状态）：

- 限流计数：**登录失败锁定已迁到 Redis**（auth-service，键 `auth:login:fail:*` / `auth:login:lock:*`，TTL 即锁定时长）；接口频控未开发；
- 令牌失效状态（登出/踢下线）：未开发；
- 写操作 `Idempotency-Key` 幂等键：**仍以 PostgreSQL 唯一约束为权威，不迁 Redis**（理由见下）；
- 考生会话心跳与会话在线状态：**未迁**，`contest_schema.exam_sessions` 仍是权威（理由见下）；
- WebSocket 节点路由、评测任务短期锁、截图频率控制：均未开发。

**约束**：Redis 不是成绩、审计或任何业务事实源——Redis 数据丢失不允许造成业务数据丢失。

**接入状态**：`spring-boot-starter-data-redis` 目前只有 auth-service 引入（连接参数见 `docs/deployment.md` 的 `REDIS_HOST` / `REDIS_PORT` / `REDIS_TIMEOUT_MS`）。计数与锁定标记用 `StringRedisTemplate` + Lua 脚本**原子执行**——若拆成多次命令，"INCR 成功但没设上 TTL"会留下永不过期的计数键，导致锁到期后下一次失败立刻再次锁定（旧内存实现即有该缺陷）。**fail-open**：Redis 不可用时登录限流放行并打 warn 日志——限流属短期状态层，其不可用不应让全站无法登录。

**有意不迁 Redis 的两项**（非遗漏，改动前须先推翻这里的理由）：

- **幂等键**：`docs/api.md` 把"同一键 + 相同请求内容返回首次结果、不同内容 409 `SUBMISSION_DUPLICATE`"写成了对外契约，且 submission 行本身是成绩读模型。若以 Redis 为幂等事实源，Redis 抖动或 TTL 过期会导致同键重试**新建 submission 并重复触发评测**——等于把数据库的正确性保证换成更弱的保证。Redis 至多可做加速缓存，DB 唯一约束必须保留为最后防线。
- **会话状态**：`exam_sessions.status` 是提交通道（submission-service 经 `canSubmit` 依赖它），且 TERMINATED 是终态。Redis 全丢会让超时扫描把在考会话误判为 EXPIRED、直接阻断考生提交。可迁的只有"在线心跳/在线标记"这类可重建信息，且 DB 仍须为权威。

## RocketMQ

**用途**：服务间异步事件总线，解耦提交、评测、风控、截图、查重链路。

**功能**：

- 首期 7 个 Topic：`lunar-submission-created`、`lunar-submission-judged`、`lunar-risk-event-created`、`lunar-screenshot-requested`、`lunar-screenshot-created`、`lunar-plagiarism-requested`、`lunar-plagiarism-completed`（生产者/消费者矩阵见 `docs/architecture.md` 第 5 节）；
- 事件统一信封：`eventId`、`eventType`、`aggregateId`、`occurredAt`、`schemaVersion`、`traceId`；
- 与 PostgreSQL Outbox 配合实现可靠发布；发布失败进死信表，支持管理员重放。

**约束**：消费者必须按 `eventId` 幂等；重复消息不能把提交状态从终态改回运行态。

**本地部署**：Compose 已包含 NameServer + broker（同为 `apache/rocketmq:5.3.1`）。broker 配置在 `docker/rocketmq/broker.conf`：单主 `ASYNC_MASTER`、堆内存调到 512m、`brokerIP1=127.0.0.1`（Spring 服务跑在宿主机的前提——否则 broker 向 NameServer 注册容器内网 IP，宿主机客户端连不上；服务容器化后注释掉该行即可）、开发期 `autoCreateTopicEnable=true`（生产必须关闭并显式建 Topic）。

## Nacos

**用途**：服务发现 + 配置中心（均已接入）。

**功能**：

- **服务注册与发现**：8 个服务启动时注册到 `DEFAULT_GROUP`，调用方按服务名寻址——gateway 的 6 条路由用 `lb://<服务名>`，服务间客户端（`AuthInternalClient` / `UpstreamClient` / `AuditClient`）的 baseUrl 默认值也是服务名，由 Nacos 注册表解析实例；
- **配置中心**：托管"运行时可变"的服务私有配置，改值后无需重启即生效。

### 配置中心：放什么、不放什么

dataId = `<spring.application.name>.yml`，分组 `DEFAULT_GROUP`，命名空间 `public`（与服务发现一致；`NACOS_CONFIG_NAMESPACE` 可按需覆盖）。

| 服务 | 键 | 改后是否热生效 |
|---|---|---|
| auth-service | `auth.login.max-failures`、`auth.login.lock-minutes` | **是** |
| contest-service | `exam.session.heartbeat-timeout-minutes`（考试期间网络抖动时可临时放宽） | **是** |
| contest-service | `exam.status.sweep-interval-ms` | 否，重启生效 |
| submission-service / anti-cheat-service | `outbox.publish-interval-ms` | 否，重启生效 |

**不放**（有意为之）：

- **自举参数**：Nacos 自身的连接信息（`server-addr` / `namespace`）必须留在本地 yml——"从哪里取配置"不可能从配置中心拿；`NACOS_SERVER_ADDR` 仍是环境变量。
- **部署期配置**：端口、数据库连接串、服务地址（`*_BASE_URL` / `*_SERVICE_URI`）、judge 的 go-judge 超时——随版本发布走，留在 git，也确实是启动固化的。
- **密钥**：`AUTH_JWT_PRIVATE_KEY` 等按 `docs/deployment.md` 从环境变量注入，既不入库也不入配置中心。

**热更新的边界**：只有 `@ConfigurationProperties`（可变 JavaBean）+ **每次调用时读取**才能热更新。`@Value` 不参与刷新；构造期读进字段等于固化（那样配置中心就只剩"改完重启"的价值）。`@Scheduled(fixedDelayString=…)` 与 HTTP 客户端超时都在启动时解析/固化，改后需重启——上表已逐项标注。

**离线兜底**：`spring.config.import` 用 `optional:` 前缀，Nacos 不可达时服务仍以 `@ConfigurationProperties` 的字段默认值启动（与 `NACOS_FAIL_FAST=false` 一致）。**注意：导入的配置优先级低于本地 yml**——被托管的键不能再写在本地 yml，否则本地值会永远盖住 Nacos 的值（本项目已按此清理过 auth 的 `auth.login.*`）。环境变量优先级高于两者，仍可临时覆盖。

**配置内容的真源在 git**：`docker/nacos/config/<服务名>.yml` 是这些配置的版本化真源，用 `bash docker/nacos/publish-configs.sh` 推送到 Nacos（走官方 nacos-client，免鉴权、不受 HTTP API 版本差异影响）。推荐流程：改仓库里的 yml → 提交 → 跑脚本。控制台 UI 也能改（适合临时调参），但那样只有 Nacos 自己的变更历史，不在 git 里。

**为什么服务端用 3.x**：SCA 2025.0.0.0 捆绑 nacos-client 3.0.3，其命名空间语义对标 Nacos 3.x；服务端停在 2.4.2 时**配置拉取会静默失败**——nacos-client 默认按 `public` 取配置，而 2.x 的 public 命名空间真实 ID 是空字符串，gRPC 返回 `not-found`（服务端 `config-client-request.log` 里 `tenant=public → not-found` 与 `tenant=null → ok` 两行是直接证据；HTTP API 会做命名空间别名，**用 curl 测不出来**，客户端侧只表现为 `[Nacos Config] config[…] is empty`）。升级到 **3.0.3（与捆绑客户端同补丁线，最适配）** 后默认命名空间即可用，配置与服务发现都放 `public` + `DEFAULT_GROUP`。

**实测要点（踩过的坑，勿重复）**：

- 3.x 镜像的启动脚本**强制要求** `NACOS_AUTH_TOKEN`（Base64）、`NACOS_AUTH_IDENTITY_KEY`、`NACOS_AUTH_IDENTITY_VALUE` 三个环境变量（2.x 无此要求），缺任一容器直接退出；开发用值已写在 compose 里，生产必须换强随机值。
- 刻意**不设** `NACOS_AUTH_ENABLE`：沿用镜像默认的 `nacos.core.auth.enabled=false`，客户端 API 免凭据（与 2.x 时期一致）。**控制台另有自己的登录**，且 3.x 取消了默认 `nacos/nacos`——首次访问控制台需**初始化管理员密码**（用户名预填 `nacos`，密码留空会随机生成）。
- **3.x 的 API 变化**：v1 配置 API 已移除（`POST /nacos/v1/cs/configs` 返回 404），发布配置走控制台或用 `docker/nacos/publish-configs.sh`；v3 的 admin API 需要控制台登录凭据。**v1 的命名（naming）API 仍可用**，`docs/deployment.md` 里的冒烟命令不受影响。
- 暂无托管配置的服务（problem / judge / audit / gateway）启动时会打一条 `config[…] is empty` 警告，属预期（`optional:` 导入 + 该服务确实没有托管配置），不是错误。
- **配置数据已持久化**：`docker/compose.platform.yml` 给 nacos 挂了命名卷 `nacos-data-v3`（此前无卷，容器重建即丢；3.x 的 Derby 结构与 2.x 不兼容，故换了新卷；已实测强制重建后配置仍在）。

### 服务发现的约束

- 依赖：根 POM 的 `spring-cloud-alibaba.version=2025.0.0.0`（Spring Cloud Alibaba BOM，须与 spring-cloud 同代，2025.0.3 对应它）；各服务引 `spring-cloud-starter-alibaba-nacos-discovery`，**按服务名发起调用或路由的服务（gateway / contest / submission / anti-cheat）另需 `spring-cloud-starter-loadbalancer`**——SCA 的 starter 不以非 optional 方式传递它，缺了会在 `lb://` 处直接抛 `NoLoadBalancerClientException`。
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
- 平台通过 `lunar-common` 的 `JudgeEngine` 抽象和 `GoJudgeAdapter`（待实现）调用，业务代码不绑定 go-judge 协议。

**约束**：评测节点禁止出网；禁止访问数据库、消息队列和宿主机敏感目录；必须与业务服务分节点或分隔离运行时；评测镜像固定版本并扫描；源码和测试数据只在评测任务临时目录存在，任务结束即清理。当前仓库内由 `MockJudgeEngine` 本地替代，通过 `GO_JUDGE_BASE_URL` 等环境变量切换。

**本地部署与代码接入**（2026-10-05 完成）：

- Compose：`docker/compose.judge.yml` 提供单节点 go-judge（`criyle/go-judge`，5050 端口，`privileged: true` + `shm_size: 256m` 为官方推荐的本地运行方式），独立默认网络，不与 core/platform/edge 共网；镜像未固定版本，生产前必须锁定；
- 代码：judge-service 的 `io.lunar.judge.infra.GoJudgeAdapter` 实现 `JudgeEngine`，翻译为 `POST /run` 协议（status 为字符串枚举，time 纳秒 / memory 字节，编译产物走 `copyOutCached` → `copyIn fileId`）；`GO_JUDGE_BASE_URL` 设置即启用并优先于 mock profile，未设置时回落 `MockJudgeEngine`；
- 执行语义：go-judge `/run` 是同步接口，适配器在 `submit` 内一次性完成编译 + 全部测试点（首个失败即停），异步任务状态机（`judge_task` 表 + 重试/超时补偿）仍待评测链路落地；
- 测试点约定：`JudgeSubmissionCommand.testFiles` 中 `<name>.in` 为输入，与同名 `<name>.out` / `<name>.ans` 为期望输出成对，按文件名排序；
- 语言模板：v1 内置 C / C++ / Java / Python 四种（`LanguageTemplate`，硬编码过渡，后续迁 problem-service 配置）；Java 多编译产物（内部类）暂不支持；go-judge 容器内需有对应工具链，`criyle/go-judge` 默认镜像是否自带 jdk/python 未实测，缺失时需自定义镜像或挂载工具链。

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
- **Judge0 / DOMjudge / DMOJ**：明确不引入的第三方评测平台，评测统一走 go-judge + 自有 `JudgeEngine` 抽象（见 `docs/architecture.md` §16）。
