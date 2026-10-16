# Lunar Online Judge 后端架构与业务流程

## 1. 文档目标

本文档描述首期后端的可落地设计，而不是只列技术名词。首期产品是面向学校、培训机构和竞赛组织者的 ACM/ICPC 风格在线编程竞赛平台，Windows 10/11 客户端负责参赛和本地监考采集，后端负责竞赛、题目、提交、评测调度、实时监考控制、截图证据、风控审核和审计。

本文档覆盖服务职责、基础设施数据职责、端到端流程、状态机、一致性、失败恢复和安全边界。

考试流程的当前确认版本见 `docs/exam-process.md`。当考试规则与通用竞赛规则发生冲突时，以考试流程文档中的最终确认项为准，待确认项不能直接实现。

## 2. 已确认的产品边界

### 2.1 用户和角色

首期只保留四类角色：

| 角色   | 能力                                         |
| ------ | -------------------------------------------- |
| 管理员 | 用户、系统配置、全局审计、竞赛管理           |
| 老师   | 题目、测试数据、评审；必须授权到具体竞赛     |
| 监考员 | 被授权竞赛内的实时屏幕/摄像头查看和告警处理  |
| 考生   | 参加竞赛、查看题目、提交代码、查看自己的结果 |

角色不是最终权限。权限必须同时满足用户角色、竞赛授权、考生会话、当前时间窗口和资源范围。

当前考试权限补充规则：所有老师都可以管理学生账号；老师可以查看所负责考试的源码、评测结果、风险事件、截图、实时屏幕和摄像头；管理员拥有老师全部权限，并额外可以创建老师和管理员账号。所有高敏感媒体访问和账号操作必须审计。

### 2.1.1 老师管理竞赛学生

老师可以在自己被授权的竞赛中维护学生名单，并为学生创建或重置参赛账号。学生账号包含以下业务字段：

| 字段                     | 所属            | 规则                                               |
| ------------------------ | --------------- | -------------------------------------------------- |
| `studentNo`              | auth-service    | 学号；学生账号必填，在租户/组织内唯一              |
| `username`               | auth-service    | 登录账户名；全局唯一，创建后默认不允许老师直接修改 |
| `password`               | 请求字段        | 仅用于创建或重置时传输，服务端不落库、不回显       |
| `passwordHash`           | auth-service    | BCrypt 哈希，唯一持久化的密码材料                  |
| `displayName`            | auth-service    | 学生姓名或显示名                                   |
| `managedBy`              | auth-service    | 创建/重置该账号的老师 ID，写入审计                 |
| `passwordChangeRequired` | auth-service    | 首次登录或重置后要求学生修改密码                   |
| `enrollmentStatus`       | contest-service | `ENROLLED`、`DISABLED`、`REMOVED`                  |

学生账号与竞赛报名关系分开保存：同一个学生账号可以参加多场竞赛，每场竞赛有独立的名单状态、加入人和加入时间。老师只能操作自己拥有或被授权管理的竞赛；管理员可以跨竞赛操作。

密码流程必须满足：

1. 老师提交初始密码或重置密码请求；
2. auth-service 校验老师的竞赛授权和账号状态；
3. 服务端立即计算密码哈希，明文只存在于当前请求生命周期；
4. API 响应只返回账号名、学号、学生 ID 和 `passwordChangeRequired`，不返回密码或哈希；
5. 创建、重置、禁用和移出名单全部写入 audit-service；
6. 老师不能读取既有密码，只能发起重置。

### 2.1.2 学生账号与报名关系表设计

`auth_schema.users`（学生账号相关字段）：

| 字段                       | 类型         | 说明                                                                     |
| -------------------------- | ------------ | ------------------------------------------------------------------------ |
| `id`                       | UUID         | 学生账号 ID                                                              |
| `student_no`               | varchar(64)  | 学号，学生账号必填；通过部分唯一索引保证学生学号唯一，非学生账号允许为空 |
| `username`                 | varchar(100) | 登录账户名，全局唯一                                                     |
| `password_hash`            | varchar(255) | BCrypt 结果（后续可选升级 Argon2id），唯一持久化的密码材料               |
| `display_name`             | varchar(120) | 显示名                                                                   |
| `role`                     | varchar(32)  | 学生账号固定为 `CANDIDATE`                                               |
| `managed_by_teacher_id`    | UUID         | 创建/重置该账号的老师 ID                                                 |
| `password_change_required` | boolean      | 初始密码或重置后为 true                                                  |
| `status`                   | varchar(32)  | `ACTIVE`、`DISABLED`                                                     |

若业务为多租户，`student_no` 的唯一约束调整为 `organization_id + student_no`。

`contest_schema.contest_candidates`（报名关系，归 contest-service）：

| 字段                  | 类型        | 说明                              |
| --------------------- | ----------- | --------------------------------- |
| `id`                  | UUID        | 报名关系 ID                       |
| `contest_id`          | UUID        | 竞赛 ID                           |
| `student_id`          | UUID        | auth-service 用户 ID              |
| `student_no_snapshot` | varchar(64) | 报名时的学号快照，便于审计        |
| `added_by_teacher_id` | UUID        | 操作老师                          |
| `status`              | varchar(32) | `ENROLLED`、`DISABLED`、`REMOVED` |
| `enrolled_at`         | timestamptz | 加入时间                          |
| `removed_at`          | timestamptz | 移出时间，可空                    |

唯一约束为 `contest_id + student_id`；移出名单是软删除，保留历史提交、成绩和审计关系。登录失败需要限流，连续失败触发短暂锁定。

### 2.2 实时监考与截图

- 屏幕采集：竞赛策略开启时强制；
- 摄像头采集：设备存在且策略允许时开启；没有摄像头只记录 `CAMERA_MISSING`（与 `RiskRules` 词表一致），不报警；
- 实时传输：WebRTC；业务服务只负责鉴权、信令和状态，不转发 RTP 媒体包；
- 连续视频：首期不保存、不回放；
- 截图：支持风险事件、定时、手动三种触发，保存到 MinIO；截图策略至少包含 `captureMode`、`intervalSeconds`、`retentionDays` 和 `maxPerSession`；
- 风险处理：管理端告警，人工审核，不因单个风险事件自动取消成绩。

### 2.3 评测与查重

- 用户代码不能在 Spring Boot 业务容器内执行；
- `go-judge` 是独立 Linux 评测节点上的沙箱执行服务；
- 平台通过 `JudgeEngine` 抽象调用 go-judge；
- 首批语言：C、C++、Java、Python、Go、JavaScript/TypeScript；
- 代码查重通过独立 JPlag worker 异步执行；
- 判题结果自动影响成绩和排名；查重和反作弊只形成证据和审核任务。

## 3. 总体拓扑

```text
Windows 客户端 -> OpenResty(80, 边缘反向代理) -> gateway-service
                    |-- auth-service       登录/RBAC
                    |-- contest-service        竞赛/会话/策略
                    |-- problem-service        题目/测试点/语言
                    |-- submission-service     提交/成绩读模型
                    |-- judge-service          调度/go-judge/状态机
                    |-- anti-cheat-service     风险/查重/审核
                    |-- media-service          WebRTC/截图/媒体审计
                    `-- audit-service          管理与访问审计

submission-service -> RocketMQ -> judge-service -> GoJudgeAdapter -> go-judge
media-service -> WebRTC signaling -> 独立 SFU；媒体 RTP 不经过业务服务

PostgreSQL | Redis | MinIO | Nacos | RocketMQ
```

## 4. 服务边界和数据归属

### gateway-service

业务流量的内部统一入口，负责路由、请求追踪 ID、基础限流、鉴权转发和 WebSocket 入口。不含题目、成绩或风控业务逻辑。外部入口由 OpenResty 承担（边缘反向代理，见 `docs/middleware.md`），只做转发与边缘通用处理。

### auth-service

负责用户、登录、令牌、角色、竞赛授权和服务间身份。RBAC 解决能否访问，资源范围由竞赛服务和监考策略继续校验。

### contest-service

负责竞赛生命周期、参赛名单、学生报名关系、考生会话、开始/结束时间、监考策略和排名规则。客户端进入竞赛前必须创建或恢复服务端会话。学生账号本身归 auth-service，contest-service 只保存报名关系和权限范围。

### problem-service

负责题目、题面、测试点、样例、判题限制、语言白名单和题目版本。测试数据不能通过公开题目 API 返回给考生。

### submission-service

负责接收代码、竞赛状态校验、幂等、提交状态和成绩读模型。不直接调用 go-judge；提交成功后发布 `submission.created`。

### judge-service

负责消费提交事件、评测任务、`GoJudgeAdapter`、重试、超时补偿、verdict 归一化和 `submission.judged` 事件。它是平台评测状态机的唯一拥有者。

### anti-cheat-service

负责风险事件、风险分数、告警、JPlag 任务、相似度报告和人工审核。不能直接修改已发布成绩。

### media-service

负责监考令牌、WebRTC SDP/ICE 信令、实时流状态、截图策略、截图对象和媒体访问授权。Spring 服务不处理高带宽 RTP 数据。

### audit-service

负责管理员操作、权限变更、实时流查看、截图查看、人工审核和系统配置审计。普通业务接口不能删除审计记录。

## 5. 基础设施和数据职责

### PostgreSQL

第一期是一套 PostgreSQL 部署，不是主从高可用集群。每个服务使用独立 schema：

```text
auth_schema, contest_schema, problem_schema, submission_schema,
judge_schema, anti_cheat_schema, media_schema, audit_schema
```

服务禁止跨 schema 直接查询；以后可以把高负载 schema 迁移到独立实例。

### Redis

保存限流计数（**已实现**：auth 的登录失败锁定）。以下为规划、尚未使用：令牌失效状态、幂等键（经评估**有意不迁**，见 `docs/middleware.md`）、会话心跳、WebSocket 节点路由、评测短期锁、截图频率控制。Redis 不是成绩和审计的事实源。

### RocketMQ

首期 Topic：

| Topic                        | 生产者            | 消费者                       | 语义         |
| ---------------------------- | ----------------- | ---------------------------- | ------------ |
| `lunar-submission-created`   | submission        | judge、anti-cheat            | 新提交已落库 |
| `lunar-submission-judged`    | judge             | submission、contest、gateway | 评测结果可用 |
| `lunar-risk-event-created`   | media/anti-cheat  | anti-cheat、gateway、audit   | 风险事件     |
| `lunar-screenshot-requested` | anti-cheat/media  | media                        | 请求截图     |
| `lunar-screenshot-created`   | media             | anti-cheat、audit、gateway   | 截图已保存   |
| `lunar-plagiarism-requested` | anti-cheat        | plagiarism-worker            | 异步查重     |
| `lunar-plagiarism-completed` | plagiarism-worker | anti-cheat、audit            | 查重完成     |

每条事件包含 `eventId`、`eventType`、`aggregateId`、`occurredAt`、`schemaVersion`、`traceId`；消费者必须幂等。

**当前落地情况（与上表的差距）**：只有 3 个 Topic 在代码中存在——`lunar-submission-created`（submission 经 Outbox 生产 → judge 消费）、`lunar-submission-judged`（judge 直接发送，submission 也经 Outbox 生产 → **只有 submission 消费**）、`lunar-risk-event-created`（anti-cheat 经 Outbox 生产 → **暂无消费者**）。其余 4 个 Topic 未实现；contest / gateway / audit / media 作为消费者的角色也未实现（这些服务没有 RocketMQ 依赖）。另外，信封字段当前只存在于 `outbox_events` 表行中，**上线消息只带内层 `payload`**（消息头仅有 `KEYS=eventId` 与 `traceId`），因此"消费者按 `eventId` 幂等"目前无法落实——judge 的消费者没有任何去重。

### MinIO

MinIO 首期只保存截图，不保存连续视频；对象使用不可猜测键，下载使用短期签名 URL。风险事件、评测日志摘要、查重关系和审计的检索首期直接查询 PostgreSQL，暂不引入 Elasticsearch，后续检索需求增长时再评估引入。

## 6. 核心流程一：创建竞赛

```text
管理员/老师 -> contest-service：创建竞赛
contest-service -> problem-service：绑定题目版本和语言策略
contest-service -> auth-service：绑定老师/监考员授权
contest-service：保存监考策略、截图策略、时间窗口
contest-service -> audit-service：记录创建和授权操作
```

发布前校验：题目版本和测试点完整、语言有执行模板、时间窗口合法、截图保留策略有上限、监考人员已授权。发布后题目版本不原地修改。

## 6.1 核心流程：老师添加竞赛学生

```text
1. 老师 -> contest-service：提交 contestId、studentNo、username、displayName、initialPassword。
2. contest-service 校验老师是否拥有该竞赛的 TEACHER 管理授权，竞赛是否允许维护名单。**（未实现：当前只校验竞赛存在，任何 TEACHER/ADMIN 都能对任意竞赛操作名单、公告与密码重置）**
3. contest-service -> auth-service：请求创建学生账号；调用使用服务间身份和幂等键。
4. auth-service 校验学号/账户名唯一性，哈希初始密码，创建 CANDIDATE 用户。
5. auth-service 返回 studentId，不返回密码和 passwordHash。
6. contest-service 在本地事务写入 contest_candidate(status=ENROLLED)。
7. audit-service 记录操作者、竞赛、学生、操作类型和结果，不记录密码。
8. 返回学生账号摘要；老师需要通过受控渠道把初始密码交给学生，学生首次登录必须修改。
```

如果第 6 步失败，contest-service 必须使用幂等键补偿报名关系；如果账号已经创建但报名关系未写入，应通过 Outbox/补偿任务完成关联，而不是再次创建账号。删除名单默认是软删除，不能删除学生历史提交和评测记录。**（未实现：当前是"先远程建账号、再本地写报名"，本地写入失败会留下孤立账号，没有补偿或对账任务。）**

### 6.2 老师重置学生密码

```text
老师 -> contest-service：重置指定竞赛学生密码
contest-service：校验竞赛授权和学生报名关系
contest-service -> auth-service：重置密码
auth-service：更新 passwordHash、设置 passwordChangeRequired=true
audit-service：记录重置操作
```

老师不能读取旧密码；重置接口也不返回新密码。生产环境应通过一次性临时密码交付、管理员导入或受控线下渠道交给学生，不能把密码写入普通日志。

## 7. 核心流程二：考生进入竞赛和实时监考

```text
1. 客户端登录 auth-service，获得短期 Access Token。
2. 请求 contest-service 创建参赛会话。
3. contest-service 校验名单、竞赛时间、重复登录和客户端版本。
4. media-service 签发监考令牌：sessionId、contestId、screenRequired、
   cameraAllowed、startAt、endAt、nonce。
5. 客户端在时间窗口内启动强制屏幕采集；摄像头存在且策略允许时启动。
6. 客户端完成 WebRTC SDP/ICE 交换并连接独立 SFU。
7. 被授权监考员或老师查看当前竞赛内考生流，访问写审计。
8. 客户端发送心跳和用户态风险事件。
9. 断流时自动重连；超过阈值后发布告警，人工处理。
```

实时媒体与业务链路分开：屏幕/摄像头媒体不经过 gateway 或 PostgreSQL；业务服务只保存会话、授权、状态和审计。

考试预检失败不会统一阻断：账号/名单失败禁止进入；屏幕或网络失败允许进入但产生高风险告警；客户端版本异常允许进入但禁止提交并要求升级；没有摄像头只记录设备状态。详细规则见 `docs/exam-process.md`。

## 8. 核心流程三：代码提交和 go-judge 评测

```text
1. 客户端 -> submission-service：提交 submissionId、problemId、language、sourceCode。
2. 校验会话有效、竞赛进行中、题目属于竞赛、语言允许、提交次数未超限。
3. 本地事务写入 submission(status=QUEUED) 和 Outbox(submission.created)。
4. Outbox publisher 发布 lunar-submission-created。
5. judge-service 消费，创建 judge_task(status=DISPATCHING)。
6. GoJudgeAdapter 发送编译/运行命令、输入文件、CPU、墙钟、内存、
   进程数、输出和文件限制。
7. go-judge 隔离执行，返回退出码、stdout、stderr、耗时、内存和限制错误。
8. judge-service 归一化为 QUEUED、RUNNING、ACCEPTED、WRONG_ANSWER、
   COMPILE_ERROR、TIME_LIMIT_EXCEEDED、MEMORY_LIMIT_EXCEEDED、
   RUNTIME_ERROR、OUTPUT_LIMIT_EXCEEDED 或 SYSTEM_ERROR。
9. 本地事务更新任务和结果，发布 lunar-submission-judged。
10. submission 更新成绩读模型，contest 更新排名，gateway 推送 WebSocket。
```

评测服务不向 go-judge 发送数据库凭据、消息队列地址或宿主机路径。源代码和测试数据只在评测任务临时目录存在，任务结束后清理。

**当前实现与上图的差距**：第 5 步的 `judge_task(status=DISPATCHING)` 不存在（judge-service 无 DataSource、无迁移，评测结果只存内存）；第 9 步 judge 是**直接发送** `lunar-submission-judged`（不经 Outbox、无信封）；第 10 步只有 submission 更新提交状态，**contest 更新排名与 gateway 推送未实现**；第 2 步的五项校验已实现 4 项（会话有效、竞赛进行中、语言允许、提交频率未超限），"题目属于竞赛"仍需随机分配表落地。**测试点已随事件传输**（submission 经 problem 的服务间接口取到后放进 `testFiles`），真实引擎可出真实 verdict 与按测试点比例的部分分。

学生没有主动交卷接口，服务端在统一结束时间关闭进入和提交入口。已提交任务在最终结算策略确定前保持独立状态，不能因为客户端关闭而删除。

## 9. go-judge 适配设计

平台接口位于 `lunar-common`：

```java
public interface JudgeEngine {
    JudgeSubmissionResult submit(JudgeSubmissionCommand command);
    JudgeResult query(String engineSubmissionId);
    void cancel(String engineSubmissionId);
}
```

`GoJudgeAdapter` 负责语言模板、多文件和测试点打包、请求超时与重试、引擎任务 ID 映射、原始结果保存、verdict 归一化、取消任务和重复提交保护。TypeScript 采用 `tsc` 生成 JavaScript 后由 Node.js 运行，不能直接把 `.ts` 交给 Node.js。

## 10. 核心流程四：代码查重

```text
submission-service：保存源码快照引用
       ↓ lunar-plagiarism-requested
anti-cheat-service：按竞赛/题目/语言聚合提交
       ↓
plagiarism-worker：调用 JPlag，生成相似度报告
       ↓ lunar-plagiarism-completed
anti-cheat-service：保存相似度、双方、报告地址和阈值命中状态
       ↓
管理端：人工查看报告，不自动改分
```

第一期默认同语言比较。查重 worker 与主服务隔离，主服务不在 HTTP 请求线程中运行 JPlag。

## 11. 核心流程五：风控、截图和人工审核

### 11.1 风险事件

事件包括失焦/切屏、剪贴板、可疑窗口和进程树、未签名模块、调试器/注入工具/Hook 特征、异常子进程、媒体断流、摄像头缺失状态、go-judge 资源限制异常。事件进入 `anti-cheat-service`，计算风险等级后通过 WebSocket 告警；单一事件不能自动判作弊。

### 11.2 截图

截图来源为 `RISK_EVENT`、`SCHEDULED` 或 `MANUAL`。流程为：

```text
触发方 -> media-service：校验竞赛/会话/权限/频率
media-service -> 客户端：截图请求
客户端 -> media-service：上传一次性图片
media-service -> MinIO：保存对象
media-service -> PostgreSQL：保存元数据和过期时间
media-service -> audit-service：记录访问和创建审计
media-service -> WebSocket：通知截图就绪
```

截图访问必须校验当前竞赛授权并使用短期签名 URL。过期后清理任务删除对象和元数据；申诉中的对象可以保留锁定。

## 12. 状态机

### 12.1 提交

```text
QUEUED -> DISPATCHING -> RUNNING -> FINISHED
   |          |             |          |
   +------> SYSTEM_ERROR <---+          +-> verdict
```

状态变更带版本或乐观锁，重复消息不能把终态改回运行态。

**当前实现**：`DISPATCHING` / `RUNNING` / `FINISHED` 三个中间态不存在，提交状态只有 `QUEUED` 与 `JudgeVerdict` 的 8 个终态。重复消息的保护分两层：读到的状态已是终态 → 直接 409；写入用**前态条件更新**（`where id=? and status=读到的状态`），并发下影响 0 行同样返回 409——"重复消息不能把终态改回运行态"已由 CAS 保证，只是没有版本列。

### 12.2 风险审核

```text
OPEN -> ACKNOWLEDGED -> CONFIRMED / DISMISSED
```

`CONFIRMED` 不直接改成绩；处罚必须走独立人工裁决流程并审计。

**当前实现**：审核动作 `ACKNOWLEDGE` / `CONFIRM` / `DISMISS` 由服务端白名单校验合法性，写入用**前态条件更新**（`where id=? and review_status=读到的前态`）：两个并发终审最多一个成功，失败方收 409 `MONITOR_INVALID_TRANSITION`；`CONFIRMED` / `DISMISSED` 为终态。

### 12.3 媒体会话

```text
CREATED -> AUTHORIZED -> CONNECTING -> LIVE
                      |          |
                      +-> EXPIRED +-> DEGRADED -> LIVE
                                           |
                                           +-> CLOSED
```

## 13. 一致性、幂等和失败恢复

- 业务事实和 Outbox 事件在同一个 PostgreSQL 本地事务提交（**已实现**：submission 的提交与判决回写、anti-cheat 的风险上报）；
- HTTP 写操作使用 `Idempotency-Key`（**已实现**：以 DB 唯一约束为权威；并发同键未捕获唯一冲突，会以 500 `INTERNAL_ERROR` 收场而不是"返回首次结果"，属待办）；事件使用 `eventId` 去重（**未实现**，见第 5 节"当前落地情况"）；
- 评测任务使用 `submissionId + judgeVersion` 唯一约束（**未实现**：没有 judge_task 表）；
- go-judge 不可用时指数退避，超过次数转 `SYSTEM_ERROR` 并告警（**未实现**：只有连接/读取超时，失败交给 RocketMQ 默认重投）；
- WebSocket 重连后先通过 REST 拉取未确认事件，再继续接收推送（**未实现**：无 WebSocket 端点）；
- 媒体不保存，因此断线前画面不可回放（**未实现**：media-service 仅占位 POM）；
- Outbox 发布失败进入死信表，支持管理员重放（**未实现**：失败仅告警并留待下一轮，无重试计数/退避/死信表）。

## 14. 安全边界

- 业务服务、评测节点和媒体节点分网络；
- go-judge 节点默认禁止出网；
- 用户代码不拥有宿主机目录、数据库、Redis、RocketMQ 或云凭据；
- 评测镜像固定版本并扫描；
- 所有媒体和截图访问校验 RBAC、竞赛授权和时间窗口；
- 风控只提供证据和告警，不承诺发现所有内核级外挂，不安装内核驱动；
- 密钥只从环境变量或密钥管理系统注入；
- 学生密码永远不写入日志、RocketMQ、MinIO、审计内容或响应体；
- 老师账号管理权限限定在被授权竞赛，不能通过修改请求中的 `teacherId` 越权；
- 生产环境需要额外完成隐私告知、用户授权、数据保留和第三方许可证审查。

## 15. 当前实现与后续顺序

**当前已实现**（逐项可验证）：

- 公共层：多模块父工程、`ApiResponse` + 全局异常处理（仅覆盖域异常与校验失败，无兜底 `Exception` 处理器）、`RequestIdFilter`、`Role`、`JudgeEngine` 抽象与 `JudgeVerdict`；服务间调用用 Spring 原生声明式 HTTP 接口（`@HttpExchange` 接口 + `HttpServiceClientFactory` 装配成 Bean，baseUrl 默认服务名经 Nacos + LoadBalancer 寻址）；
- auth-service：登录（BCrypt + RS256 JWT）、JWKS、Redis 登录失败限流、学生账号服务间接口（创建/查询/重置）；
- contest-service：生命周期 6 状态（含按时间自动 PUBLISHED→RUNNING→FINISHED 与人工归档）、名单（唯一约束/软删除/重新加入）、考生会话（进入/心跳/超时/断线恢复）、公告（发布/送达/确认）；
- problem-service：题目 + 版本 + 测试点，发布后版本不可变、考生视角不含测试点；
- submission-service：提交（幂等 + 4 项校验 + 取测试点 + Outbox）、判决回写、Outbox 发布器、judged 消费者；
- judge-service：`lunar-submission-created` 消费者、`GoJudgeAdapter`（设 `GO_JUDGE_BASE_URL` 激活）与 `MockJudgeEngine`（`mock` profile）、verdict 归一化；
- anti-cheat-service：风险事件上报（服务端算 severity）/分页查询/人工审核状态机；
- audit-service：追加式账本写入与查询（**contest / auth / submission / anti-cheat 四个调用方已接入**，共用 lunar-common 的 `AuditServiceApi` + 尽力而为的 `AuditRecorder`）；
- gateway-service：6 条路由前缀 + `X-Request-Id` 透传，`/api/v1/internal/**` 不暴露；
- 基础设施：Compose（PostgreSQL / Redis / MinIO / Nacos / RocketMQ / OpenResty / go-judge）、Nacos 注册 + 配置中心（4 个服务托管热更新项）、Actuator 健康检查（8 个服务）。

**尚未实现**（相对本文档其余章节的确认设计）：随机题目分配、成绩读模型（排名/最高分/成绩状态机/部分分）、考试预检 6 项与重复登录踢设备、指定题目公告、代码查重、媒体链路（监考令牌/WebRTC/截图）、WebSocket 推送、`judge_task` 异步评测状态机与退避/死信、事件信封上线与消费端 `eventId` 幂等、竞赛级授权范围（含 `INVIGILATOR` 权限）。

后续顺序：

1. auth-service：用户、登录、RBAC、JWT；
2. PostgreSQL schema、迁移和 MyBatis-Plus 基础配置；
3. contest/problem/submission 核心领域和状态机；
4. RocketMQ Outbox、Topic 和幂等消费；
5. `GoJudgeAdapter`、语言模板和评测节点集成测试；
6. WebSocket 推送和 WebRTC 信令；
7. 风险事件、截图 MinIO 流程和人工审核；
8. JPlag worker、相似度报告和审计；
9. 集成测试、压测、沙箱安全测试和生产加固。
