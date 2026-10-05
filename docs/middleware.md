# 中间件清单

本文档列出项目依赖的全部中间件与外部服务，说明各自的用途、功能、接入状态和使用约束。版本与镜像以 `docker/compose.core.yml`、`docker/compose.platform.yml` 和根 POM 为准；端口与启动见 `docs/deployment.md`；数据流细节见 `docs/architecture.md`。

## 总览

| 中间件 | 镜像/版本 | 端口 | 用途 | 接入状态 |
|---|---|---|---|---|
| PostgreSQL | `postgres:16` | 5432 | 唯一业务事实源，多 schema 隔离 | Compose 已提供；驱动 + Flyway 已引入（auth） |
| Redis | `redis:7-alpine` | 6379 | 短期状态：令牌、幂等、限流、心跳、锁 | Compose 已提供；Starter 未接入 |
| RocketMQ | `apache/rocketmq:5.3.1` | 9876 | 服务间异步事件总线（7 个 Topic） | Compose 已提供；Starter 未接入 |
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

**功能**：

- 令牌失效状态（登出/踢下线）；
- 写操作 `Idempotency-Key` 幂等键；
- 限流计数（接口频控、登录失败锁定）；
- 考生会话心跳与会话在线状态；
- WebSocket 节点路由（gateway 多实例时定位推送目标）；
- 评测任务短期锁（防止重复派发）；
- 截图频率控制（`maxPerSession` 等策略的执行计数）。

**约束**：Redis 不是成绩、审计或任何业务事实源——Redis 数据丢失不允许造成业务数据丢失。Spring Data Redis Starter 尚未接入。

## RocketMQ

**用途**：服务间异步事件总线，解耦提交、评测、风控、截图、查重链路。

**功能**：

- 首期 7 个 Topic：`lunar-submission-created`、`lunar-submission-judged`、`lunar-risk-event-created`、`lunar-screenshot-requested`、`lunar-screenshot-created`、`lunar-plagiarism-requested`、`lunar-plagiarism-completed`（生产者/消费者矩阵见 `docs/architecture.md` 第 5 节）；
- 事件统一信封：`eventId`、`eventType`、`aggregateId`、`occurredAt`、`schemaVersion`、`traceId`；
- 与 PostgreSQL Outbox 配合实现可靠发布；发布失败进死信表，支持管理员重放。

**约束**：消费者必须按 `eventId` 幂等；重复消息不能把提交状态从终态改回运行态。

**本地部署**：Compose 已包含 NameServer + broker（同为 `apache/rocketmq:5.3.1`）。broker 配置在 `docker/rocketmq/broker.conf`：单主 `ASYNC_MASTER`、堆内存调到 512m、`brokerIP1=127.0.0.1`（Spring 服务跑在宿主机的前提——否则 broker 向 NameServer 注册容器内网 IP，宿主机客户端连不上；服务容器化后注释掉该行即可）、开发期 `autoCreateTopicEnable=true`（生产必须关闭并显式建 Topic）。

## Nacos

**用途**：服务发现与动态配置中心（预留，当前无服务注册）。

**功能**：

- 服务注册与发现：后续 gateway 按服务名路由、服务间调用寻址；
- 动态配置：限流阈值、策略开关等配置的热更新。

**约束**：根 POM 目前只保留 `nacos.version=2025.0.0.0`（对应 Spring Cloud Alibaba BOM 版本）属性，尚未引入任何 Nacos Starter；本地 Compose 以 standalone 模式运行。

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
