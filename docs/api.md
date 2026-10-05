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

### 错误码

错误码大写下划线命名，`<域>_<原因>`；新增先登记再使用。初始注册表：

| 错误码 | 含义 | HTTP 状态 |
|---|---|---|
| `VALIDATION_FAILED` | 参数校验失败 | 400 |
| `AUTH_INVALID_CREDENTIALS` | 用户名或密码错误（不区分二者，防枚举） | 401 |
| `AUTH_ACCOUNT_DISABLED` | 账号已禁用 | 403 |
| `AUTH_RATE_LIMITED` | 登录失败次数过多，暂时锁定 | 429 |
| `AUTH_WEAK_PASSWORD` | 密码强度不满足要求 | 400 |
| `CONTEST_NOT_FOUND` | 竞赛不存在 | 404 |
| `CONTEST_NOT_ENROLLED` | 不在竞赛名单中 | 403 |
| `CONTEST_NOT_RUNNING` | 竞赛未开始或已结束 | 409 |
| `CONTEST_CLOSED` | 考试入口已关闭 | 409 |
| `SUBMISSION_LIMIT_EXCEEDED` | 提交次数超限 | 429 |
| `SUBMISSION_LANGUAGE_NOT_ALLOWED` | 语言不在白名单 | 400 |
| `SUBMISSION_DUPLICATE` | 幂等键重复提交 | 409 |
| `INTERNAL_ERROR` | 未分类服务端错误 | 500 |

### 请求头

| 请求头 | 语义 | 生成方 |
|---|---|---|
| `X-Request-Id` | 全链路追踪 ID，`RequestIdFilter` 透传并在响应头返回 | 客户端或服务端自动生成 |
| `Idempotency-Key` | 写操作幂等键；同一键重复请求返回首次结果，不产生二次副作用 | 客户端 |
| `Authorization: Bearer <JWT>` | 访问令牌，由 auth-service 签发，各服务作为资源服务器本地验签 | 登录后携带 |

未携带或携带无效令牌返回 401；令牌有效但缺少角色/授权返回 403。注意：当前各服务尚未配置 `SecurityFilterChain`，运行时是 Spring Security 默认行为（所有端点 401），鉴权规则随 auth 落地逐步生效。

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

### WebSocket

入口 `/ws/v1/contests/{contestId}`。事件类型：`JUDGING_STATUS`、`RISK_ALERT`、`MEDIA_STATE`、`SCREENSHOT_READY`。重连后先通过 REST 拉取未确认事件再继续接收推送。WebRTC SDP/ICE 交换通过媒体信令接口完成，业务服务不转发媒体 RTP 包。

## 端点总表

标注"已实现（Mock）"的表示代码已存在但为占位实现；其余为规划约定，随模块落地逐步实现。

| 服务 | 方法 | 路径 | 状态 |
|---|---|---|---|
| auth | POST | `/api/v1/auth/login` | 已实现（Mock） |
| auth | GET | `/api/v1/auth/jwks` | 规划 |
| contest | POST | `/api/v1/contests` | 已实现（Mock） |
| contest | GET | `/api/v1/contests/{id}` | 已实现（Mock） |
| problem | POST | `/api/v1/problems` | 已实现（Mock） |
| problem | GET | `/api/v1/problems/{id}` | 已实现（Mock） |
| submission | POST | `/api/v1/submissions` | 已实现（Mock） |
| submission | GET | `/api/v1/submissions/{id}` | 已实现（Mock） |
| judge | POST | `/api/v1/judge/submissions` | 已实现（Mock 引擎） |
| contest | POST | `/api/v1/contests/{contestId}/review` | 规划 |
| contest | POST | `/api/v1/contests/{contestId}/publish` | 规划 |
| contest | POST | `/api/v1/contests/{contestId}/announcements` | 规划 |
| contest | GET | `/api/v1/contests/{contestId}/assignments/{studentId}` | 规划 |
| contest | GET | `/api/v1/contests/{contestId}/score` | 规划 |
| contest | POST | `/api/v1/contests/{contestId}/students` | 规划 |
| contest | GET | `/api/v1/contests/{contestId}/students` | 规划 |
| contest | POST | `/api/v1/contests/{contestId}/students/{studentId}/password-reset` | 规划 |
| contest | DELETE | `/api/v1/contests/{contestId}/students/{studentId}` | 规划 |
| media | POST | `/api/v1/contests/{contestId}/sessions/{sessionId}/monitor-token` | 规划 |
| media | POST | `/api/v1/monitor/screenshot` | 规划 |
| anti-cheat | GET | `/api/v1/monitor/events` | 规划 |
| anti-cheat | POST | `/api/v1/monitor/events/{eventId}/review` | 规划 |
| audit | GET | `/api/v1/audit/media-access` | 规划 |

## 认证（auth-service）

### 登录

`POST /api/v1/auth/login`（已实现 Mock；真实实现按以下契约补齐，实现路径见 `docs/development-guide.md` 第 5 节）

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

失败：`AUTH_INVALID_CREDENTIALS`（401，用户不存在与密码错误返回同一码）、`AUTH_ACCOUNT_DISABLED`（403）、`AUTH_RATE_LIMITED`（429）。当前 mock 响应缺少 `tokenType` 与 `passwordChangeRequired` 字段，以本契约为准。

### JWKS

`GET /api/v1/auth/jwks`：公开返回 RS256 公钥集（JWKSet JSON），资源服务据此本地验签。

## 评测

`POST /api/v1/judge/submissions`（当前由 MockJudgeEngine 处理，返回假结果；生产实现由 judge-service 将内部命令翻译为 go-judge 请求，不允许客户端直接访问 go-judge）：

```json
{
  "submissionId":"sub_123",
  "language":"cpp",
  "sourceCode":"...",
  "cpuTimeLimitMs":2000,
  "memoryLimitKb":256000
}
```

评测链路：客户端 → submission-service 落库并发 `lunar-submission-created` → judge-service 经 `GoJudgeAdapter` 调 go-judge → 归一化 verdict → `lunar-submission-judged` → submission/contest/gateway 各自消费。完整流程见 `docs/architecture.md` 第 8 节。

## 考试流程

考试和竞赛使用同一个活动模型。考试生命周期 `DRAFT → REVIEWING → PUBLISHED → RUNNING → FINISHED → ARCHIVED` 由 contest-service 管理，规则细节见 `docs/exam-process.md`：

- `POST /api/v1/contests`：老师创建草稿考试；
- `POST /api/v1/contests/{contestId}/review`：提交管理员审核；
- `POST /api/v1/contests/{contestId}/publish`：管理员发布考试（发布时生成每位学生相同数量的随机题目，并保存随机种子和题目版本）；
- `POST /api/v1/contests/{contestId}/announcements`：老师/管理员发布公告；
- `GET /api/v1/contests/{contestId}/assignments/{studentId}`：查询学生随机题目分配；
- `GET /api/v1/contests/{contestId}/score`：查询当前临时或最终成绩。

学生没有主动交卷接口；服务端在统一结束时间关闭考试入口。

## 成绩状态

```text
PROVISIONAL -> UNDER_REVIEW -> FINAL
                         \-> REVOKED
```

评测结果可以即时返回，最终成绩由考试规则决定是否在查重和风控审核后发布。当前未设计学生申诉接口，申诉能力暂不实现。

## 实时监考

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

幂等键使用 `Idempotency-Key`。同一个 `contestId + studentNo` 只能有一条有效报名关系；已存在的学生账号不能被重复创建。

### 查询竞赛学生

`GET /api/v1/contests/{contestId}/students?page=1&pageSize=20&keyword=20260001`

只返回 `studentId`、学号、账户名、显示名、报名状态、加入人和加入时间，不返回密码、密码哈希或登录令牌。

### 重置学生密码

`POST /api/v1/contests/{contestId}/students/{studentId}/password-reset`

请求：

```json
{"temporaryPassword":"新的临时密码"}
```

接口返回 `passwordChangeRequired=true`，不返回密码。老师不能查询旧密码，只能重置。操作必须记录审计事件 `STUDENT_PASSWORD_RESET`。

### 移出竞赛名单

`DELETE /api/v1/contests/{contestId}/students/{studentId}`

该操作默认软删除报名关系并设置 `enrollmentStatus=REMOVED`，不删除学生账号、历史提交、评测结果或审计记录。

## 截图规则

截图来源为 `RISK_EVENT`、`SCHEDULED` 或 `MANUAL`；保存 MinIO 对象键、SHA-256、来源、会话、创建时间和过期时间。截图访问必须经过竞赛授权并写审计日志。
