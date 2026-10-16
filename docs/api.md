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
| `AUTH_INVALID_SERVICE_CLIENT` | 服务客户端凭证无效（客户端未注册或密钥错误，不区分二者，防客户端枚举） | 401 |
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
| `AI_JOB_NOT_FOUND` | AI 作业不存在 | 404 |
| `AI_FINDING_NOT_FOUND` | AI 结论不存在 | 404 |
| `AI_RESOURCE_NOT_FOUND` | 目标资源不存在**或不在调用者的资源范围内**（两情形同码，避免泄露存在性） | 404 |
| `AI_INVALID_REQUEST` | AI 请求不合法（未知作业类型、缺少必要引用、提交未评测完成等） | 400 |
| `AI_QUOTA_EXCEEDED` | 当日 AI 作业额度已用尽（`ai.limits.max-jobs-per-day`） | 429 |
| `AI_EMBEDDING_DISABLED` | 需要向量检索但 embedding 未启用（`ai.limits.embedding-enabled=false`） | 409 |
| `AI_PROVIDER_UNAVAILABLE` | AI Provider 不可达或超时（云端网络不通，或自建内网 OpenAI 兼容网关未启动） | 503 |
| `AI_PROVIDER_REJECTED` | AI Provider 返回非 2xx（模型名、密钥或额度需检查；含 Provider 侧限流） | 502 |
| `AI_INVALID_MODEL_OUTPUT` | 模型输出不符合约定结构，**不得当成功结果使用** | 502 |
| `ANALYTICS_RESOURCE_NOT_FOUND` | 分析目标资源不存在**或不在调用者的资源范围内**（两情形同码，避免泄露存在性；路径里的 id 不是合法 UUID 也走这里） | 404 |
| `ANALYTICS_REBUILD_IN_PROGRESS` | 该考试的成绩重算正在进行中（单飞；重复触发会让两次整批替换互相覆盖） | 409 |
| `ANALYTICS_INVALID_RANGE` | 分析查询的区间参数不合法（档宽越界、时间窗起止颠倒、时间格式不可解析） | 400 |
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
- **服务身份令牌（客户端凭证）**：`POST /api/v1/internal/service-tokens` 用 `clientId`（服务名）+ `clientSecret` 换取，claims 同上，差别是 `sub` 为服务名、`roles` 为 `["SERVICE"]`、TTL 更短（默认 10 分钟，`SERVICE_TOKEN_TTL_MINUTES` 可配）。只给"没有用户上下文"的调用方（定时任务、MQ 消费者）用；**有用户上下文的调用仍转发用户令牌**，账本才能记到真实 actor（`BACKEND_IMPROVEMENT_PLAN` BE-07）。生产必须用环境变量注入客户端密钥（`SERVICE_CLIENT_SECRET_*`），默认值仅供本地联调；
- **资源服务接入**：`spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://auth-service:8081/api/v1/auth/jwks`；
- 令牌内不放敏感信息（密码哈希、学号以外的个人信息）。

### 分页

列表接口统一使用 `page`（1 起）与 `pageSize`，响应 `data` 中包含分页元数据（总数、当前页）。

### 事件信封

所有 RocketMQ 事件包含统一信封字段：`eventId`、`eventType`、`aggregateId`、`occurredAt`、`schemaVersion`、`traceId`；消费者必须按 `eventId` 幂等。Topic 清单见 `docs/architecture.md` 第 5 节。

**上线格式（已实现）**：消息体就是完整信封 JSON，内层业务数据在 `payload` 字段；消息头带 `KEYS=eventId` 与 `traceId`（traceId 为空时**省略该头**——RocketMQ 拒绝空属性值，历史上曾有事件因此永久卡在 Outbox）。实现见 `lunar-common` 的 `EventEnvelope`（生产者包装）与 `EventMessages`（消费者解包）。

**灰度兼容（务必知悉）**：生产者与消费者无法同时发布，因此消费端**必须同时认两种形态**——带信封的新消息，与"整条消息就是内层 payload"的旧消息。`EventMessages.payloadOf` 已封装该兼容，判定依据是 `eventId` + `eventType` + `payload` 三个字段同时存在（只看 `payload` 字段会把"内层恰好含 payload 字段"的旧消息剥壳、丢掉业务数据）。旧格式没有 `eventId`，此时幂等退化为业务侧的终态条件更新（CAS）。

**消费端去重（已实现）**：submission 用 `submission_schema.consumed_events` 闸门，与事实写入同一事务（`insert ... on conflict do nothing`，插入即判断）；judge-service 没有数据库，用 Redis 的 `ConsumedEventGuard`。judge 侧有意采用"**先查、处理成功后再登记**"而不是"先 SETNX 占坑"：后者会在评测中途失败后让重投看到坑位已占而直接 ack，判决永久丢失；先查后写的代价只是极端并发下多评一次。两者都 fail-open（短期状态层不可用不影响主链路）。

### WebSocket（**尚未实现**，以下为约定）

入口 `/ws/v1/contests/{contestId}`。事件类型：`JUDGING_STATUS`、`RISK_ALERT`、`MEDIA_STATE`、`SCREENSHOT_READY`。重连后先通过 REST 拉取未确认事件再继续接收推送。WebRTC SDP/ICE 交换通过媒体信令接口完成，业务服务不转发媒体 RTP 包。当前代码中没有任何 WebSocket 端点或处理器（gateway 也未配置 `/ws/v1/**` 路由，且没有 RocketMQ 依赖），消费事件推送尚不具备条件。

## 端点总表

下表与代码一致：**代码实际有 76 个端点、24 个控制器**（`grep '@\(Get\|Post\|Put\|Delete\|Patch\)Mapping'` 与 `^@RestController$` 实测；`@RestControllerAdvice` 不计入）。本表目前列 69 行，差 7 个——4 个从未进表（`GET /api/v1/submissions/admin/dead-letters`、`POST /api/v1/submissions/admin/dead-letters/{eventId}/replay`、`GET /api/v1/internal/users/{userId}/tokens-valid-after`、`GET /api/v1/internal/monitor/events`），3 个被错放在下方"规划中（代码不存在）"表里但已实现（3 个 `assignments` 端点）；另有下方"规划中"表的表头为 4 列而数据行写了 5 个单元格。这属于待清理的文档债，尚未修正。"鉴权"列是 `@PreAuthorize` / `SecurityConfig` 的实际规则；"任意已认证"表示当前只要求有效令牌、**尚未做资源范围校验**（属待办，见 `BACKEND_IMPROVEMENT_PLAN.md` BE-01）。

| 服务 | 方法 | 路径 | 鉴权 | 状态 |
|---|---|---|---|---|
| auth | POST | `/api/v1/auth/login` | 公开 | 已实现（BCrypt + RS256 JWT + Redis 失败限流） |
| auth | GET | `/api/v1/auth/jwks` | 公开 | 已实现（裸 JWKSet，不套 ApiResponse） |
| auth | GET | `/api/v1/internal/students` | TEACHER/ADMIN | 已实现（按学号或账户名查询，供 contest 判断账号复用） |
| auth | POST | `/api/v1/internal/students` | TEACHER/ADMIN | 已实现（创建学生账号，固定 CANDIDATE 角色） |
| auth | POST | `/api/v1/internal/students/{studentId}/password-reset` | TEACHER/ADMIN | 已实现（不返回密码材料） |
| auth | POST | `/api/v1/internal/service-tokens` | **免认证**（凭证在请求体） | 已实现（服务间身份/客户端凭证：校验 clientId+clientSecret 后签发 `roles=["SERVICE"]` 的短 TTL 令牌，供定时任务与 MQ 消费者等无用户上下文的调用方使用；不经网关，只能内网直连） |
| problem | POST | `/api/v1/problems` | TEACHER/ADMIN | 已实现（题目 + 首版本 + 测试点） |
| problem | GET | `/api/v1/problems` | TEACHER/ADMIN | 已实现（分页 + keyword，管理端列表） |
| problem | GET | `/api/v1/problems/{id}` | 任意已认证 | 已实现（考生视角：当前发布版本，不含测试点） |
| problem | POST | `/api/v1/problems/{id}/versions` | TEACHER/ADMIN | 已实现（新版本：`baseVersionId` 缺省时基于题目当前版本，字段缺省沿用基线；无当前版本时 404） |
| problem | GET | `/api/v1/problems/{id}/versions` | TEACHER/ADMIN | 已实现（版本列表） |
| problem | POST | `/api/v1/problems/{id}/versions/{versionId}/publish` | TEACHER/ADMIN | 已实现（发布并切换 `current_version_id`，发布后版本不可变） |
| problem | GET | `/api/v1/problems/{id}/versions/{versionId}/testcases` | TEACHER/ADMIN | 已实现（仅老师/管理员；判题链路走下面的内部端点） |
| problem | GET | `/api/v1/internal/problems/{problemId}/versions/{versionId}` | **SERVICE** | 已实现（BE-04：按考试锁定版本取判题限制，不受全局发布影响） |
| contest | GET | `/api/v1/internal/contests/{contestId}/problems/{problemId}` | CANDIDATE | 已实现（BE-04：提交时查该考试给这道题锁定的版本；题目不在考试里返回 404 `CONTEST_PROBLEM_NOT_IN_CONTEST`） |
| problem | GET | `/api/v1/internal/problems/{problemId}/versions/{versionId}/testcases` | **SERVICE**（服务身份令牌） | 已实现（**不经网关**，只能内网直连；**只认服务身份**——测试数据不能靠"内网 + 考生/教师令牌"取走，BE-01；submission 用自己的服务令牌取到后随 `lunar-submission-created` 事件送到评测引擎；超限 413 `PROBLEM_TESTDATA_TOO_LARGE`） |
| submission | POST | `/api/v1/submissions` | CANDIDATE | 已实现（幂等 + 会话/竞赛/**题目属于竞赛**/语言/频率五项校验 + Outbox；版本取自考试锁定值，见 BE-04） |
| submission | GET | `/api/v1/submissions/{id}` | 任意已认证（本人；TEACHER/ADMIN 可查任意） | 已实现（权限在 Service 内判定，方法上无 `@PreAuthorize`） |
| submission | POST | `/api/v1/internal/submissions/{id}/judged` | ADMIN | 已实现（判题回写过渡端点） |
| submission | GET | `/api/v1/internal/submissions/{submissionId}` | **SERVICE** | 已实现（**含源码**的服务间详情：源码是学生作品，只认服务身份、不经网关；当前调用方是 ai-service 的代码评审作业） |
| submission | GET | `/api/v1/internal/submissions` | **SERVICE** | 已实现（按 `contestId`+`problemId` 批量取提交摘要，**含源码**；`limit` 由服务端夹取到 ≤500，调用方无法把它变成全库导出；当前调用方是 ai-service 的代码评审） |
| judge | POST | `/api/v1/judge/submissions` | **未接验签** | 代码存在，但**当前对所有调用者恒 401**（未配 `jwk-set-uri` / `oauth2ResourceServer`）；`requestId` 硬编码为 `"pending"`，测试点恒为空 |
| contest | POST | `/api/v1/contests` | TEACHER/ADMIN | 已实现（创建草稿） |
| contest | GET | `/api/v1/contests` | 任意已认证 | 已实现（分页 + status 过滤；**未做资源范围限制**） |
| contest | GET | `/api/v1/contests/{id}` | 任意已认证 | 已实现（**未做资源范围限制**） |
| contest | POST | `/api/v1/contests/{id}/review` | TEACHER/ADMIN | 已实现（DRAFT→REVIEWING） |
| contest | POST | `/api/v1/contests/{id}/publish` | ADMIN | 已实现（REVIEWING→PUBLISHED；发布前**只校验时间窗口**，随机题目分配未实现） |
| contest | POST | `/api/v1/contests/{contestId}/problems` | TEACHER（限归属）/ADMIN | 已实现（BE-04：加入题目并**锁定 `problemVersionId`**；重复加入同一题目视为改锁定版本） |
| contest | GET | `/api/v1/contests/{contestId}/problems` | TEACHER（限归属）/ADMIN | 已实现（考试题目清单与锁定版本） |
| contest | DELETE | `/api/v1/contests/{contestId}/problems/{problemId}` | TEACHER（限归属）/ADMIN | 已实现（移出题目） |
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
| contest | GET | `/api/v1/internal/contests/{contestId}/snapshot` | **SERVICE** | 已实现（考试快照：考试元数据 + 全员名单（含 `REMOVED`）+ 锁定题目版本；供分析型消费者拉取参照数据。考生令牌语义的 `/session` 不具备这个用途，且快照含全员名单，不能凭"内网 + 任意已认证令牌"取走） |
| anti-cheat | POST | `/api/v1/monitor/events` | CANDIDATE | 已实现（severity 由服务端规则表计算） |
| anti-cheat | GET | `/api/v1/monitor/events` | **ADMIN** | 已实现（分页：竞赛/学生/等级/审核状态过滤；**BE-01 先收紧为管理员专用**——风险事件只知道 contestId、不知道竞赛归属，跨服务范围查询落地前按兼容说明继续 ADMIN-only） |
| anti-cheat | POST | `/api/v1/monitor/events/{eventId}/review` | **ADMIN** | 已实现（OPEN→ACKNOWLEDGED→CONFIRMED/DISMISSED；写入走前态条件更新，**并发终审恰好一个成功、另一个 409**，终态不可再动；**终态必须填原因**，空白/超长 400 `MONITOR_REVIEW_REASON_INVALID`；角色同上一行，先收紧为管理员） |
| audit | POST | `/api/v1/internal/audit/records` | 任意已认证 | 已实现（服务间写入；注意规则是 `isAuthenticated()`，未限制为 TEACHER/ADMIN。调用方：contest / auth / submission / anti-cheat，经 lunar-common 的 `AuditRecorder` 尽力而为写入。请求体可选字段缺省传 `null`——`contestId` 是 uuid 列，传空串会被 PostgreSQL 拒绝。SERVICE 令牌写入时 `sub` 落 `service_id`、`actor_id` 留空） |
| audit | GET | `/api/v1/audit/records` | TEACHER/ADMIN | 已实现（追加式账本查询） |
| ai | POST | `/api/v1/ai/jobs/code-review` | TEACHER（限归属）/ADMIN | 已实现（请求代码评审：立即返回作业号，评审在后台执行；必须给 `contestId` 以做资源范围判断） |
| ai | GET | `/api/v1/ai/jobs/{jobId}` | TEACHER（限归属）/ADMIN | 已实现（作业状态与 `errorCode`；越权与不存在同样 404） |
| ai | GET | `/api/v1/ai/jobs` | TEACHER（限归属）/ADMIN | 已实现（分页 + contestId/jobType/status 过滤；不给 contestId 时仅 ADMIN） |
| ai | GET | `/api/v1/ai/findings` | TEACHER（限归属）/ADMIN | 已实现（分页 + contestId/submissionId/findingType 过滤；`score` 是可疑度，**仅用于排序展示，禁止据此自动处置**） |
| ai | GET | `/api/v1/ai/findings/{findingId}` | TEACHER（限归属）/ADMIN | 已实现（结论详情，含结构化 `detailJson`） |
| ai | GET | `/api/v1/ai/provider` | **ADMIN** | 已实现（Provider 运行态：mode/地址主机/模型；**不回显 api-key**） |
| ai | POST | `/api/v1/ai/jobs/problem-draft` | TEACHER（限归属）/ADMIN | 已实现（请求出题草稿：产物是**草稿**，落 `problem_drafts`，教师确认后才由 problem 既有接口落库；不给 `contestId` 时仅 ADMIN） |
| ai | GET | `/api/v1/ai/drafts` | TEACHER（限归属）/ADMIN | 已实现（出题草稿分页；不给 `contestId` 时仅 ADMIN） |
| ai | GET | `/api/v1/ai/drafts/{draftId}` | TEACHER（限归属）/ADMIN | 已实现（草稿详情：题面/样例/建议测试点/知识点标签） |
| analytics | GET | `/api/v1/analytics/contests/{contestId}/scoreboard` | TEACHER（限归属）/ADMIN | 已实现（成绩榜分页；名次由成绩读模型授予，排序口径可配 `analytics.ranking.tie-break`，默认 总分降序 → 先达到者在前 → 解出题数降序 → student_id 升序（**前三档待产品确认**，docs/exam-process.md §10）；读模型为空时顺带自愈重算一次） |
| analytics | GET | `/api/v1/analytics/contests/{contestId}/scoreboard/me` | CANDIDATE | 已实现（考生只看自己那一行；身份取令牌 sub，**不接受 studentId 参数**；可见范围可配 `analytics.score-visibility`，默认 `OWN_ONLY`，放开策略待确认（docs/exam-process.md §10-6），不可见时返回 `visibility=HIDDEN` 而不是 404） |
| analytics | GET | `/api/v1/analytics/contests/{contestId}/score-distribution` | TEACHER（限归属）/ADMIN | 已实现（总分直方图 + 人数/均分/最高分；`bucketSize` 取值 1..100，越界 400 `ANALYTICS_INVALID_RANGE`——不夹取，否则前端坐标轴会与数据错位） |
| analytics | GET | `/api/v1/analytics/contests/{contestId}/problems` | TEACHER（限归属）/ADMIN | 已实现（题目维度分页：attempts/acceptedCount/distinctStudents/均分/平均耗时；以考试锁定的题目为驱动表，**无人提交的题目也列出**；排序按出题顺序 sort_order，末档 problemId 保证分页稳定） |
| analytics | GET | `/api/v1/analytics/contests/{contestId}/problems/{problemId}/stats` | TEACHER（限归属）/ADMIN | 已实现（单题统计；题目不属于该考试时与"考试不存在"同码 404） |
| analytics | GET | `/api/v1/analytics/contests/{contestId}/students/{studentId}/profile` | TEACHER（限归属）/ADMIN | 已实现（学情：名次/总分来自成绩读模型，verdict 与语言分布、逐题最佳分与最近结论、提交时间线按需聚合；`from`/`to`（ISO-8601）**只作用于时间线**，起止颠倒或格式非法 400 `ANALYTICS_INVALID_RANGE`） |
| analytics | GET | `/api/v1/analytics/contests/{contestId}/risk/summary` | **ADMIN** | 已实现（风险汇总：总数/待审核/已确认/已驳回/涉事学生数 + 严重度·事件类型·审核状态分布 + 学生×时间窗热力图（只回**最近** N 格，桶宽与上限可配）；**先收紧为 ADMIN**——考试级授权落地前按最小权限开放，与 anti-cheat 的 `/monitor/events` 同一取舍） |
| analytics | GET | `/api/v1/analytics/contests/{contestId}/risk/students` | **ADMIN** | 已实现（风险学生分页：按可疑度降序，权重可配 `analytics.risk.weight-*`，末档 student_id 保证深分页不漏行/不重行；每行并入最多 5 条 AI 结论作为**证据**——模型可疑度不进入排序权重、不触发任何自动处置） |
| analytics | POST | `/api/v1/internal/analytics/rebuild` | **SERVICE / ADMIN** | 已实现（显式重算某场考试的成绩与排名；同考试重算中返回 409 `ANALYTICS_REBUILD_IN_PROGRESS`；**不经网关**；单飞闸门是**进程内**的，多实例并发重算未覆盖） |

**规划中（代码不存在）**：

| 服务 | 方法 | 路径 | 状态 |
|---|---|---|---|
| contest | GET | `/api/v1/contests/{contestId}/assignments/me` | CANDIDATE | 已实现（随机题目分配：考生看自己的题目集合与锁定版本） |
| contest | GET | `/api/v1/contests/{contestId}/assignments/{studentId}` | TEACHER（限归属）/ADMIN | 已实现（老师复盘某学生的分配，含 randomSeed 与分配版本） |
| contest | POST | `/api/v1/contests/{contestId}/assignments/generate` | TEACHER（限归属）/ADMIN | 已实现（显式重算分配，幂等：已有分配的学生跳过） |
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
- `GET /api/v1/contests/{contestId}/assignments/me`：考生查看自己的随机题目分配（**已实现**）；`GET /api/v1/contests/{contestId}/assignments/{studentId}`：老师/管理员复盘某学生的分配（**已实现**）；`POST /api/v1/contests/{contestId}/assignments/generate`：显式重算分配（**已实现**，幂等）；
- `GET /api/v1/contests/{contestId}/score`：查询当前临时或最终成绩（**规划，未实现**）。

学生没有主动交卷接口；服务端在统一结束时间关闭考试入口。

## 成绩状态（**规划：当前无任何实现**）

```text
PROVISIONAL -> UNDER_REVIEW -> FINAL
                         \-> REVOKED
```

评测结果可以即时返回，最终成绩由考试规则决定是否在查重和风控审核后发布。当前未设计学生申诉接口，申诉能力暂不实现。

**实现现状**：代码中不存在该状态机的枚举、字段或接口，也没有排名与"该题最高有效分"聚合；judge 侧已按测试点通过比例给分（`passedCases`/`totalCases` 随 `lunar-submission-judged` 传输，通过全部为满分，见上文"评测"），但成绩读模型、排名与成绩状态机仍未落地。

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

## AI

AI 能力由 ai-service 提供（`/api/v1/ai/**`，经网关可达）。端点与鉴权见上方端点总表，职责边界见 `docs/services.md` 的 ai-service 节。

### 三层权限（缺一不可）

| 层 | 判据 | 实现 |
|---|---|---|
| ① 角色 | 方法级 `@PreAuthorize`：**风险研判与 Provider 运行态仅 ADMIN**，其余 TEACHER/ADMIN——AI 是教师/管理员的工具，考生不该触发计费调用 | `AiController` |
| ② 资源范围 | 带 `contestId` 的请求按考试归属判断（以服务身份取 contest 的快照，比对 `ownerTeacherId`）；**不带 `contestId` 的资源（作业/结论/草稿的列表与详情）只有 ADMIN 可用** | `AiScopeGuard` |
| ③ 身份来源 | actor 与 role 只从**已验证令牌**取（`sub` 与 authorities），**绝不读请求体** | `AiController` |

**越权与不存在返回同一个 404 `AI_RESOURCE_NOT_FOUND`**：区分二者等于确认"这个考试存在"，与 BE-01 隐藏存在性的要求一致。

### 作业是异步的

两类作业（`CODE_REVIEW` / `PROBLEM_DRAFT`）都是慢操作（模型调用秒级到分钟级），因此 `POST /api/v1/ai/jobs/*` **立即返回作业号**，实际执行在后台 worker（租约 + 指数退避重试，失败终态为 `FAILED`）。

请求（代码评审；`risk-assessment` 传 `contestId`+`studentId`，`similarity-scan` 传 `contestId`+`problemId`，`problem-draft` 传 `requirement`（必填，≤4000 字）与可选的 `contestId`/`difficulty`/`languageHint`）：

```json
{"contestId":"c_123","submissionId":"sub_456"}
```

响应（`data`，只列关键字段；字段全集见端点总表与实现的读模型）：

```json
{
  "jobId": "job_789",
  "jobType": "CODE_REVIEW",
  "status": "PENDING",
  "contestId": "c_123",
  "submissionId": "sub_456",
  "requestedBy": "user_123",
  "model": "gpt-4o-mini",
  "promptVersion": "code-review-v2",
  "errorCode": null,
  "attempts": 0,
  "createdAt": "2026-10-09T03:00:00Z"
}
```

轮询 `GET /api/v1/ai/jobs/{jobId}`：`status` 为 `PENDING` → `RUNNING` → `SUCCEEDED` / `FAILED`；失败时 `errorCode` 取错误码注册表里的 AI 码，`statusDetail` 给运维看原因。`GET /api/v1/ai/jobs` 支持 `contestId` / `jobType` / `status` 过滤。**同一份输入**（同作业类型 + 同引用 + 同提示词版本）已有成功结论时，入队会**直接返回那条旧作业**而不新建——老师连点两次"生成评审"是最常见用法，不该产生两份作业与两次 Provider 调用（也因此入队不消耗额外的每日配额）。

结论用 `GET /api/v1/ai/findings`（`contestId` / `submissionId` / `findingType` 过滤）与 `GET /api/v1/ai/findings/{findingId}` 读取，`detailJson` 是该作业类型的结构化产出；**一份作业至多产出一条结论**，`findingType` 就是作业类型。

### `score` 的语义警告（必读）

`score` 是**可疑度 0..100，仅用于排序与展示**：任何调用方都**不允许**用它驱动自动处置。仓库铁律是单一事件/单一信号不能自动判定作弊，AI 结论只是人工复核的线索（`ai_findings.score` 的列注释与 `AiFindingView` 的契约说明都写明了这一条）。

- 只有 `RISK_ASSESSMENT` 与 `SIMILARITY_SCAN` 会产生 `score`；`CODE_REVIEW` 是教学反馈，`severity` 与 `score` 都为空——把两者混在一个字段里，风险列表会被"学生写错了循环"这类结论污染；
- `severity` **不由 AI 定义**（那是 anti-cheat 规则表的语言）；风险研判另外给出理由、**反方证据**与人工介入方向（`recommendedAction` 只允许 `DISMISS` / `REVIEW_MANUALLY` / `ESCALATE`，模型给出约定外的取值时降级为最保守的 `REVIEW_MANUALLY`），**不含任何处罚建议**；
- ai-service **不写成绩、不流转审核状态机**：审核流转仍走 `POST /api/v1/monitor/events/{eventId}/review`。

### 出题草稿

`POST /api/v1/ai/jobs/problem-draft` 的产物是**草稿**，用 `GET /api/v1/ai/drafts` / `GET /api/v1/ai/drafts/{draftId}` 读取（题面、样例、建议测试点、知识点标签、难度与语言提示），落在 `ai_schema.problem_drafts`，`reviewStatus` 当前恒为 `DRAFT`。**它不写 `problem_schema`**：教师确认后仍走 problem 既有的创建接口，"发布后版本不可变""发布前至少一个测试点"等既有不变量不为 AI 让路。教师确认入口尚未实现。

### 数据出境门禁

代码评审要把学生源码交给模型，因此"数据发去哪"是**默认可核对、默认关闭**的开关：

- 判据是 Provider 的 **base-url 地址**（不是 `ai.provider.mode`）：回环、私有网段、`.local`/`.internal` 后缀、不含点的短名都算内网；**地址无法解析时按非内网处理**（fail closed）；
- base-url 指向非内网地址时必须显式设置环境变量 `AI_EGRESS_APPROVED=true`，否则服务**启动失败**；并且**每次构建模型前都会复核**——把 base-url 热切成公网地址同样拦得住；
- 授权值**只从环境变量读**，配置中心改不动它（详见 `docs/middleware.md`"LLM 推理服务"节）；
- `GET /api/v1/ai/provider`（仅 ADMIN）回显当前 mode / 地址主机 / 模型名，**不回显 api-key**。

失败语义：Provider 不可达或超时 → 503 `AI_PROVIDER_UNAVAILABLE`（值得重试）；Provider 返回非 2xx（模型名/密钥/额度，含 Provider 侧限流）→ 502 `AI_PROVIDER_REJECTED`（重试无用，要改配置）；模型输出不符合约定结构 → 502 `AI_INVALID_MODEL_OUTPUT`（**不得当成功结果使用**）。这些码会记进作业的 `errorCode` 并按 attempts 退避重试；当日配额用尽 → 429 `AI_QUOTA_EXCEEDED`（`ai.limits.max-jobs-per-day`）；需要向量检索但未启用 → 409 `AI_EMBEDDING_DISABLED`。

## 截图规则（**规划：未实现**）

截图来源为 `RISK_EVENT`、`SCHEDULED` 或 `MANUAL`；保存 MinIO 对象键、SHA-256、来源、会话、创建时间和过期时间。截图访问必须经过竞赛授权并写审计日志。当前 media-service 只有占位 POM（无 `src/`），仓库未引入 MinIO SDK，也没有截图相关端点或表。
