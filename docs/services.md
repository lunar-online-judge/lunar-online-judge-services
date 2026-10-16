# 服务职责说明

本文档回答"每个服务负责什么、不负责什么、数据存在哪里、现在实现到哪一步"。完整的数据流、状态机和安全边界见 `docs/architecture.md`；接口约定见 `docs/api.md`；考试规则见 `docs/exam-process.md`；端口与启动见 `docs/deployment.md`。

## 总览

| 服务 | 默认端口 | 一句话职责 | 数据归属 | 当前状态 |
|---|---|---|---|---|
| gateway-service | 8080 | 统一入口：路由、追踪、鉴权转发、WebSocket | 无独立数据库（无 Redis 依赖） | 8 条路由前缀已实现（含 `/api/v1/ai/**`、`/api/v1/analytics/**`）；WebSocket 与基础限流未实现 |
| auth-service | 8081 | 用户、登录、JWT 签发、RBAC、竞赛授权、服务间身份 | `auth_schema`；Redis 存登录失败限流计数 | 登录/JWKS/学生账号服务间接口已实现（真实 BCrypt + RS256 + Redis 限流）；竞赛授权与令牌吊销未实现 |
| contest-service | 8082 | 竞赛生命周期、报名名单、考生会话、监考策略、排名 | `contest_schema`（会话心跳未迁 Redis，DB 为权威） | 生命周期 6 状态（含时间自动推进）/名单/会话/公告已实现；随机分配、排名、监考策略、成绩查询未实现 |
| problem-service | 8083 | 题目、题面、测试点、判题限制、语言白名单、题目版本 | `problem_schema` | 题目 + 版本 + 测试点已实现（发布后版本不可变）；执行模板与"供 judge 取测试点"的服务间接口未实现 |
| submission-service | 8084 | 接收提交、幂等校验、提交状态 | `submission_schema`；幂等键以 DB 唯一约束为权威 | 提交/幂等/Outbox/判决回写已实现；五项校验实现 4 项，**成绩读模型（排名/最高分聚合）已由 analytics-service 承接**，submission 自身只维护提交状态 |
| judge-service | 8080* | 评测调度：消费提交事件、适配 go-judge、verdict 归一化 | `judge_schema`（只一张 `outbox_events`：判决先落本地再异步投递） | 事件消费 + verdict 归一化 + go-judge 适配器 + **判决 Outbox（含退避/死信）**已实现；`judge_task` 异步状态机/超时补偿未实现 |
| anti-cheat-service | 8086 | 风险事件、风险分数、查重任务、人工审核 | `anti_cheat_schema` | 风险事件上报/查询/审核已实现（2026-10-07）；查重与告警推送未实现 |
| media-service | 8080* | 监考令牌、WebRTC 信令、截图策略与存取授权 | `media_schema`（未建） | 仅占位 POM，无代码 |
| audit-service | 8087 | 管理操作与敏感访问审计 | `audit_schema` | 账本写入/查询已实现；**contest / auth / submission / anti-cheat 四个调用方已接入**（共用 lunar-common 的 `AuditRecorder`） |
| ai-service | 8089 | AI 能力：代码评审、出题草稿（风险研判与语义查重已按简化决定删除），**只产证据不改事实** | `ai_schema`（作业队列、结论、草稿、相似对、Outbox） | 2 项作业能力已实现（LangChain4j Provider 层、异步作业管道含租约/退避/原子配额、BE-01 范围）；审计留痕改为结构化日志（Outbox 与隐私守卫已删） |
| analytics-service | 8088 | 分析读模型：消费事件把事实投影进自己的 schema，提供成绩排名、学情与监考风险查询，**只读自己的 schema、不影响任何业务不变量** | `analytics_schema`（事实表、物化成绩表、参照数据缓存、幂等闸门，全部可由 MQ 重放重建） | 4 个消费者 + 三条读模型 + 9 个端点均已实现；模块内设计文档 `docs/analytics/read-model.md` |

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
analytics ──> contest    考试快照（取考试归属做范围判断 + 刷新参照数据缓存，服务身份）
auth    <── 所有资源服务  JWT 校验（资源服务器本地验签，不回源）

事件链路（RocketMQ）：
submission ──(submission.created)──> judge、anti-cheat
judge ──(submission.judged)──> submission、analytics、contest、gateway
media/anti-cheat ──(risk-event-created)──> analytics、anti-cheat、gateway、audit
anti-cheat ──(risk-event-reviewed)──> analytics（审核结论就地修正）
media ──(screenshot-created)──> anti-cheat、audit、gateway
anti-cheat ──(plagiarism-requested)──> plagiarism-worker（独立部署）
ai ──(ai-finding-created)──> analytics（**已消费**）；anti-cheat 未消费
submission/anti-cheat/ai ──(audit-recorded)──> audit（**已生产，audit 已消费**）
```

**当前已实现的调用（Spring 原生声明式 HTTP 接口 `@HttpExchange`，内网直连不经网关）**：contest→auth（学生账号创建/查询/重置，`AuthServiceApi`；**服务身份令牌，`ServiceTokenApi`**）、submission→contest（提交判定，`ContestServiceApi`）、submission→problem（题目当前版本 `ProblemServiceApi` 与判题测试点 `ProblemInternalApi`）、**ai→submission / problem / contest / anti-cheat（AI 取数，见下方 ai-service 节）**、**analytics→contest（考试快照：取考试归属做范围判断 + 刷新参照数据缓存，服务身份，见下方 analytics-service 节）**、**contest→audit、auth→audit、submission→audit、anti-cheat→audit（审计留痕，四个调用方共用 lunar-common 的 `AuditServiceApi` + 尽力而为的 `AuditRecorder`）**。上图其余箭头（contest→problem、contest/gateway 作为事件消费方）尚未实现。**事件消费者代码已落地的只有 4 个服务**：judge 消费 `lunar-submission-created`、submission 消费 `lunar-submission-judged`、analytics 消费 `lunar-submission-judged` / `lunar-risk-event-created` / `lunar-risk-event-reviewed` / `lunar-ai-finding-created`、audit 消费 `lunar-audit-recorded`（anti-cheat / gateway / media 目前没有消费者代码）。

**令牌来源分两种**：有用户上下文（老师/考生发起的动作）**转发用户 Bearer**，账本因此能记到真实 actor；没有用户上下文（contest 的定时任务、submission 的 MQ 消费者、**ai 的作业 worker**、**analytics 的参照数据刷新**）**用服务身份令牌**（`POST /api/v1/internal/service-tokens` 以 clientId+clientSecret 换取 `roles=["SERVICE"]`、`sub=服务名`、默认 10 分钟 TTL），`AuditRecorder` 在操作者令牌为空时自动回落。客户端密钥由环境变量注入（`SERVICE_CLIENT_SECRET*`），默认值仅供本地联调。

## gateway-service

统一入口，本身不含业务逻辑。

- 职责：请求路由到各服务；为每个请求注入/传递 `X-Request-Id`；基础限流；鉴权转发（把身份上下文传给下游）；WebSocket 入口与推送。
- 不负责：题目、成绩、风控等业务判断；不转发媒体 RTP 数据。
- 数据与基础设施：无数据库；Redis 保存 WebSocket 节点路由和限流计数（Redis 不是任何业务事实源）。
- 对外接口：`/api/v1/**` 反向代理（8 条路由前缀）；`/ws/v1/contests/{contestId}` WebSocket **尚未实现**（未配 `/ws/v1/**` 路由，也没有 RocketMQ 依赖），"消费 `lunar-submission-judged`、`lunar-risk-event-created`、`lunar-screenshot-created` 推送"属规划。
- 当前实现（2026-10-07）：**选型已定为 Spring Cloud Gateway（响应式）**——需 WebSocket 入口能力与限流/重试生态；路由 8 前缀（auth/contests/problems/submissions/monitor/audit/ai/analytics，上游地址可 env 覆盖），JWT 与 `X-Request-Id` 默认透传（验签在各资源服务）；`/api/v1/internal/**` 刻意不配置路由（经网关不可达，服务间调用走内网直连）；真机验证经 OpenResty:80 → 网关 → 四服务全链路 + internal 隔离 + 未认证 401。纯路由配置无单测（配置即代码）。
- 下一步：WebSocket 入口（`/ws/v1/**` 路由随会话推送特性）；基础限流（RequestRateLimiter + Redis）；SpringDoc 文档网关聚合（可选）。

## auth-service

用户与认证的唯一事实源。

- 职责：用户账号管理（含老师为学生创建/重置账号，`studentNo` 组织内唯一，`username` 全局唯一）；登录认证；签发短期 Access Token（JWT）；角色与 RBAC；竞赛授权关系（把老师/监考员授权到具体竞赛，**未实现**）；服务间身份；密码只持久化 BCrypt 哈希（后续可选升级 Argon2id），`passwordChangeRequired`（**当前仅作为响应字段返回，服务端未强制改密**）；登录失败限流与短暂锁定（已迁 Redis）。
- 不负责：资源级权限判定——RBAC 只解决"能否访问"，竞赛范围由 contest-service 和监考策略继续校验；不保存报名关系（那是 contest-service 的数据）；不返回密码或哈希。
- 数据与基础设施：`auth_schema.users`（字段设计见 `docs/architecture.md` §2.1.2）；Redis 存登录失败限流计数（令牌黑名单/失效状态尚未实现）。
- 对外接口：`POST /api/v1/auth/login`、`GET /api/v1/auth/jwks`（均已实现，真实校验）；规划中还有学生账号管理服务间接口（供 contest-service 调用，见 `docs/api.md`）。
- 当前实现（2026-10-05）：`SecurityConfig` 基线（STATELESS、放行 login/jwks、其余 401+Bearer 入口点）；MyBatis-Plus `UserMapper` 查 `auth_schema.users`；`AuthService` 编排（限流检查→用户加载→BCrypt 校验→ACTIVE 校验→签发）；RS256 `JwtTokenService`（claims 契约见 api.md）+ JWKS 公钥分发（`AUTH_JWT_PRIVATE_KEY` 未配置时临时生成开发密钥）；`LoginRateLimiter` 失败限流已迁 Redis（5 次锁 5 分钟，多实例共享、TTL 自动清理、Redis 不可用时 fail-open）；V3 迁移含开发种子管理员（`AUTH_DEV_SEED` 守卫）。**生产配置门禁（2026-10，BE-02）**：`prod` profile 下 `ProductionConfigGuard` 在就绪前校验（必须有持久 RSA 私钥、开发种子必须关闭、数据库口令/客户端密钥不得是开发默认、配置中心 fail-fast），不通过直接启动失败；**密钥轮换**：JWKS 与内部验签同时接受当前密钥与 `auth.jwt.retired-public-keys` 的退役公钥，旧 token 在 TTL 内仍有效。32 个单测通过（含对真实 Redis 的集成用例与密钥轮换用例），真实库实测通过。
- 服务间接口（2026-10-06）：`/api/v1/internal/students`——创建学生账号（CANDIDATE 固定角色，BCrypt，幂等靠唯一约束）、按学号/账户名查询、重置密码；鉴权 = 操作者（老师/管理员）令牌由 contest 转发，auth 以自身资源服务器验签；审计：账号创建/密码重置同时写 audit-service 账本（lunar-common 的 `AuditRecorder`，尽力而为）与结构化日志。**服务身份令牌（2026-10）**：`POST /api/v1/internal/service-tokens` 以 clientId+clientSecret 换 `roles=["SERVICE"]` 的短 TTL 令牌（免认证端点，凭证在请求体），供无用户上下文的调用方使用；客户端注册表在 `auth.service-clients.secrets`（部署期配置，生产用环境变量注入）。
- 下一步：令牌吊销（Redis 黑名单）；接口频控；服务客户端注册表迁配置中心/库表（当前在 `application.yml` + 环境变量）。

## contest-service

竞赛业务的核心，考试规则的载体。

- 职责：竞赛生命周期（创建→审核→发布→进行→结束→归档，见 `docs/exam-process.md` §1）；参赛名单与报名关系（**代码中实际只有 `ENROLLED`/`REMOVED`，`DISABLED` 未使用**；含老师添加学生流程的编排，**补偿机制未实现**）；考生会话（进入前创建/恢复服务端会话，校验名单、状态与时间窗口；管理端可终止会话（TERMINATED 终态），移出名单时会话同时失效；**重复登录踢设备与客户端版本校验未实现**）；开始/结束时间（统一结束时间关闭进入与提交，学生没有主动交卷接口）；监考策略与截图策略（**未实现**）；随机题目分配（**未实现**，见 `docs/exam-process.md` §3）；公告（全体公告已实现，指定题目公告未实现）；排名规则与排名更新（**未实现**）。
- 不负责：学生账号本身（归 auth-service）；题目内容与测试数据（只引用 problem-service 的题目版本）；评测。
- 数据与基础设施：`contest_schema` 的 contests / contest_candidates / exam_sessions / announcements / announcement_confirms（字段设计见 `docs/architecture.md` §2.1.2；**题目分配表不存在**）；会话心跳以 DB 为权威、**未迁 Redis**（理由见 `docs/middleware.md`）。
- 对外接口：`POST /api/v1/contests`、`GET /api/v1/contests`、`GET /{id}`、`POST /{id}/review`、`POST /{id}/publish`、`POST /{id}/archive`，以及名单增删查、CSV 批量导入、密码重置、公告发布/拉取/确认、会话进入与心跳、管理端终止考生会话（**均已实现**，JWT 验签 + 角色控制：创建/审核/名单/公告为老师或管理员，发布与归档仅管理员）；**规划**：题目分配查询、成绩查询；**"消费 `lunar-submission-judged` 更新排名"尚未实现**（contest 无 RocketMQ 依赖）。
- 当前实现（2026-10-06）：**首个 OAuth2 资源服务器**——JWT 经 auth-service JWKS 本地验签（roles claim → ROLE_ authorities，`AUTH_JWK_SET_URI` 可覆盖）；`contest_schema.contests` 落库（MyBatis-Plus，独立 Flyway 历史表 `contest_history`）；生命周期状态机（`ContestStatus` 单向流转 + 条件更新防并发双审，RUNNING/FINISHED/ARCHIVED 的触发随时间管理特性落地）。**资源范围（2026-10，BE-01）**：`owner_teacher_id` 表达"谁有权管理"（迁移按 `created_by` 回填，回填不到仅 ADMIN 可见），管理端每个入口先做范围校验、越权按 404 隐藏存在，列表 total 也在范围内；考生侧按"是否已报名"放行。
- 学生名单（2026-10-06）：`contest_schema.contest_candidates` 报名关系（唯一约束 contest_id+student_id，学号/账户名/显示名快照）；添加学生（auth 账号不存在则创建、存在则复用；REMOVED 行重新加入时激活）、**CSV 批量导入（multipart 上传，逐行等价于添加学生，逐行返回成功/失败原因，部分失败不回滚）**、分页名单（keyword 查学号）、重置密码（学生须在名单中）、移出（软删除 REMOVED）；重复报名 409、重复/未报名移出 403。**审计**：加入/重新加入/重置密码/移出四个动作写 audit-service 账本（`AuditRecorder` 尽力而为，失败不阻断；结构化日志同时保留兜底）。
- 考生会话（2026-10-06）：`contest_schema.exam_sessions`（唯一约束 contest_id+student_id）；进入（名单+状态+时间窗口校验）、心跳、断线重连恢复、TERMINATED 终态拒绝重进；心跳超时（默认 2 分钟，`exam.session.heartbeat-timeout-minutes`）由 @Scheduled 扫描置 EXPIRED（条件更新多实例幂等）；管理端终止会话（`POST /{contestId}/students/{studentId}/session/terminate`，条件更新）与"移出名单即失效"（§9）已实现；重复登录踢设备的设备模型随客户端版本字段落地。
- 状态推进与归档（2026-10-06）：PUBLISHED→RUNNING→FINISHED 由 `ContestScheduler` 按时间窗口自动推进（30s 周期，条件更新多实例幂等，无窗口的考试不参与）；心跳复核考试状态/窗口/名单；`POST /{id}/archive` 管理员归档（FINISHED→ARCHIVED）。**审计**：状态推进写账本（定时任务无用户令牌 → 服务身份令牌，账本 actor=contest-service、role=SERVICE）。
- 服务间快照（2026-10）：`GET /api/v1/internal/contests/{contestId}/snapshot`（**只认服务身份** `roles=["SERVICE"]`）——一次给出考试元数据、全员名单（含 `REMOVED`，过滤留给使用方）与锁定题目版本，供分析型消费者定时拉取参照数据。已有的 `/internal/contests/{id}/session` 是**考生令牌**语义（判 `canSubmit`），分析服务用不了；实现见 `ContestSnapshotService`（单独成组件：它跨考试/名单/锁定题目三张表，塞进 `ContestService` 会让那个类同时承担生命周期规则与参照数据投影两件事）。
- 公告（2026-10-06）：发布/考生拉取即送达/考生确认（幂等），表 announcements + announcement_confirms；指定题目公告与监考端确认情况查询随 problem/监考功能扩展。
- 下一步：成绩查询与读模型（依赖评测链路）；状态变更事件推送 gateway（事件总线阶段）。

## problem-service

题目与判题元数据的唯一事实源。

- 职责：题目与题面；样例；测试点与测试数据；判题限制（CPU/墙钟/内存/输出）；语言白名单与执行模板（C、C++、Java、Python、Go、JavaScript/TypeScript，TypeScript 需先 tsc 编译）；题目版本管理（发布后不原地修改）。
- 不负责：测试数据绝不能通过公开题目 API 返回给考生。
- 数据与基础设施：`problem_schema`。
- 对外接口：`POST /api/v1/problems`（题目+首版本+测试点）、`GET /{id}`（考生视角：当前发布版本，**不含测试点**）、`POST /{id}/versions`（新版本，可复制已有版本）、`GET /{id}/versions`、`POST /{id}/versions/{versionId}/publish`、`GET /{id}/versions/{versionId}/testcases`（仅老师/管理员/判题链路）。
- 当前实现（2026-10-06）：`problem_schema` 落库（problems + problem_versions + testcases，独立 Flyway 历史表 `problem_history`）；版本模型——创建即 v1 草稿，发布后不可变，改动建新版本（复制+覆盖，`baseVersionId` 缺省时基于当前版本），发布时切换 `current_version_id`；判题限制（CPU/内存/输出）与语言白名单（逗号分隔）；JWT 资源服务器 + 角色控制；11 个单测 + 真机闭环（含"考生视图无测试点"安全约束验证）。**服务间测试点接口已落地**（`GET /api/v1/internal/problems/{problemId}/versions/{versionId}/testcases`，不经网关、**只认服务身份 `roles=["SERVICE"]`**、带体积上限 413）。**资源范围（2026-10，BE-01）**：`owner_teacher_id`（迁移按 `created_by` 回填）+ 列表/新版本/版本列表/发布/测试点入口的范围校验，越权按 404 隐藏存在；题目详情已分层授权：老师/管理员按题目归属校验（越权 404），考生只能读到已发布版本；"考生只读本场分配给他的题"仍需 contest 侧按 (考生, 题目) 反查，列为待办。测试点数据 v1 直接落库，大文件对象存储迁移留后。
- 下一步：测试点对象存储引用（测试点当前随事件进 MQ，受 1 MiB 上限约束）；语言执行模板配置（当前硬编码在 judge 的 `LanguageTemplate`）。

## submission-service

提交入口，评测链路的起点。

- 职责：接收代码提交（`Idempotency-Key` 幂等）；提交前五项校验（会话有效、竞赛进行中、题目属于竞赛、语言允许、次数未超限即频率限制，**当前实现 4 项**，见下）；本地事务写入 `submission(status=QUEUED)` 与 Outbox，发布 `lunar-submission-created`；提交状态展示（`GET /{id}`）；消费 `lunar-submission-judged` 更新提交状态（**"每题最高有效分/排名"的成绩读模型不在本服务，已由 analytics-service 从同一事件独立投影**；判决回写用前态条件更新，重复或乱序的判题结果不会覆盖终态）；保存源码快照引用供查重（**查重未实现**）。
- 不负责：不直接调用 go-judge，也不了解评测细节。
- 数据与基础设施：`submission_schema`（submissions + outbox_events）；**幂等键不存 Redis，以 DB 唯一约束为权威**（理由见 `docs/middleware.md`）。
- 对外接口：`POST /api/v1/submissions`（考生；`Idempotency-Key` 幂等）、`GET /{id}`（学生看自己，老师/管理员可查任意）、`POST /api/v1/internal/submissions/{id}/judged`（判题回写过渡端点）；生产 `lunar-submission-created`、消费 `lunar-submission-judged`。
- 当前实现（2026-10-06）：`submission_schema` 落库（submissions + outbox_events，独立 Flyway 历史表 `submission_history`）；五项校验实现四项——会话有效 + 竞赛进行中（contest 服务间判定 `/api/v1/internal/contests/{id}/session`）、语言白名单 + 判题限制（problem 服务间查询）、提交频率未超限（按学生做窗口计数，阈值经 Nacos `exam.submission.*` 热更新，默认每 60 秒 30 次，超限 429）；「题目属于竞赛」依赖随机分配表，仍未实现并在代码中标注；幂等=同键同指纹返首次、异指纹 409；提交与 Outbox（信封含 traceId）同一事务；判决回写更新状态并写 `lunar-submission-judged` Outbox；**审计**：提交动作写 audit-service 账本（`AuditRecorder` 尽力而为，traceId 透传）；9 个单测 + 真机闭环通过。
- 事件总线（2026-10-07，2026-10 更新）：`OutboxPublisher`（2s 周期扫未发布事件行 → RocketMQTemplate 投递 → 标记 published_at，失败留待重试；**消息体改为完整事件信封**，此前只发内层 payload 因此 `eventId` 传不出去）；`JudgedConsumer` 消费 `lunar-submission-judged` 回写提交状态（成绩与排名由 analytics-service 独立投影同一事件）——**幂等分两层**：信封 `eventId` 走 `submission_schema.consumed_events` 闸门（V2 迁移，`on conflict do nothing` 插入即判断，与状态回写**同一事务**，回滚时登记一起回滚，不会出现"登记了已处理、判决却没落库"的黑洞），无 `eventId` 的旧格式消息退化为终态条件更新（CAS）。**审计**：判决回写写账本（MQ 消费者无用户令牌 → 服务身份令牌，actor=submission-service）。
- 下一步：题目归属校验（依赖随机分配表落地）；提交频率阈值目前是保守默认，正式考试前按考场规模调整 `exam.submission.*`。（"该题最高有效分"聚合与成绩读模型已由 analytics-service 实现，本服务不再持有。）

## judge-service

平台评测状态机的唯一拥有者。

- 职责：消费 `lunar-submission-created`；通过 `JudgeEngine` 抽象（定义在 lunar-common）经 `GoJudgeAdapter` 调用独立 Linux 节点上的 go-judge 沙箱；verdict 归一化为 8 种判题终态（唯一权威是 `lunar-common` 的 `JudgeVerdict`：ACCEPTED、WRONG_ANSWER、COMPILE_ERROR、TIME_LIMIT_EXCEEDED、MEMORY_LIMIT_EXCEEDED、RUNTIME_ERROR、OUTPUT_LIMIT_EXCEEDED、SYSTEM_ERROR，与 `submissions.status` 同名同集合）；发布 `lunar-submission-judged`。**判决投递已改为本地 Outbox**（`judge_schema.outbox_events`，含 attempts/指数退避/死信）：broker 不可用只影响投递时机，不再丢判决。**尚未实现**：`judge_task` 表与 `DISPATCHING/RUNNING/FINISHED` 异步状态机、超时补偿、go-judge 不可用时指数退避、死信重放接口。
- 不负责：绝不在 Spring 容器内执行用户代码；不向 go-judge 传递数据库凭据、MQ 地址或宿主机路径；源码与测试数据只在评测任务临时目录存在，任务结束即清理。
- 数据与基础设施：`judge_schema`（V1 迁移，**只有 `outbox_events` 一张表**——判决先落本地再异步投递；`judge_task` 状态机仍未实现）+ Redis（消费去重与短期状态，见 `infra/ConsumedEventGuard`）；go-judge 节点（独立 Linux，通过 `GO_JUDGE_BASE_URL` 配置）。
- 对外接口：`POST /api/v1/judge/submissions`（代码存在但**当前不可用**：未接入验签，所有请求 401；也不落库、不发事件、测试点恒为空）；生产 `lunar-submission-judged`、消费 `lunar-submission-created`。
- 当前实现：`JudgeServiceApplication`、`JudgeController`、`MockJudgeEngine`（mock profile）；`GoJudgeAdapter`（`infra/`，设置 `GO_JUDGE_BASE_URL` 即启用且优先于 mock）翻译 go-judge `POST /run` 协议并归一化 verdict，内置 C/C++/Java/Python/JavaScript/TypeScript 六种语言模板，14 个适配器单测通过；go-judge 容器由 `docker/compose.judge.yml` 提供（用 `docker/go-judge/Dockerfile` 构建带工具链的镜像）。**真机实测（2026-10）**：五种语言（C++/Python/Java/JS/TS）在同一个 go-judge 实例内编译/运行/比对均正常，C++ 的五类 verdict（ACCEPTED/WRONG_ANSWER/COMPILE_ERROR/RUNTIME_ERROR/TIME_LIMIT_EXCEEDED）均按预期归一化；**测试点已随 `lunar-submission-created` 的 `testFiles` 进入引擎**（消费端 `parseTestFiles` 解析，缺失时返回空表、由引擎判 `SYSTEM_ERROR`），真实引擎因此能出真实 verdict 与按测试点比例的部分分。唯一启用 spring-boot-maven-plugin 的模块。
- 事件总线（2026-10-07，2026-10 更新）：`SubmissionCreatedConsumer` 消费 `lunar-submission-created` → JudgeEngine 执行 → 发布 `lunar-submission-judged`（verdict 归一化 + `passedCases`/`totalCases`，`score` 按测试点通过比例计算，通过全部为满分）；Mock 引擎模拟"提交即判对"供全链路演示，真实引擎 `GO_JUDGE_BASE_URL` 启用；**测试点随事件传输**（submission 经 problem 的服务间接口取到后放进 `testFiles`），真实引擎因此能出真实 verdict；新增 application.yml（rocketmq 配置，SERVER_PORT 仍需显式设置）。积压消息在消费组上线后自动补消费（观测验证）。
- 事件信封与消费去重（2026-10）：`lunar-submission-created` 消费端新增 Redis 去重（`ConsumedEventGuard`，**特意"先查、处理成功后再登记"**——先 SETNX 占坑会在评测中途失败后让重投看到坑位已占而直接 ack，判决永久丢失；fail-open）；judged 事件改为发**完整信封**（带 `eventId`，消费端可真正幂等）并富化 `contestId`/`problemId`/`problemVersionId`/`studentId`/`language`/`submittedAt`——judge 的库只有 Outbox 一张表（没有可放业务身份的读模型），只能把 created 事件里的身份原样带下去，而此前 judged 只有 `submissionId` 与 verdict，**除 submission 之外没有任何服务能独立消费这条事件**。
- 下一步：judge_task 表与异步状态机（重试/超时补偿）；生产镜像锁定 digest 与工具链版本。

## anti-cheat-service

风控证据与人工审核，不裁决成绩。

- 职责：接收风险事件（失焦/切屏、剪贴板、可疑进程、调试器特征、媒体断流等），计算风险等级并经 WebSocket 告警（**WebSocket 告警未实现**）；按竞赛/题目/语言聚合提交并发起 JPlag 查重任务（**未实现**）；保存相似度报告与阈值命中（**未实现**）；人工审核状态机 `OPEN → ACKNOWLEDGED → CONFIRMED / DISMISSED`（**已实现，写入走前态条件更新：并发终审最多一个成功，终态不可再动**）。
- 不负责：不能直接修改已发布成绩；单一风险事件不能自动判定作弊，处罚必须走独立人工裁决并审计。
- 数据与基础设施：`anti_cheat_schema`；风险事件检索首期直接查 PostgreSQL（Elasticsearch 暂不使用）。
- 对外接口：`POST /api/v1/monitor/events`（考生上报）、`GET /api/v1/monitor/events`（监考/管理分页查询）、`POST /api/v1/monitor/events/{eventId}/review`（人工审核）**均已实现**（见 `docs/api.md`）；生产 `lunar-risk-event-created`（经 Outbox）；**消费 `lunar-submission-created`、`lunar-risk-event-created`、`lunar-screenshot-created`、`lunar-plagiarism-completed` 均未实现**（有 rocketmq 依赖但没有任何消费者），规划中的 `lunar-screenshot-requested`、`lunar-plagiarism-requested` 也未生产。
- 当前实现（2026-10-06/07）：`anti_cheat_schema.risk_events` 落库 + Outbox（`lunar-risk-event-created` → gateway 告警的未来消费方）；上报（考生客户端，severity 服务端规则表计算、未知类型保守 MEDIUM）；分页查询（竞赛/学生/等级/审核状态过滤）；审核状态机 OPEN→ACKNOWLEDGED→CONFIRMED/DISMISSED（条件更新防并发，终态保护，动作留痕日志待 audit 迁移）；9 个单测 + 真机闭环通过。
- 审核流转事件（2026-10）：新增 `lunar-risk-event-reviewed`（`previousStatus`/`newStatus`/`reviewedBy`/`reviewedAt`，与审核状态更新**同一事务**）。此前风险事件只在**创建**时进 Outbox，只消费 `created` 的话分析侧的审核结论会永远停留在 `OPEN`；同时给 created 的 payload 补上 `problemId`。
- 下一步：查重任务（JPlag，随 submission 聚合）；告警推送 gateway（WebSocket 阶段）；规则表可配置化；审核动作迁移 audit-service。

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
- 对外接口：`POST /api/v1/internal/audit/records`（服务间写入）与 `GET /api/v1/audit/records`（管理端查询）**均已实现**；规划 `GET /api/v1/audit/media-access`（媒体访问审计查询，见 `docs/api.md`）；**已消费 `lunar-audit-recorded`**（audit-service 引了 rocketmq-spring，`AuditRecordedConsumer` 按 `eventId` 幂等入账——actor 取事件里的审计快照，不重放用户 Bearer，因此令牌过期不会让补偿永远失败；生产者是 submission / anti-cheat / ai 的本地 Outbox），**消费 `lunar-risk-event-created`、`lunar-screenshot-created`、`lunar-plagiarism-completed` 等事件仍未实现**——auth / contest 目前的接入方式仍是经 HTTP 同步写入（lunar-common 的 `AuditServiceApi` + 尽力而为的 `AuditRecorder`，失败不阻断主流程）。
- 当前实现（2026-10-08，2026-10 更新）：`audit_schema.audit_logs` 追加式账本（仅插入，无更新/删除接口；**V3 起 DB 触发器兜底**：任何 `UPDATE`/`DELETE` 一律被拒绝——用触发器而不是 `REVOKE`，因为所有服务共用同一个数据库角色（也是表 owner），对 owner 做 REVOKE 不生效）；服务间写入端点（转发操作者令牌，actor/role 自动记录）+ 管理端分页查询（动作/操作者/竞赛过滤）；**服务身份单独存 `service_id`**（V2 迁移加列：SERVICE 令牌的 `sub` 是服务名而非用户 uuid，塞进 `actor_id` 会被 PostgreSQL 拒绝；此时 `actor_id` 留空、`actor_role=SERVICE`）；动作清单约定（大写下划线自由字符串）：STUDENT_CREATED、STUDENT_PASSWORD_RESET、RISK_EVENT_REVIEWED、EXAM_STARTED、SUBMISSION_JUDGED 等，新增动作自然写入不因校验丢账。
- 接入样本：anti-cheat 审核动作 + contest 名单变更/考试流转 + auth 学生账号 + submission 提交与判决，共 **10 个动作**已接入（`AuditRecorder` 尽力而为，审计服务暂不可用不阻断主流程，条目缺口由服务结构化日志兜底）；其中考试流转与判决回写没有用户令牌，用**服务身份令牌**（role=SERVICE）写入。**ai 的三个动作**（`AI_JOB_REQUESTED` / `AI_JOB_FAILED` / `AI_FINDING_CREATED`）在简化后**只写结构化日志**，不再入审计账本（原走本地 Outbox 的 `lunar-audit-recorded`）。
- 下一步：**BE-07 的持久补偿已部分落地**——submission / anti-cheat / **ai** 的敏感动作改为与本地 audit outbox 同事务提交、异步投递，consumer 按 `eventId` 幂等、带重试退避与 dead-letter；**auth / contest 仍是同步 HTTP 尽力而为**（audit 不可用时会丢条目，只有结构化日志兜底）；媒体访问审计（随 media-service）；审计检索深化。

## ai-service

AI 能力的证据产出方，**只产证据、不改事实**。

- 职责：异步作业管道——两类作业（`CODE_REVIEW` 代码评审、`PROBLEM_DRAFT` 出题草稿）入队后**立即返回作业号**，由 worker 按数据库队列（`for update skip locked` + 租约）领取执行；两类产出统一落 `ai_findings`（`finding_type` 直接取作业类型，不另维护第二套枚举）；出题作业额外落 `problem_drafts` **草稿表**；审计留痕改为结构化日志（成果事件与本地 Outbox 已随简化删除）；每日配额按（天，调用者）原子占用（入队即扣），同输入（含提示词版本）已有成功结论时直接复用，不重复烧 Provider 配额。
- 不负责：**不写成绩、不流转审核状态机、不含处罚建议**——风险研判只给可疑度 + 理由 + 反方证据 + 人工介入方向，`recommendedAction` 只允许 `DISMISS` / `REVIEW_MANUALLY` / `ESCALATE`，模型给出约定外的取值（如 `PUNISH`）时降级为最保守的 `REVIEW_MANUALLY`；不定义 `severity`（那是 anti-cheat 规则表的语言，AI 只有 `score`）；不直接写 `problem_schema`（草稿经教师确认后才走 problem 既有创建接口）；不在 HTTP 请求线程里同步调模型。
- 数据与基础设施：`ai_schema`（`ai_jobs` 作业队列、`ai_findings` 结论、`problem_drafts` 出题草稿、`finding_feedback` 有用性反馈、`ai_quota_counters` 每日配额），独立 Flyway 历史表 `ai_history`；Provider 为**云厂商 OpenAI 兼容端点**（LangChain4j 1.22.0；地址由 `AI_PROVIDER_BASE_URL` 或 `ai.provider.base-url` 给出，未配置时首次调用报错；本地推理栈已废弃），见 `docs/middleware.md`"LLM 推理服务"节；**不使用 Redis**。
- 对外接口（JWT 验签 + 方法级 `@PreAuthorize`，**角色之后还有一层资源范围判断**；完整语义见 `docs/api.md`"AI"节）：`POST /api/v1/ai/jobs/code-review`（TEACHER/ADMIN，必须给 `contestId`）、`POST /api/v1/ai/jobs/problem-draft`（TEACHER/ADMIN，`contestId` 可选，不给则仅 ADMIN）、`GET /api/v1/ai/jobs`、`GET /api/v1/ai/jobs/{jobId}`、`GET /api/v1/ai/findings`、`GET /api/v1/ai/findings/{findingId}`、`GET /api/v1/ai/drafts`、`GET /api/v1/ai/drafts/{draftId}`（列表/详情均为 TEACHER/ADMIN，不传 `contestId` 时仅 ADMIN）、`GET /api/v1/ai/provider`（**仅 ADMIN**，回显 mode/地址主机/模型，不回显 api-key）。**共 9 个端点**，与 `docs/api.md` 端点总表一致。
- 当前实现（2026-10，简化后保留两类作业）：**代码评审**读提交源码 + 题面 + 判题结果，产出错因与改进建议（硬性截断 5 条，**不产生可疑度**——它是教学反馈而不是风险信号）；**出题草稿**产出题面/样例/建议测试点/知识点标签，落草稿表等待教师确认。三层权限：角色（`@PreAuthorize`）→ 资源范围（`AiScopeGuard` 取考试 `ownerTeacherId` 判定，**越权与不存在同样 404**）→ 身份来源（actor/role 只从已验证令牌取，绝不读请求体）。Provider 地址未配置时首次调用即报错（**没有默认端点**）；出境门禁与隐私守卫已随简化删除。
- 下一步：结论有用性反馈接口（`finding_feedback` 表已建）与出题草稿的教师确认入口（`review_status` 目前恒为 `DRAFT`）。

## analytics-service

分析读模型（CQRS 的读侧）：**只消费事件、只写自己的 schema**。业务写路径不经过它，因此它挂了不影响交卷与判题；库里的数据全部可由 MQ 重放重建。模块内设计文档见 `docs/analytics/read-model.md`。

- 职责：把"同一场考试的多次提交、多条风险事件"收敛成人能读的成绩与风险视图。**4 个消费者**（都用 `EventMessages` 解包以兼容旧格式的裸 payload）：`JudgedFactConsumer`（`lunar-submission-judged`）、`RiskEventFactConsumer`（`lunar-risk-event-created`）、`RiskReviewFactConsumer`（`lunar-risk-event-reviewed`）、`AiFindingFactConsumer`（`lunar-ai-finding-created`）；每个消费者都有 `consumed_events` 幂等闸门，**闸门与事实写入在同一事务**。事实表：`submission_facts`（**主键 `submission_id`**——judged 有两个生产者，按提交收敛才能最终一致，`event_id` 只作"最后一次改写者"的追溯）、`risk_event_facts`（主键 `event_id`，风险事件不可变，审核流转就地更新同一行）、`ai_finding_facts`（AI 结论，**只作证据**）；物化表 `student_contest_scores` 存成绩与名次；`contest_refs` / `contest_problem_refs` / `contest_candidate_refs` 是参照数据缓存（考生名单**保留 `REMOVED`**，过滤是读模型的口径）。三条读模型：**成绩排名**（每题取该生最高分、总分=各题最高分之和；名次是 1..N 稠密序号、无并列，排序链可配 `analytics.ranking.tie-break`）、**学情**（按需聚合，不物化）、**监考风险**（按需聚合：类型/等级/审核结论计数 + 学生×时间窗热力图 + 按可疑度降序的学生列表）。
- 不负责：**不写任何业务事实**——不改提交状态、不流转审核状态机、不改成绩，因此不影响任何既有业务不变量；**不跨 schema 查库**（仓库禁令）——需要考试归属/题目/考生名单时走 contest 的服务级快照端点（`GET /api/v1/internal/contests/{contestId}/snapshot`，服务身份）拉回后缓存成参照数据，**HTTP 取数在事务外**；不做跨服务的考试级授权，只在自己的入口按考试归属判范围；**AI 结论的可疑度不进风险排序权重**（模型输出可能被提示注入影响），它是证据不是判据。
- 数据与基础设施：`analytics_schema`，独立 Flyway 历史表 `analytics_history`（`V1__analytics.sql`：事实表 + 参照缓存 + 幂等闸门；`V2__scoreboard_and_ai_findings.sql`：物化成绩表 + AI 结论事实表）；RocketMQ（4 个消费组；配置里有 `producer.group` 但当前不发任何事件）；Nacos（注册 + 配置中心）；**不使用 Redis**。
- 对外接口（JWT 验签 + 方法级 `@PreAuthorize`，**角色之后还有一层资源范围判断**；端点语义与错误码见 `docs/api.md` 端点总表与错误码表）：`GET /api/v1/analytics/contests/{contestId}/scoreboard`（TEACHER/ADMIN，成绩榜分页）、`/scoreboard/me`（**CANDIDATE**，只看自己那一行，身份取令牌 sub、**不接受 `studentId` 参数**）、`/score-distribution`（TEACHER/ADMIN，总分直方图，档宽越界 400 `ANALYTICS_INVALID_RANGE`）、`/problems`（TEACHER/ADMIN，题目维度分页，含无人提交的题目）、`/problems/{problemId}/stats`（TEACHER/ADMIN，单题统计）、`/students/{studentId}/profile`（TEACHER/ADMIN，学情档案，`from`/`to` 只作用于提交时间线）、`/risk/summary`（**仅 ADMIN**）、`/risk/students`（**仅 ADMIN**，分页，按可疑度降序并附最多 5 条 AI 结论作证据）、以及 **`POST /api/v1/internal/analytics/rebuild`**（**SERVICE 或 ADMIN**，显式重算某场考试的成绩与排名，同考试进行中 409 `ANALYTICS_REBUILD_IN_PROGRESS`，**不经网关**）。**共 9 个端点**，与 `docs/api.md` 端点总表一致。
- 当前实现：三条读模型与 9 个端点均已实现（口径与已知边界记在 `docs/analytics/read-model.md`）。鉴权三层与 ai-service 同构：角色 → `AnalyticsScopeGuard`（取考试快照判 `ownerTeacherId`，**越权与不存在同为 404 `ANALYTICS_RESOURCE_NOT_FOUND`**；考生接口另要求仍在名单内且为 `ENROLLED`；风险接口只认 ADMIN、仅校验考试存在）→ 身份只从已验证令牌取。参照数据两条刷新路径：请求路径上按需刷新（超过 `analytics.refresh.max-age` 才刷）与 `@Scheduled` 定时兜底（只刷"最近有事实写入"或参照数据缺失的考试，**不做全表轮询**）。成绩读模型三条重算路径：定时增量（只算 `computed_at < judged_at` 的考试）、读路径自愈（物化表为空时顺带算一次，30 秒冷却）、显式重建端点。可疑度**公式只在 SQL 里出现一处**，权重外置为 `analytics.risk.weight-*`。
- 下一步：**重算的单飞闸门是进程内的**——多实例部署时两个实例仍可能同时重算同一场考试，跨实例互斥需要数据库咨询锁或分布式锁；榜单/题目/风险列表是 `limit ... offset ...` 的深分页（做游标分页才能支撑"跳到第 1000 页"）；`tie-break` 前三档与成绩可见范围放开策略仍待产品确认（`docs/exam-process.md` §10）；参照数据在 `refreshed_at` 之前允许陈旧；AI 结论读模型已随简化删除（不再消费 `lunar-ai-finding-created`），风险面板只显示风险事件统计。

## 公共模块（非独立服务）

- `lunar-common`：统一 API 响应 `ApiResponse`（`requestId/data/error` 结构，错误为 `code + message`）、请求追踪 `RequestIdFilter`（`X-Request-Id` 透传/生成）、角色枚举 `Role`（ADMIN/CANDIDATE/INVIGILATOR/TEACHER）、评测引擎抽象 `JudgeEngine`（`submit/query/cancel`）及其判题模型。
- 跨服务契约不单设模块：服务间 REST 契约由各服务的 `api.dto` / `domain.dto` 表达（调用方按字段名取值），评测引擎契约（`JudgeEngine` / `JudgeVerdict` / `JudgeSubmissionResult`）与统一响应在 `lunar-common`。
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
