# API 约定（v1）

HTTP 前缀统一为 `/api/v1`。各服务经 gateway 对外暴露；客户端不直接访问业务服务端口（本地开发除外）。

## 通用约定

### 响应结构

所有接口返回统一包装（实现见 `lunar-common` 的 `ApiResponse`）：

```json
{"requestId":"...","data":{},"error":null}
```

失败时 `data` 为 `null`，`error` 携带稳定的 `code`（机器可读，大写下划线命名，如 `CONTEST_NOT_ENROLLED`）和面向调用方的 `message`：

```json
{"requestId":"...","data":null,"error":{"code":"...","message":"..."}}
```

异常 → 错误响应的转换统一由 `lunar-common` 的 `ApiExceptionHandler` 完成（`WebCommonAutoConfiguration` 注册，全平台唯一）：各服务的域异常继承 `ApiException`（携带 `code` 与 `httpStatus`），抛出即得上述结构；参数校验失败统一映射为 400 `VALIDATION_FAILED`。服务内不再自建 `@RestControllerAdvice`，也不把 Handler 内嵌进 Controller。

**当前覆盖范围（实现事实）**：处理器覆盖四类——`ApiException`（域异常，按注册表 code/状态返回）、`MethodArgumentNotValidException`（400 `VALIDATION_FAILED`）、`HttpMessageNotReadableException`（请求体缺失或 JSON 不可解析，同样 400 `VALIDATION_FAILED`），以及**兜底的 `Exception` → 500 `INTERNAL_ERROR`**（异常细节只进日志，响应体不含内部信息）。兜底会**原样抛出**两类异常以免误伤状态码：框架自带 `ErrorResponse` 语义的异常（404/405/415 等）与 Spring Security 的认证/授权异常（交回过滤器链产出 401/403）。

### 错误码

错误码大写下划线命名，`<域>_<原因>`；新增先登记再使用。初始注册表：

| 错误码 | 含义 | HTTP 状态 |
|---|---|---|
| `VALIDATION_FAILED` | 参数校验失败 | 400 |
| `AUTH_INVALID_CREDENTIALS` | 用户名或密码错误（不区分二者，防枚举） | 401 |
| `AUTH_ACCOUNT_DISABLED` | 账号已禁用 | 403 |
| `AUTH_RATE_LIMITED` | 登录失败次数过多，暂时锁定 | 429 |
| `AUTH_WEAK_PASSWORD` | 密码强度不满足要求 | 400 |
| `AUTH_USER_NOT_FOUND` | 目标用户不存在（服务间接口） | 404 |
| `AUTH_USERNAME_EXISTS` | 登录账户名已被占用 | 409 |
| `AUTH_STUDENT_NO_EXISTS` | 学号已被占用 | 409 |
| `CONTEST_NOT_FOUND` | 竞赛不存在 | 404 |
| `CONTEST_NOT_ENROLLED` | 不在竞赛名单中 | 403 |
| `CONTEST_NOT_RUNNING` | 竞赛未开始或已结束 | 409 |
| `CONTEST_CLOSED` | 考试入口已关闭 | 409 |
| `CONTEST_INVALID_TRANSITION` | 生命周期非法流转（如草稿直接发布） | 409 |
| `CONTEST_PUBLISH_INCOMPLETE` | 发布前配置不完整（时间窗口、名单、题目等） | 409 |
| `CONTEST_STUDENT_ALREADY_ENROLLED` | 该学生已在本竞赛名单中 | 409 |
| `SESSION_NOT_ACTIVE` | 考生会话不存在或未激活（心跳/恢复） | 409 |
| `PROBLEM_NOT_FOUND` | 题目不存在 | 404 |
| `PROBLEM_VERSION_NOT_FOUND` | 题目版本不存在（含未发布） | 404 |
| `PROBLEM_VERSION_IMMUTABLE` | 版本已发布不可修改（改动须创建新版本） | 409 |
| `PROBLEM_TESTDATA_TOO_LARGE` | 测试点数据超过服务间传输上限（默认 1 MiB，`PROBLEM_TESTDATA_MAX_BYTES` 可配）：测试点随事件进 MQ，超限的消息发不出去 | 413 |
| `MONITOR_EVENT_NOT_FOUND` | 风险事件不存在 | 404 |
| `MONITOR_INVALID_TRANSITION` | 审核流转非法（重复终审/未知动作） | 409 |
| `SUBMISSION_LIMIT_EXCEEDED` | 学生提交过于频繁（窗口内超限；阈值经 Nacos `exam.submission.*` 配置，默认每 60 秒 30 次） | 429 |
| `SUBMISSION_LANGUAGE_NOT_ALLOWED` | 语言不在白名单 | 400 |
| `SUBMISSION_DUPLICATE` | 幂等键重复且请求内容不一致 | 409 |
| `SUBMISSION_NOT_FOUND` | 提交不存在 | 404 |
| `SUBMISSION_INVALID_TRANSITION` | 提交状态不允许该流转（如终态再回写判决） | 409 |
| `INTERNAL_ERROR` | 未分类服务端错误（由全局兜底处理器产出，响应体不含异常细节） | 500 |

**两处需要知悉的既有事实**：①`CONTEST_NOT_RUNNING` 仍被 submission-service 复用（`SubmissionException.ContestNotRunning`），且它同时覆盖"时间窗口外"与"竞赛状态不符"两种原因——contest 的服务间判定只回 `canSubmit`，提交链路无法区分；`CONTEST_NOT_ENROLLED` 目前只在 problem 服务调用失败时被复用（`NotSubmittable("题目不可用")`，语义偏弱）。精确化需要 contest 的服务间判定返回拒绝原因，属待办。**会话不存在/过期/终止已改用注册表里的 `SESSION_NOT_ACTIVE`（409）**，不再借用名单类错误码。②`AUTH_WEAK_PASSWORD`/`AUTH_USERNAME_EXISTS` 等 auth 码只在服务间学生账号接口产生。

### 请求头

| 请求头 | 语义 | 生成方 |
|---|---|---|
| `X-Request-Id` | 全链路追踪 ID，`RequestIdFilter` 透传并在响应头返回 | 客户端或服务端自动生成 |
| `Idempotency-Key` | 写操作幂等键；同一键 + 相同请求内容返回首次结果（200，不产生二次副作用），同一键 + 不同请求内容返回 409 `SUBMISSION_DUPLICATE` | 客户端 |
| `Authorization: Bearer <JWT>` | 访问令牌，由 auth-service 签发，各服务作为资源服务器本地验签 | 登录后携带 |

未携带或携带无效令牌返回 401（资源服务用 `BearerTokenAuthenticationEntryPoint`）；令牌有效但缺少角色/授权返回 403。**当前安全基线**：除 gateway（无 Spring Security 依赖）与 media（无代码）外，7 个服务都已配置 `SecurityFilterChain`——auth 放行 login/jwks，contest / problem / submission / anti-cheat / audit 作为资源服务器经 auth 的 JWKS 本地验签、角色规则写在方法级 `@PreAuthorize`；**judge-service 尚未接入验签**（未配置 `oauth2ResourceServer`，也没有 `jwk-set-uri`），其端点当前对所有调用者都返回 401。

### 认证令牌

令牌由 auth-service 签发，各资源服务本地验签，不回源校验。以下为默认约定，实现前可讨论调整：

- **算法**：RS256——auth-service 持私钥签发，公钥经 JWKS 分发，资源服务无需保管共享密钥；
- **JWKS 端点**：`GET /api/v1/auth/jwks`（公开访问）；
- **Claims**：

| Claim | 内容 |
|---|---|
| `sub` | 用户 ID（`auth_schema.users.id`） |
| `roles` | 角色数组，如 `["TEACHER"]`；资源服务映射为 `ROLE_` 前缀 authorities |
| `iss` | `auth-service` |
| `iat` / `exp` | 签发与过期时间 |
| `jti` | 令牌唯一 ID，预留吊销 |

- **TTL**：access token 默认 30 分钟；刷新令牌首期不做，考试中的重新登录按 `docs/exam-process.md` §6 处理；TTL 是否随考试时长延长属待确认项（见 exam-process §10）；
- **资源服务接入**：`spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://auth-service:8081/api/v1/auth/jwks`；
- 令牌内不放敏感信息（密码哈希、学号以外的个人信息）。

### 分页

列表接口统一使用 `page`（1 起）与 `pageSize`，响应 `data` 中包含分页元数据（总数、当前页）。

### 事件信封

所有 RocketMQ 事件包含统一信封字段：`eventId`、`eventType`、`aggregateId`、`occurredAt`、`schemaVersion`、`traceId`；消费者必须按 `eventId` 幂等。Topic 清单见 `docs/architecture.md` 第 5 节。

**当前实现与契约的差距（务必知悉）**：信封字段目前只落在各服务的 `outbox_events` 行里，**上线消息体只带内层 `payload` JSON**，消息头只带 `KEYS=eventId` 与 `traceId`——`aggregateId` / `schemaVersion` / `occurredAt` 不随消息传输；judge-service 直接发送 `lunar-submission-judged` 时连 `eventId` 都没有。因此"按 `eventId` 幂等"目前**无法实现**：judge 的 `lunar-submission-created` 消费者没有任何去重（重复投递会重复评测并重复发 judged），submission 侧只靠"终态前置检查"兜底。补信封与消费端去重属待办（见 `BACKEND_IMPROVEMENT_PLAN.md`）。

### WebSocket（**尚未实现**，以下为约定）

入口 `/ws/v1/contests/{contestId}`。事件类型：`JUDGING_STATUS`、`RISK_ALERT`、`MEDIA_STATE`、`SCREENSHOT_READY`。重连后先通过 REST 拉取未确认事件再继续接收推送。WebRTC SDP/ICE 交换通过媒体信令接口完成，业务服务不转发媒体 RTP 包。当前代码中没有任何 WebSocket 端点或处理器（gateway 也未配置 `/ws/v1/**` 路由，且没有 RocketMQ 依赖），消费事件推送尚不具备条件。

## 端点总表

下表与代码一致（共 40 个端点，分布在 12 个控制器）。"鉴权"列是 `@PreAuthorize` / `SecurityConfig` 的实际规则；"任意已认证"表示当前只要求有效令牌、**尚未做资源范围校验**（属待办，见 `BACKEND_IMPROVEMENT_PLAN.md` BE-01）。

| 服务 | 方法 | 路径 | 鉴权 | 状态 |
|---|---|---|---|---|
| auth | POST | `/api/v1/auth/login` | 公开 | 已实现（BCrypt + RS256 JWT + Redis 失败限流） |
| auth | GET | `/api/v1/auth/jwks` | 公开 | 已实现（裸 JWKSet，不套 ApiResponse） |
| auth | GET | `/api/v1/internal/students` | TEACHER/ADMIN | 已实现（按学号或账户名查询，供 contest 判断账号复用） |
| auth | POST | `/api/v1/internal/students` | TEACHER/ADMIN | 已实现（创建学生账号，固定 CANDIDATE 角色） |
| auth | POST | `/api/v1/internal/students/{studentId}/password-reset` | TEACHER/ADMIN | 已实现（不返回密码材料） |
| problem | POST | `/api/v1/problems` | TEACHER/ADMIN | 已实现（题目 + 首版本 + 测试点） |
| problem | GET | `/api/v1/problems` | TEACHER/ADMIN | 已实现（分页 + keyword，管理端列表） |
| problem | GET | `/api/v1/problems/{id}` | 任意已认证 | 已实现（考生视角：当前发布版本，不含测试点） |
| problem | POST | `/api/v1/problems/{id}/versions` | TEACHER/ADMIN | 已实现（新版本：`baseVersionId` 缺省时基于题目当前版本，字段缺省沿用基线；无当前版本时 404） |
| problem | GET | `/api/v1/problems/{id}/versions` | TEACHER/ADMIN | 已实现（版本列表） |
| problem | POST | `/api/v1/problems/{id}/versions/{versionId}/publish` | TEACHER/ADMIN | 已实现（发布并切换 `current_version_id`，发布后版本不可变） |
| problem | GET | `/api/v1/problems/{id}/versions/{versionId}/testcases` | TEACHER/ADMIN | 已实现（仅老师/管理员；判题链路走下面的内部端点） |
| problem | GET | `/api/v1/internal/problems/{problemId}/versions/{versionId}/testcases` | 任意已认证（服务间） | 已实现（**不经网关**，只能内网直连；submission 取到后随 `lunar-submission-created` 事件送到评测引擎；超限 413 `PROBLEM_TESTDATA_TOO_LARGE`） |
| submission | POST | `/api/v1/submissions` | CANDIDATE | 已实现（幂等 + 会话/竞赛/语言/频率四项校验 + Outbox；**题目归属校验未实现**，依赖随机分配表） |
| submission | GET | `/api/v1/submissions/{id}` | 任意已认证（本人；TEACHER/ADMIN 可查任意） | 已实现（权限在 Service 内判定，方法上无 `@PreAuthorize`） |
| submission | POST | `/api/v1/internal/submissions/{id}/judged` | ADMIN | 已实现（判题回写过渡端点） |
| judge | POST | `/api/v1/judge/submissions` | **未接验签** | 代码存在，但**当前对所有调用者恒 401**（未配 `jwk-set-uri` / `oauth2ResourceServer`）；`requestId` 硬编码为 `"pending"`，测试点恒为空 |
| contest | POST | `/api/v1/contests` | TEACHER/ADMIN | 已实现（创建草稿） |
| contest | GET | `/api/v1/contests` | 任意已认证 | 已实现（分页 + status 过滤；**未做资源范围限制**） |
| contest | GET | `/api/v1/contests/{id}` | 任意已认证 | 已实现（**未做资源范围限制**） |
| contest | POST | `/api/v1/contests/{id}/review` | TEACHER/ADMIN | 已实现（DRAFT→REVIEWING） |
| contest | POST | `/api/v1/contests/{id}/publish` | ADMIN | 已实现（REVIEWING→PUBLISHED；发布前**只校验时间窗口**，随机题目分配未实现） |
| contest | POST | `/api/v1/contests/{id}/archive` | ADMIN | 已实现（FINISHED→ARCHIVED） |
| contest | POST | `/api/v1/contests/{contestId}/students` | TEACHER/ADMIN | 已实现（账号经 auth 创建/复用；**不校验操作者是否拥有该竞赛**） |
| contest | GET | `/api/v1/contests/{contestId}/students` | TEACHER/ADMIN | 已实现（分页 + 学号 keyword） |
| contest | POST | `/api/v1/contests/{contestId}/students/import` | TEACHER/ADMIN | 已实现（CSV 批量导入：multipart 上传，逐行等价于添加学生，逐行返回成功/失败原因） |
| contest | POST | `/api/v1/contests/{contestId}/students/{studentId}/password-reset` | TEACHER/ADMIN | 已实现（经 auth 重置） |
| contest | DELETE | `/api/v1/contests/{contestId}/students/{studentId}` | TEACHER/ADMIN | 已实现（软删除，置 `REMOVED`；当前考试会话同时失效） |
| contest | POST | `/api/v1/contests/{contestId}/students/{studentId}/session/terminate` | TEACHER/ADMIN | 已实现（终止考生会话：ACTIVE/EXPIRED → TERMINATED，终态重进被拒；重复调用幂等） |
| contest | POST | `/api/v1/contests/{contestId}/announcements` | TEACHER/ADMIN | 已实现（全体公告；**指定题目公告未实现**） |
| contest | GET | `/api/v1/contests/{contestId}/announcements` | 任意已认证（CANDIDATE 走考生视角） | 已实现（考生拉取即送达；管理员看全量） |
| contest | POST | `/api/v1/contests/{contestId}/announcements/{announcementId}/confirm` | CANDIDATE | 已实现（幂等，保留首次确认时间） |
| contest | POST | `/api/v1/contests/{contestId}/session` | CANDIDATE | 已实现（进入/恢复；校验名单 + 状态 + 时间窗口） |
| contest | POST | `/api/v1/contests/{contestId}/session/heartbeat` | CANDIDATE | 已实现（心跳 + 剩余秒数） |
| contest | GET | `/api/v1/internal/contests/{contestId}/session` | CANDIDATE（转发学生令牌） | 已实现（供 submission 判定 `canSubmit`） |
| anti-cheat | POST | `/api/v1/monitor/events` | CANDIDATE | 已实现（severity 由服务端规则表计算） |
| anti-cheat | GET | `/api/v1/monitor/events` | TEACHER/ADMIN | 已实现（分页：竞赛/学生/等级/审核状态过滤） |
| anti-cheat | POST | `/api/v1/monitor/events/{eventId}/review` | TEACHER/ADMIN | 已实现（OPEN→ACKNOWLEDGED→CONFIRMED/DISMISSED；写入走前态条件更新，并发终审最多一个成功） |
| audit | POST | `/api/v1/internal/audit/records` | 任意已认证 | 已实现（服务间写入；注意规则是 `isAuthenticated()`，未限制为 TEACHER/ADMIN。调用方：contest / auth / submission / anti-cheat，经 lunar-common 的 `AuditRecorder` 尽力而为写入。请求体可选字段缺省传 `null`——`contestId` 是 uuid 列，传空串会被 PostgreSQL 拒绝） |
| audit | GET | `/api/v1/audit/records` | TEACHER/ADMIN | 已实现（追加式账本查询） |

**规划中（代码不存在）**：

| 服务 | 方法 | 路径 | 状态 |
|---|---|---|---|
| contest | GET | `/api/v1/contests/{contestId}/assignments/{studentId}` | 规划（依赖随机题目分配，未实现） |
| contest | GET | `/api/v1/contests/{contestId}/score` | 规划（成绩读模型/排名未实现） |
| media | POST | `/api/v1/contests/{contestId}/sessions/{sessionId}/monitor-token` | 规划（media-service 仅占位 POM） |
| media | POST | `/api/v1/monitor/screenshot` | 规划（同上） |
| audit | GET | `/api/v1/audit/media-access` | 规划（随 media-service） |

**角色说明**：注册表里的 `INVIGILATOR`（监考员）目前**没有出现在任何授权规则中**——监考入口（`/api/v1/monitor/**`）要求 TEACHER 或 ADMIN，因此监考员角色登录后无法使用监考端点。这与 `docs/exam-process.md` §2、`docs/architecture.md` §2.1 的"监考员按考试授权查看实时监考信息并处理风险告警"不一致，属待办（同一处还需补"考试级授权"）。

## 认证（auth-service）

### 登录

`POST /api/v1/auth/login`（已实现：BCrypt 校验 + RS256 JWT 签发 + 失败限流；开发种子账号 admin/admin123 仅限本地，见 `docs/deployment.md`）

请求：

```json
{"username":"student_20260001","password":"..."}
```

成功响应（`data`）：

```json
{
  "userId": "user_123",
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresAt": "2026-10-04T12:30:00Z",
  "role": "CANDIDATE",
  "passwordChangeRequired": false
}
```

失败：`AUTH_INVALID_CREDENTIALS`（401，用户不存在与密码错误返回同一码）、`AUTH_ACCOUNT_DISABLED`（403）、`AUTH_RATE_LIMITED`（429）。响应字段已与上述契约一致（含 `tokenType` 与 `passwordChangeRequired`）。

### JWKS

`GET /api/v1/auth/jwks`：公开返回 RS256 公钥集。**本端点是 RFC 7517 标准协议端点，返回顶层 `{"keys":[...]}`，不套通用响应包装**——资源服务的 JwtDecoder 按标准格式解析。

## 评测

`POST /api/v1/judge/submissions`（**当前不可用**：judge-service 未接入验签，所有请求返回 401；该端点也不落库、不发事件、测试点恒为空。真正的评测链路走 MQ，见下。生产实现由 judge-service 将内部命令翻译为 go-judge 请求，不允许客户端直接访问 go-judge）：

```json
{
  "submissionId":"sub_123",
  "language":"cpp",
  "sourceCode":"...",
  "cpuTimeLimitMs":2000,
  "memoryLimitKb":256000
}
```

评测链路（**当前实现**）：客户端 → submission-service 落库并发 `lunar-submission-created`（Outbox）→ judge-service 消费并经 `GoJudgeAdapter`（或 `mock` profile 下的 Mock 引擎）评测 → 归一化 verdict → 直接向 `lunar-submission-judged` 发送 → **只有 submission-service 消费**（contest 更新排名与 gateway 推送尚未实现）。**测试点随事件传输**：submission 在提交时经 `GET /api/v1/internal/problems/{problemId}/versions/{versionId}/testcases` 取到测试点，按 `<name>.in` / `<name>.out` 组装进事件的 `testFiles` 字段；总字节超上限（默认 1 MiB）时上游直接返回 413，避免超限消息卡在 Outbox。judged 事件携带 `passedCases` / `totalCases`，`score` 按通过比例计算（通过全部为满分）。完整设计见 `docs/architecture.md` 第 8 节。

## 考试流程

考试和竞赛使用同一个活动模型。考试生命周期 `DRAFT → REVIEWING → PUBLISHED → RUNNING → FINISHED → ARCHIVED` 由 contest-service 管理（**6 个状态与全部 5 条边均已实现**：提交审核、管理员发布、按时间窗口自动 PUBLISHED→RUNNING→FINISHED、管理员归档），规则细节见 `docs/exam-process.md`：

公告（2026-10-06 已实现）：老师/管理员发布（content/important/requireConfirmation）；考生拉取列表即送达（require_confirmation 记录 delivered_at），确认记录 confirmed_at，重复确认幂等；"指定题目公告"随 problem-service 接入扩展 scope。表：contest_schema.announcements + announcement_confirms（唯一约束 announcement_id+student_id）。

状态推进（2026-10-06 已实现）：PUBLISHED→RUNNING→FINISHED 由调度按时间窗口自动推进（30s 周期，条件更新多实例幂等）；心跳会复核考试状态、时间窗口与名单——考试结束/被移出名单的会话立即失效；ARCHIVED 由管理员手动归档。

考生会话（2026-10-06 已实现）：`POST /api/v1/contests/{contestId}/session` 进入/恢复，`POST .../session/heartbeat` 心跳；进入条件 = 名单 ENROLLED + 状态 PUBLISHED/RUNNING + 服务端时间在窗口内；同一学生同一竞赛一个会话，断线重连恢复且剩余时间不补偿；心跳超时（默认 2 分钟）由定时扫描置 EXPIRED；管理端可用 `POST /api/v1/contests/{contestId}/students/{studentId}/session/terminate` 终止会话（TERMINATED 为终态，重进被拒，重复调用幂等）；学生被移出名单时会话同步失效。

- `POST /api/v1/contests`：老师创建草稿考试；
- `POST /api/v1/contests/{contestId}/review`：提交管理员审核；
- `POST /api/v1/contests/{contestId}/publish`：管理员发布考试（**当前只校验时间窗口**；"发布时生成每位学生相同数量的随机题目，并保存随机种子和题目版本"**尚未实现**）；
- `POST /api/v1/contests/{contestId}/announcements`：老师/管理员发布公告（已实现，但只有全体公告）；
- `GET /api/v1/contests/{contestId}/assignments/{studentId}`：查询学生随机题目分配（**规划，未实现**）；
- `GET /api/v1/contests/{contestId}/score`：查询当前临时或最终成绩（**规划，未实现**）。

学生没有主动交卷接口；服务端在统一结束时间关闭考试入口。

## 成绩状态（**规划：当前无任何实现**）

```text
PROVISIONAL -> UNDER_REVIEW -> FINAL
                         \-> REVOKED
```

评测结果可以即时返回，最终成绩由考试规则决定是否在查重和风控审核后发布。当前未设计学生申诉接口，申诉能力暂不实现。

**实现现状**：代码中不存在该状态机的枚举、字段或接口，也没有排名与"该题最高有效分"聚合；judge 侧固定 `ACCEPTED=100`、其余 0 分，且 `lunar-submission-judged` 事件不带 `passedCases`/`totalCases`，因此 `docs/exam-process.md` §7 确认的"按测试点通过比例给部分分"同样尚未落地。

## 实时监考

风险事件（2026-10-06 已实现）：`POST /api/v1/monitor/events` 由考生客户端自动上报（studentId = JWT sub），severity 由服务端规则表计算（不采信上报值）；v1 规则：HIGH=DEBUGGER_DETECTED/INJECTION_ATTEMPT/UNAUTHORIZED_MODULE，MEDIUM=SUSPICIOUS_PROCESS/MEDIA_STREAM_LOST/JUDGE_LIMIT_VIOLATION，LOW=FOCUS_LOST/CAMERA_MISSING/CLIPBOARD_ACCESS，未知类型保守 MEDIUM；事件入库即写 `lunar-risk-event-created` Outbox（gateway WebSocket 告警的未来消费方）。查询与审核（2026-10-06 已实现）：分页过滤 + 审核状态机 OPEN→ACKNOWLEDGED→CONFIRMED/DISMISSED（**写入是"前态条件更新"（CAS）：并发终审最多一个成功，终态不可再动**）；单一事件不能自动判定作弊，处置必须人工审核并留痕（审核人/时间/备注，已接入 audit-service，尽力而为、失败不阻断）。

- `POST /api/v1/contests/{contestId}/sessions/{sessionId}/monitor-token`：签发带竞赛、会话、屏幕/摄像头策略和时间窗口的 WebRTC 信令令牌；
- `POST /api/v1/monitor/screenshot`：风险/定时/手动截图请求；
- `GET /api/v1/monitor/events`：分页查询风险事件；
- `POST /api/v1/monitor/events/{eventId}/review`：人工审核风险事件；
- `GET /api/v1/audit/media-access`：查询实时流和截图访问审计。

预检与断流处理规则见 `docs/exam-process.md` §5-6；截图完整流程见 `docs/architecture.md` §11.2。

## 老师管理竞赛学生

老师只能操作自己拥有或被授权管理的竞赛。学生账号创建和密码重置由 contest-service 编排、auth-service 执行；密码只在请求中出现，服务端只保存哈希；流程与数据表设计见 `docs/architecture.md` §2.1.1-2.1.2。

### 添加学生

`POST /api/v1/contests/{contestId}/students`

请求：

```json
{
  "studentNo": "20260001",
  "username": "student_20260001",
  "displayName": "张三",
  "initialPassword": "一次性初始密码"
}
```

响应不包含密码：

```json
{
  "requestId": "req_123",
  "data": {
    "studentId": "user_123",
    "studentNo": "20260001",
    "username": "student_20260001",
    "displayName": "张三",
    "enrollmentStatus": "ENROLLED",
    "passwordChangeRequired": true
  },
  "error": null
}
```

幂等性由唯一约束保证：同一个 `contestId + studentNo` 只能有一条有效报名关系，已存在的学生账号不会被重复创建；重复添加返回 409 `CONTEST_STUDENT_ALREADY_ENROLLED`。

### CSV 批量导入名单

`POST /api/v1/contests/{contestId}/students/import`（`multipart/form-data`，字段名 `file`，内容 UTF-8）

CSV 列顺序固定为 `studentNo,username,displayName,initialPassword`；首个非空行若是同名表头会自动跳过，空行跳过，字段可用双引号包裹（内部逗号保留、`""` 表示一个字面双引号）。**每一行等价于一次"添加学生"**：复用同一套唯一性校验与审计，部分行失败不回滚已成功行，响应逐行给出结果：

```json
{
  "requestId": "req_123",
  "data": {
    "total": 3,
    "succeeded": 1,
    "failed": 2,
    "items": [
      {"lineNumber": 2, "studentNo": "20260001", "status": "ENROLLED", "studentId": "user_123", "errorCode": null, "message": null},
      {"lineNumber": 3, "studentNo": "20260001", "status": "FAILED", "studentId": null, "errorCode": "CONTEST_STUDENT_ALREADY_ENROLLED", "message": "该学生已在本竞赛名单中"},
      {"lineNumber": 4, "studentNo": "20260002", "status": "FAILED", "studentId": null, "errorCode": "VALIDATION_FAILED", "message": "缺少必填字段：username"}
    ]
  },
  "error": null
}
```

`lineNumber` 是 CSV 文件中的行号（含表头与空行），便于老师定位问题行；`status` 只有 `ENROLLED` 与 `FAILED` 两种，未分类失败（上游不可用等）`errorCode` 为 null 且原因不泄漏内部细节。竞赛不存在时整批返回 404 `CONTEST_NOT_FOUND`。

**幂等的实现方式**：与 §9"逐行携带 `Idempotency-Key`"的意图一致，但落地靠唯一约束而不是显式幂等键——账号唯一性（学号/账户名）与名单唯一约束（竞赛+学生）保证重复导入不产生重复数据，已在名单中的行按 `CONTEST_STUDENT_ALREADY_ENROLLED` 逐行报回，调用方可据此视为"已存在"。

### 查询竞赛学生

`GET /api/v1/contests/{contestId}/students?page=1&pageSize=20&keyword=20260001`

只返回 `studentId`、学号、账户名、显示名、报名状态、加入人和加入时间，不返回密码、密码哈希或登录令牌。

### 重置学生密码

`POST /api/v1/contests/{contestId}/students/{studentId}/password-reset`

请求：

```json
{"temporaryPassword":"新的临时密码"}
```

接口返回 `passwordChangeRequired=true`，不返回密码。老师不能查询旧密码，只能重置。操作必须记录审计事件 `STUDENT_PASSWORD_RESET`（**现状**：contest/auth 侧先落结构化日志，尚未进 audit 账本，见 `docs/services.md` 各服务"下一步"；anti-cheat 的审核动作已接入 audit-service）。

### 移出竞赛名单

`DELETE /api/v1/contests/{contestId}/students/{studentId}`

该操作默认软删除报名关系并设置 `enrollmentStatus=REMOVED`，不删除学生账号、历史提交、评测结果或审计记录。

## 截图规则（**规划：未实现**）

截图来源为 `RISK_EVENT`、`SCHEDULED` 或 `MANUAL`；保存 MinIO 对象键、SHA-256、来源、会话、创建时间和过期时间。截图访问必须经过竞赛授权并写审计日志。当前 media-service 只有占位 POM（无 `src/`），仓库未引入 MinIO SDK，也没有截图相关端点或表。
