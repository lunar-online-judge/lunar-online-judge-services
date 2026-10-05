# 服务职责说明

本文档回答"每个服务负责什么、不负责什么、数据存在哪里、现在实现到哪一步"。完整的数据流、状态机和安全边界见 `docs/architecture.md`；接口约定见 `docs/api.md`；考试规则见 `docs/exam-process.md`；端口与启动见 `docs/deployment.md`。

## 总览

| 服务 | 默认端口 | 一句话职责 | 数据归属 | 当前状态 |
|---|---|---|---|---|
| gateway-service | 8080* | 统一入口：路由、追踪、限流、鉴权转发、WebSocket | 无独立数据库；Redis 存节点路由/限流计数 | 仅占位 POM，无代码 |
| auth-service | 8081 | 用户、登录、JWT 签发、RBAC、竞赛授权、服务间身份 | `auth_schema` | Mock 登录接口 |
| contest-service | 8082 | 竞赛生命周期、报名名单、考生会话、监考策略、排名 | `contest_schema` | Mock 创建竞赛接口 |
| problem-service | 8083 | 题目、题面、测试点、判题限制、语言白名单、题目版本 | `problem_schema` | Mock 接口 |
| submission-service | 8084 | 接收提交、幂等校验、提交状态、成绩读模型 | `submission_schema` | Mock 接口 |
| judge-service | 8080* | 评测调度：消费提交事件、适配 go-judge、verdict 归一化 | `judge_schema` | MockJudgeEngine 可运行 |
| anti-cheat-service | 8080* | 风险事件、风险分数、查重任务、人工审核 | `anti_cheat_schema` | 仅占位 POM，无代码 |
| media-service | 8080* | 监考令牌、WebRTC 信令、截图策略与存取授权 | `media_schema` | 仅占位 POM，无代码 |
| audit-service | 8080* | 管理操作与敏感访问审计 | `audit_schema` | 仅占位 POM，无代码 |

\* 无 `application.yml` 的服务使用 Spring 默认端口 8080，本地同时启动时必须分别设置 `SERVER_PORT`，详见 `docs/deployment.md`。

权限模型：角色只有四类（管理员、老师、监考员、考生，见 `lunar-common` 的 `Role`）。角色不是最终权限，任何访问必须同时满足角色、竞赛授权、考生会话、时间窗口和资源范围。认证方案：auth-service 签发短期 JWT，其余服务作为 OAuth2 资源服务器校验（Spring Security 6.5）。

各服务之间只通过两类通道协作：同步 REST（经 gateway）和 RocketMQ 事件（Topic 与语义见 `docs/architecture.md` 第 5 节）。服务禁止跨 schema 查库。

## 服务间调用关系

```text
客户端/管理端 ──> gateway ──> 各业务服务

contest ──> auth       创建/重置学生账号、绑定授权（服务间身份 + 幂等键）
contest ──> problem     创建竞赛时绑定题目版本和语言策略
contest ──> auth       创建竞赛时绑定老师/监考员授权
contest ──> audit       创建、授权、名单变更留痕
auth    <── 所有资源服务  JWT 校验（资源服务器本地验签，不回源）

事件链路（RocketMQ）：
submission ──(submission.created)──> judge、anti-cheat
judge ──(submission.judged)──> submission、contest、gateway
media/anti-cheat ──(risk-event-created)──> anti-cheat、gateway、audit
media ──(screenshot-created)──> anti-cheat、audit、gateway
anti-cheat ──(plagiarism-requested)──> plagiarism-worker（独立部署）
```

## gateway-service

统一入口，本身不含业务逻辑。

- 职责：请求路由到各服务；为每个请求注入/传递 `X-Request-Id`；基础限流；鉴权转发（把身份上下文传给下游）；WebSocket 入口与推送。
- 不负责：题目、成绩、风控等业务判断；不转发媒体 RTP 数据。
- 数据与基础设施：无数据库；Redis 保存 WebSocket 节点路由和限流计数（Redis 不是任何业务事实源）。
- 对外接口：`/api/v1/**` 反向代理与 `/ws/v1/contests/{contestId}` WebSocket；消费 `lunar-submission-judged`、`lunar-risk-event-created`、`lunar-screenshot-created` 推送。
- 当前实现：只有占位 POM。注意当前是普通 `spring-web` 应用，尚未引入 Spring Cloud Gateway；后续实现网关时需先决定是否切换到响应式网关。
- 下一步：确定网关技术选型（Spring Cloud Gateway vs 手写过滤器链）、接入路由与 `X-Request-Id`、实现 WebSocket 会话路由。

## auth-service

用户与认证的唯一事实源。

- 职责：用户账号管理（含老师为学生创建/重置账号，`studentNo` 组织内唯一，`username` 全局唯一）；登录认证；签发短期 Access Token（JWT）；角色与 RBAC；竞赛授权关系（把老师/监考员授权到具体竞赛）；服务间身份；密码只持久化 BCrypt 哈希（后续可选升级 Argon2id），`passwordChangeRequired` 强制首次登录改密；登录失败限流与短暂锁定。
- 不负责：资源级权限判定——RBAC 只解决"能否访问"，竞赛范围由 contest-service 和监考策略继续校验；不保存报名关系（那是 contest-service 的数据）；不返回密码或哈希。
- 数据与基础设施：`auth_schema.users`（字段设计见 `docs/architecture.md` §2.1.2）；Redis 存令牌黑名单/失效状态。
- 对外接口：`POST /api/v1/auth/login`（已实现，mock）；规划中还有学生账号管理服务间接口（供 contest-service 调用，见 `docs/api.md`）。
- 当前实现：`AuthServiceApplication`、`AuthController`（mock——按用户名硬编码角色、返回 `"mock-access-token"`）。已挂 `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server` 依赖，尚无 `SecurityFilterChain`、`JwtEncoder` 和用户表实现。
- 下一步：用户表迁移 + `JwtEncoder` 真实签发 + 密码哈希 + 登录限流，这是整个平台实现顺序的第一位。具体实现路径见 `docs/development-guide.md` 第 5 节。

## contest-service

竞赛业务的核心，考试规则的载体。

- 职责：竞赛生命周期（创建→审核→发布→进行→结束→归档，见 `docs/exam-process.md` §1）；参赛名单与报名关系（`ENROLLED/DISABLED/REMOVED`，含老师添加学生流程的编排与补偿）；考生会话（进入前创建/恢复服务端会话，校验名单、时间窗口、重复登录、客户端版本）；开始/结束时间（统一结束时间关闭进入与提交，学生没有主动交卷接口）；监考策略与截图策略；随机题目分配（发布时生成，保存随机种子，见 `docs/exam-process.md` §3）；公告；排名规则与排名更新。
- 不负责：学生账号本身（归 auth-service）；题目内容与测试数据（只引用 problem-service 的题目版本）；评测。
- 数据与基础设施：`contest_schema.contest_candidates` 与竞赛、会话、分配表（字段设计见 `docs/architecture.md` §2.1.2）；Redis 存会话心跳。
- 对外接口：`POST /api/v1/contests`、`GET /api/v1/contests/{id}`（已实现，mock）；规划：审核/发布、公告、学生名单 CRUD、密码重置、题目分配查询、成绩查询（见 `docs/api.md`）；消费 `lunar-submission-judged` 更新排名。
- 当前实现：`ContestServiceApplication`、`ContestController`（内存 Map 模拟创建竞赛，固定 DRAFT 状态，无持久化、无状态机）。
- 下一步：考试状态机 + `contest_candidates` 表 + 会话模型（对应实现顺序第 3 步）。

## problem-service

题目与判题元数据的唯一事实源。

- 职责：题目与题面；样例；测试点与测试数据；判题限制（CPU/墙钟/内存/输出）；语言白名单与执行模板（C、C++、Java、Python、Go、JavaScript/TypeScript，TypeScript 需先 tsc 编译）；题目版本管理（发布后不原地修改）。
- 不负责：测试数据绝不能通过公开题目 API 返回给考生。
- 数据与基础设施：`problem_schema`。
- 对外接口：`POST /api/v1/problems`、`GET /api/v1/problems/{id}`（已实现，mock）；题目版本/测试点管理接口规划中。
- 当前实现：`ProblemServiceApplication`、`ProblemController`。
- 下一步：题目与版本表、测试点对象存储引用、语言白名单配置。

## submission-service

提交入口与成绩读模型，评测链路的起点。

- 职责：接收代码提交（`Idempotency-Key` 幂等）；提交前五项校验（会话有效、竞赛进行中、题目属于竞赛、语言允许、次数未超限）；本地事务写入 `submission(status=QUEUED)` 与 Outbox，发布 `lunar-submission-created`；提交状态展示（`GET /{id}`）；消费 `lunar-submission-judged` 更新成绩读模型；保存源码快照引用供查重。
- 不负责：不直接调用 go-judge，也不了解评测细节。
- 数据与基础设施：`submission_schema`；PostgreSQL Outbox 表；Redis 幂等键。
- 对外接口：`POST /api/v1/submissions`、`GET /api/v1/submissions/{id}`（已实现，mock）；生产 `lunar-submission-created`、消费 `lunar-submission-judged`。
- 当前实现：`SubmissionServiceApplication`、`SubmissionController`。
- 下一步：提交表 + Outbox + RocketMQ Starter（实现顺序第 4 步）。

## judge-service

平台评测状态机的唯一拥有者。

- 职责：消费 `lunar-submission-created`，创建 `judge_task(status=DISPATCHING)`；通过 `JudgeEngine` 抽象（定义在 lunar-common）经 `GoJudgeAdapter` 调用独立 Linux 节点上的 go-judge 沙箱；重试、超时补偿、go-judge 不可用时指数退避、超限转 `SYSTEM_ERROR`；verdict 归一化为 10 种标准结果（ACCEPTED、WRONG_ANSWER、COMPILE_ERROR、TLE、MLE、RE、OLE 等）；发布 `lunar-submission-judged`。
- 不负责：绝不在 Spring 容器内执行用户代码；不向 go-judge 传递数据库凭据、MQ 地址或宿主机路径；源码与测试数据只在评测任务临时目录存在，任务结束即清理。
- 数据与基础设施：`judge_schema`；Redis 评测短期锁；go-judge 节点（独立 Linux，通过 `GO_JUDGE_BASE_URL` 配置）。
- 对外接口：`POST /api/v1/judge/submissions`（已实现，走 Mock 引擎）；生产 `lunar-submission-judged`、消费 `lunar-submission-created`。
- 当前实现：`JudgeServiceApplication`、`JudgeController`、`MockJudgeEngine`（mock profile）；`GoJudgeAdapter`（`infra/`，设置 `GO_JUDGE_BASE_URL` 即启用且优先于 mock）翻译 go-judge `POST /run` 协议并归一化 verdict，内置 C/C++/Java/Python 语言模板，10 个适配器单测通过；go-judge 容器由 `docker/compose.judge.yml` 提供。唯一启用 spring-boot-maven-plugin 的模块。
- 下一步：go-judge 容器实测（含镜像工具链确认）、评测任务表 + RocketMQ 消费 `lunar-submission-created` 的异步状态机（实现顺序第 4/5 步）。

## anti-cheat-service

风控证据与人工审核，不裁决成绩。

- 职责：接收风险事件（失焦/切屏、剪贴板、可疑进程、调试器特征、媒体断流等），计算风险等级并经 WebSocket 告警；按竞赛/题目/语言聚合提交并发起 JPlag 查重任务；保存相似度报告与阈值命中；人工审核状态机 `OPEN → ACKNOWLEDGED → CONFIRMED / DISMISSED`。
- 不负责：不能直接修改已发布成绩；单一风险事件不能自动判定作弊，处罚必须走独立人工裁决并审计。
- 数据与基础设施：`anti_cheat_schema`；风险事件检索首期直接查 PostgreSQL（Elasticsearch 暂不使用）。
- 对外接口：规划 `GET/POST /api/v1/monitor/events*`（风险事件查询与审核，见 `docs/api.md`）；消费 `lunar-submission-created`、`lunar-risk-event-created`、`lunar-screenshot-created`、`lunar-plagiarism-completed`；生产 `lunar-risk-event-created`（与 media 共同）、`lunar-screenshot-requested`、`lunar-plagiarism-requested`。
- 当前实现：只有占位 POM。
- 下一步：风险事件模型与审核状态机（实现顺序第 7 步）。

## media-service

监考媒体的信令与授权层，媒体数据本身不经过业务服务。

- 职责：签发监考令牌（sessionId、contestId、screenRequired、cameraAllowed、startAt、endAt、nonce）；WebRTC SDP/ICE 信令交换（对接独立 SFU，RTP 媒体流不经过本服务与 gateway）；实时流状态机（`CREATED → AUTHORIZED → CONNECTING → LIVE/EXPIRED/DEGRADED → CLOSED`）；截图策略与三种触发（风险事件/定时/手动，策略字段见 `docs/architecture.md` §2.2）；截图保存到 MinIO 并写元数据与过期时间；媒体与截图访问授权（校验竞赛授权 + 短期签名 URL）。
- 不负责：不保存、不回放连续视频；不转发高带宽 RTP。
- 数据与基础设施：`media_schema`；MinIO（截图对象，不可猜测键）；Redis 截图频率控制。
- 对外接口：规划 `POST /api/v1/contests/{contestId}/sessions/{sessionId}/monitor-token`、`POST /api/v1/monitor/screenshot`（见 `docs/api.md`）；消费 `lunar-screenshot-requested`；生产 `lunar-screenshot-created`、`lunar-risk-event-created`（与 anti-cheat 共同）。
- 当前实现：只有占位 POM。
- 下一步：信令服务选型与 SFU 对接（实现顺序第 6 步）。

## audit-service

审计记录的追加式账本。

- 职责：记录管理员操作、权限变更、账号管理操作（创建/重置/禁用/移出学生）、实时流查看、截图查看与创建、人工审核动作、系统配置变更。
- 不负责：普通业务接口不能删除审计记录；不记录密码及任何敏感明文。
- 数据与基础设施：`audit_schema`；风险事件与审计的检索首期直接查 PostgreSQL。
- 对外接口：规划 `GET /api/v1/audit/media-access`（媒体访问审计查询，见 `docs/api.md`）；消费 `lunar-risk-event-created`、`lunar-screenshot-created`、`lunar-plagiarism-completed` 等留痕事件。
- 当前实现：只有占位 POM。
- 下一步：审计表与写入 API（随各服务接入逐步建设）。

## 公共模块（非独立服务）

- `lunar-common`：统一 API 响应 `ApiResponse`（`requestId/data/error` 结构，错误为 `code + message`）、请求追踪 `RequestIdFilter`（`X-Request-Id` 透传/生成）、角色枚举 `Role`（ADMIN/CANDIDATE/INVIGILATOR/TEACHER）、评测引擎抽象 `JudgeEngine`（`submit/query/cancel`）及其判题模型。
- `lunar-contracts`：跨服务契约对象（如 `GoJudgeSubmissionRequest`），供服务间与事件使用。
- `plagiarism-worker`（规划中，仓库暂无模块）：独立异步 JPlag 查重 worker，与主服务隔离部署，消费 `lunar-plagiarism-requested`、生产 `lunar-plagiarism-completed`。

## 实现顺序

与 `docs/architecture.md` §15 一致，服务建设的推荐顺序：

1. auth-service（用户、登录、RBAC、JWT）；
2. 各服务 PostgreSQL schema、迁移和 MyBatis-Plus 基础配置；
3. contest / problem / submission 核心领域和状态机；
4. RocketMQ Outbox、Topic 和幂等消费；
5. `GoJudgeAdapter`、语言模板和评测节点集成测试；
6. WebSocket 推送和 WebRTC 信令（gateway + media）；
7. 风险事件、截图 MinIO 流程和人工审核（anti-cheat + media）；
8. JPlag worker、相似度报告和审计；
9. 集成测试、压测、沙箱安全测试和生产加固。
