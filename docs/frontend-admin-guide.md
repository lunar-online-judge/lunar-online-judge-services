# 管理端前端接入指南（面向前端 AI / 开发者）

> 这份文档描述**当前代码的真实行为**，不是设计愿景。写 UI 时以本文 + 各服务 Swagger 为准；
> 本文与 Swagger 冲突时，以 Swagger（代码生成）为准，并回报本文修正。
>
> 精确 schema 获取方式：`http://localhost:<端口>/swagger-ui.html`（或 `GET /v3/api-docs`）。
> **judge-service 不提供 Swagger 且其 HTTP 端点当前恒 401**（判题走消息队列，不经 HTTP）。

---

## 0. 环境与基址

| 项 | 值 |
|---|---|
| 边缘入口（推荐给浏览器用） | `http://localhost`（OpenResty → gateway） |
| 网关直连 | `http://localhost:8080` |
| 各服务直连 | auth 8081 / contest 8082 / problem 8083 / submission 8084 / judge 8085 / anti-cheat 8086 / audit 8087 / analytics 8088 / ai 8089 |
| 开发管理员 | `admin` / `admin123` |
| 时区与时间 | 后端统一返回 **ISO-8601 UTC**（如 `2026-10-17T16:55:26.115454500Z`），前端自行转本地时区 |
| ID 格式 | 全部是 **UUID 字符串**（不是数字自增） |

**网关已屏蔽 `/api/v1/internal/**`**：那些是服务间接口，前端永远不该调用。

---

## 1. 通用约定（必读，所有接口都适用）

### 1.1 认证
1. `POST /api/v1/auth/login` 用 `{username, password}` 换 `accessToken`；
2. 之后所有请求带 `Authorization: Bearer <accessToken>`；
3. **令牌有效期默认 30 分钟**，没有 refresh 接口——到期后前端要**跳回登录页**重新登录；
4. 令牌里带 `roles`，前端据此做菜单/按钮级显隐（后端仍会独立校验，前端隐藏只是体验）。

### 1.2 响应信封（成功与失败同一个壳）
```json
{ "requestId": "uuid", "data": { }, "error": null }          // 成功
{ "requestId": "uuid", "data": null, "error": { "code": "CONTEST_NOT_FOUND", "message": "竞赛不存在" } }  // 失败
```
- **判断成功看 `error === null`**，不要只看 HTTP 状态（虽然两者一致）。
- `error.code` 是**稳定错误码**，UI 用它做分支；`message` 是中文提示，可直接展示但要允许后端改文案。
- `requestId` 建议在错误弹窗里显示，方便排障。

### 1.3 分页
- 请求：`?page=1&pageSize=20`（**page 从 1 开始**）；
- 响应：`{ "items": [...], "total": 123, "page": 1, "pageSize": 20 }`；
- **单页硬上限 100**：传 `pageSize=1000` 会被截断到 100，但 `total` 仍正确（用它算总页数）；
- 注意：`pageSize=-1` 时 `total` 会返回 0（MyBatis-Plus 的负数行为）——**UI 不要传负数**。

### 1.4 幂等（提交类接口）
- `POST /api/v1/submissions` 支持 `Idempotency-Key: <前端生成的唯一串>` 请求头；
- 同键同内容 → 返回首次结果（不会重复提交）；同键不同内容 → **409 `SUBMISSION_DUPLICATE`**；
- 建议：一次用户点击生成一个 key（如 `uuid()`），重试沿用同一个 key。

### 1.5 追踪
- 响应头会回显 `X-Request-Id`（不传则后端生成）；想串联前端与后端日志时自行带上。

### 1.6 限流（会真实触发）
- **边缘层**：登录端点 **6 次/分（突发 3）**，其余 API 20 次/秒 → 超限 **429**；
- **业务层**：同一账号连续登录失败 5 次 → 锁定 5 分钟 → **429 `AUTH_RATE_LIMITED`**；
- UI 建议：429 时提示"稍后再试"，不要自动重试。

---

## 2. 登录

```
POST /api/v1/auth/login
Body: { "username": "admin", "password": "admin123" }
200 → { "data": { "accessToken": "...", "expiresAt": "2026-..." } }
401 → AUTH_INVALID_CREDENTIALS   // 账号或密码错误（不区分，防枚举）
429 → AUTH_RATE_LIMITED          // 连续失败被锁
```

**角色**：`ADMIN`（管理员，全部权限）、`TEACHER`（老师，考试限自己；题库共享，可读全部已发布题目，题目修改限自己创建）、`CANDIDATE`（考生）。

---

## 3. 端点目录（按管理端页面分组）

> 角色列写 `TEACHER(限归属)` 表示：老师只能操作**自己拥有**的考试；题目是共享题库，老师可读全部已发布题，但只有创建者或管理员能修改、发布版本、看测试点。越权与不存在**同样返回 404**（隐藏存在性，不是 bug）。

### 3.1 考试生命周期
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/contests` | TEACHER/ADMIN | 建考试 `{name, description?, startAt, endAt}` |
| GET | `/api/v1/contests?status=&page=&pageSize=` | 登录即可 | 列表（老师只看到自己的） |
| GET | `/api/v1/contests/{id}` | 登录即可 | 详情（老师越权 404） |
| POST | `/api/v1/contests/{id}/review` | TEACHER(限归属)/ADMIN | 提交审核：`DRAFT → REVIEWING` |
| POST | `/api/v1/contests/{id}/publish` | **ADMIN** | 发布：`REVIEWING → PUBLISHED`，**并自动生成随机题目分配** |
| POST | `/api/v1/contests/{id}/archive` | **ADMIN** | 归档：终态 |

状态机：`DRAFT → REVIEWING → PUBLISHED → RUNNING → FINISHED → ARCHIVED`（`RUNNING/FINISHED` 由定时任务按时间自动推进）。
非法流转 → **409 `CONTEST_INVALID_TRANSITION`**。

### 3.2 名单
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/contests/{id}/students` | TEACHER(限归属)/ADMIN | 加一名学生 `{studentNo, username, displayName, initialPassword}`（auth 建号 + 报名，一次完成） |
| GET | `/api/v1/contests/{id}/students?page=&pageSize=` | 同上 | 名单分页 |
| POST | `/api/v1/contests/{id}/students/import` | 同上 | **CSV 批量导入**（`multipart` 或文本，字段同上；逐行调用 auth，失败行不回滚——**UI 要展示逐行结果**） |
| POST | `/api/v1/contests/{id}/students/{studentId}/password-reset` | 同上 | 重置密码 `{temporaryPassword}` |
| DELETE | `/api/v1/contests/{id}/students/{studentId}` | 同上 | 移出名单（同时使该学生会话失效） |

> ⚠️ 已知缺陷：任何 TEACHER 都能重置/查询**任意**学生账号（归属校验未实现）。UI 不要因此暴露"跨班搜索学生"的功能。

### 3.3 考生会话与监考
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/contests/{id}/session` | CANDIDATE | 考生进考试（创建/恢复会话） |
| POST | `/api/v1/contests/{id}/session/heartbeat` | CANDIDATE | 心跳 |
| POST | `/api/v1/contests/{id}/students/{studentId}/session/terminate` | TEACHER(限归属)/ADMIN | 管理端终止该考生会话（终态） |

> **没有**"实时屏幕/摄像头/截图"能力——media-service 未实现，见第 6 节。

### 3.4 公告
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/contests/{id}/announcements` | TEACHER(限归属)/ADMIN | 发布 `{content, important?, requireConfirmation?}`，**content ≤ 4000** |
| GET | `/api/v1/contests/{id}/announcements` | 登录即可 | 列表（考生拉取即"送达"，写 `deliveredAt`） |
| POST | `/api/v1/contests/{id}/announcements/{aid}/confirm` | CANDIDATE | 考生确认（写 `confirmedAt`，**幂等**，重复确认时间不变） |

> 公告**不可编辑/撤回**（一期设计：纠错就发新公告）。"指定题目公告"未实现。

### 3.5 随机题目分配
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/contests/{id}/problems` | TEACHER(限归属)/ADMIN | **把题目加入考试并锁定版本** `{problemId, problemVersionId}` |
| GET | `/api/v1/contests/{id}/problems` | 同上 | 考试题目清单（含锁定版本） |
| DELETE | `/api/v1/contests/{id}/problems/{problemId}` | 同上 | 移出题目 |
| GET | `/api/v1/contests/{id}/assignments/me` | CANDIDATE | 考生看自己的分配 |
| GET | `/api/v1/contests/{id}/assignments/{studentId}` | TEACHER(限归属)/ADMIN | 老师看某学生的分配（含 `randomSeed`，可复盘） |
| POST | `/api/v1/contests/{id}/assignments/generate` | 同上 | 显式重算分配（**幂等**：已有分配的学生跳过） |

> 分配在**发布考试时自动生成**；每人抽多少题由 `contests.problems_per_student` 决定（NULL/0 = 用全部锁定题目）。
> ⚠️ 当前**没有**修改该字段的接口——UI 若需要"每人抽 N 题"，需要先加一个 PATCH 接口（或先按"全部题目"做）。

### 3.6 题目与版本 / 测试点
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/problems` | TEACHER/ADMIN | 建题（创建即 v1 草稿） |
| GET | `/api/v1/problems?page=&pageSize=&keyword=` | TEACHER/ADMIN | 列表（老师只看到自己的） |
| GET | `/api/v1/problems/{id}` | 登录即可 | **当前发布版本**详情（无测试点）；老师越权 404、考生只能读已发布版本 |
| POST | `/api/v1/problems/{id}/versions` | TEACHER(限归属)/ADMIN | 建新版本（发布后不可变，改动建新版本） |
| GET | `/api/v1/problems/{id}/versions` | 同上 | 版本列表 |
| POST | `/api/v1/problems/{id}/versions/{vid}/publish` | 同上 | 发布该版本（切换 `current_version_id`） |
| GET | `/api/v1/problems/{id}/versions/{vid}/testcases` | TEACHER(限归属)/ADMIN | **测试点**（管理端可见） |

题目字段：`title, statement, sampleInput, sampleOutput, timeLimitMs, memoryLimitKb, outputLimitKb, allowedLanguages(逗号分隔如 "cpp,python"), testcases[{name, inputContent, expectedOutput}]`。

### 3.7 提交与判题
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/submissions` | CANDIDATE | 提交 `{contestId, problemId, language, sourceCode}`，**sourceCode ≤ 65536 字符** |
| GET | `/api/v1/submissions/{id}` | 本人或 ADMIN | 提交详情与状态 |

判题状态：`QUEUED → JUDGING → ACCEPTED / WRONG_ANSWER / COMPILE_ERROR / TIME_LIMIT_EXCEEDED / MEMORY_LIMIT_EXCEEDED / RUNTIME_ERROR / OUTPUT_LIMIT_EXCEEDED / SYSTEM_ERROR`。
> ⚠️ 当前 `SubmissionView` **不返回**编译错误信息、耗时、内存与逐测试点结果——UI 只能展示 verdict 与总分。

### 3.8 审计账本
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/api/v1/audit/records?action=&actorId=&contestId=&page=&pageSize=` | **ADMIN** | 审计查询 |

> 账本**只追加**（DB 触发器拒绝 UPDATE/DELETE）。当前查询**不支持**按 `targetId`/时间范围过滤，也不返回 `eventId`/`originalOccurredAt`。

### 3.9 成绩与分析（analytics-service）
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/api/v1/analytics/contests/{id}/scoreboard?page=&pageSize=` | TEACHER(限归属)/ADMIN | **成绩榜**：`rank, studentId, studentNo, displayName, totalScore, solvedCount, lastEffectiveAt, computedAt` |
| GET | `/api/v1/analytics/contests/{id}/scoreboard/me` | CANDIDATE | 考生看自己那行（不可见时 `visibility=HIDDEN`，**不是 404**） |
| GET | `/api/v1/analytics/contests/{id}/score-distribution?bucketSize=` | TEACHER/ADMIN | 分数直方图（`bucketSize` 1..100，越界 **400 `ANALYTICS_INVALID_RANGE`**） |
| GET | `/api/v1/analytics/contests/{id}/problems?page=&pageSize=` | TEACHER/ADMIN | 题目维度统计（无人提交的题也列出） |
| GET | `/api/v1/analytics/contests/{id}/problems/{pid}/stats` | TEACHER/ADMIN | 单题统计 |
| GET | `/api/v1/analytics/contests/{id}/students/{sid}/profile?from=&to=` | TEACHER/ADMIN | 学情（名次/总分/verdict 分布/逐题最佳分/时间线） |
| GET | `/api/v1/analytics/contests/{id}/risk/summary` | **ADMIN** | 风险汇总（计数 + 三个分布 + 热力图） |
| GET | `/api/v1/analytics/contests/{id}/risk/students?page=&pageSize=` | **ADMIN** | 风险学生分页（可疑度降序） |

> ⚠️ **成绩榜是物化表**：某场考试**第一次**查询可能返回空 `items`，几秒后再查就有数据（读路径自愈，30 秒冷却）。UI 建议首次空时 3 秒后自动重试一次，或显示"统计中…"。
> 考试在 analytics 里不存在 → **404 `ANALYTICS_RESOURCE_NOT_FOUND`**（与越权同码）。

### 3.10 风险事件与审核（anti-cheat-service）
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/monitor/events` | CANDIDATE | 客户端上报 `{contestId, eventType, detail, occurredAt}`；`eventType` 必须大写下划线，**每学生 30 条/5 分钟**（超限 429） |
| GET | `/api/v1/monitor/events?contestId=&studentId=&severity=&reviewStatus=&page=&pageSize=` | **ADMIN** | 风险事件分页 |
| POST | `/api/v1/monitor/events/{eventId}/review` | **ADMIN** | 审核 `{action: ACKNOWLEDGE\|CONFIRM\|DISMISS, note}`；**终态必须填 note**（空白 400），**并发终审只有一个成功**（另一个 409） |

审核状态机：`OPEN → ACKNOWLEDGED → CONFIRMED / DISMISSED`（后两者是终态，不可再改）。
严重度由服务端按事件类型推导，前端**不要**自己算。

### 3.11 AI 作业与结论（ai-service）
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/ai/jobs/code-review` | TEACHER(限归属)/ADMIN | 代码评审 `{contestId, submissionId}` → 返回 `jobId`（异步） |
| POST | `/api/v1/ai/jobs/problem-draft` | TEACHER(限归属)/ADMIN | 出题草稿 `{contestId, requirement, difficulty?, languageHint?}` |
| GET | `/api/v1/ai/jobs/{jobId}` | TEACHER(限归属)/ADMIN | 作业状态：`PENDING → RUNNING → SUCCEEDED / FAILED`（含 `errorCode`、`statusDetail`、`attempts`） |
| GET | `/api/v1/ai/jobs?contestId=&jobType=&status=&page=&pageSize=` | 同上 | 作业列表 |
| GET | `/api/v1/ai/findings?contestId=&submissionId=&findingType=&page=&pageSize=` | 同上 | 结论列表 |
| GET | `/api/v1/ai/findings/{findingId}` | 同上 | 结论详情（`detailJson` 是结构化产出） |
| GET | `/api/v1/ai/drafts?contestId=&page=&pageSize=` | 同上 | 出题草稿列表 |
| GET | `/api/v1/ai/drafts/{draftId}` | 同上 | 草稿详情 |
| GET | `/api/v1/ai/provider` | **ADMIN** | Provider 运行态（`mode / baseUrlHost / chatModel`，**不回显 api-key**） |

> **只有两类作业**：`CODE_REVIEW`、`PROBLEM_DRAFT`。风险研判与语义查重**已被删除**，不要做对应 UI。
> 作业是**异步**的：UI 提交后拿 `jobId` 轮询（建议 3~5 秒一次，最多 2 分钟）。
> `finding.score` 是**可疑度/参考分，仅用于排序展示**，不要当成成绩或自动处置依据。
> 草稿的 `reviewStatus` 恒为 `DRAFT`——**教师确认入口尚未实现**，UI 只能展示，落库仍走题目创建接口。

### 3.12 死信运维（submission-service）
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/api/v1/submissions/admin/dead-letters` | **ADMIN** | 列出投不出去的事件（死信） |
| POST | `/api/v1/submissions/admin/dead-letters/{eventId}/replay` | **ADMIN** | 人工重放 |

### 3.13 账号管理（auth-service）
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/api/v1/auth/users?role=&status=&keyword=&page=&pageSize=` | **ADMIN** | 账号分页；keyword 匹配账号/姓名/学号 |
| POST | `/api/v1/auth/users` | **ADMIN** | 创建子管理员（ADMIN）/教师（TEACHER）/学生（CANDIDATE） |
| POST | `/api/v1/auth/users/{userId}/password-reset` | **ADMIN** | 重置密码 `{temporaryPassword}`；旧令牌立即失效 |
| POST | `/api/v1/auth/users/{userId}/status` | **ADMIN** | 启用/禁用 `{status: ACTIVE\|DISABLED}`；禁用吊销令牌 |

> 学生账号必须提供唯一 `studentNo`；非学生账号忽略该字段。账号名全局唯一。
> 管理员不能禁用当前登录账号；重置密码或禁用都会写 `tokens_valid_after`，该账号需重新登录。

### 3.14 平台公告（contest-service）
| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/api/v1/announcements?page=&pageSize=` | TEACHER / **ADMIN** | 平台公告分页；重要公告置顶，其余按发布时间倒序 |
| POST | `/api/v1/announcements` | **ADMIN** | 发布 `{title, content, important}`，记录发布人与时间 |
| DELETE | `/api/v1/announcements/{announcementId}` | **ADMIN** | 删除公告；不存在 404 `CONTEST_NOT_FOUND` |

> 平台公告与考试公告（`/api/v1/contests/{contestId}/announcements`）相互独立。
> 公告发布后不可编辑，纠错应再发一条；教师只读，不显示发布/删除入口。

---

## 4. 错误码速查（UI 分支用）

| code | HTTP | 含义 / UI 处理 |
|---|---|---|
| `AUTH_INVALID_CREDENTIALS` | 401 | 账号或密码错误 |
| `AUTH_RATE_LIMITED` | 429 | 登录过于频繁，提示稍后再试 |
| `VALIDATION_FAILED` | 400 | 参数校验失败（字段级原因在后端日志，UI 展示通用提示 + 高亮表单） |
| `CONTEST_NOT_FOUND` / `PROBLEM_NOT_FOUND` / `SUBMISSION_NOT_FOUND` | 404 | **越权与不存在同码**，统一显示"不存在或无权访问" |
| `CONTEST_INVALID_TRANSITION` | 409 | 考试状态不允许该操作 |
| `CONTEST_PROBLEM_NOT_IN_CONTEST` | 404 | 题目不在该考试（提交时最常见） |
| `SUBMISSION_PROBLEM_NOT_IN_CONTEST` | 404 | 提交的题未分配给该考生 |
| `SUBMISSION_DUPLICATE` | 409 | 同幂等键不同内容 |
| `SUBMISSION_LIMIT_EXCEEDED` | 429 | 提交频率超限 |
| `MONITOR_INVALID_TRANSITION` | 409 | 风险事件审核流转非法 |
| `MONITOR_REVIEW_REASON_INVALID` | 400 | 终态审核未填原因 / 原因超长 |
| `MONITOR_EVENT_TYPE_INVALID` | 400 | 上报的事件类型格式不对 |
| `MONITOR_REPORT_RATE_LIMITED` | 429 | 上报过于频繁 |
| `ANALYTICS_RESOURCE_NOT_FOUND` | 404 | 考试不存在或无权访问 |
| `ANALYTICS_INVALID_RANGE` | 400 | 分页/桶宽等参数越界 |
| `INTERNAL_ERROR` | 500 | 未分类异常；把 `requestId` 给后端排障 |

---

## 5. UI 必须知道的 12 个行为细节

1. **越权 = 404**：老师访问别人的考试/题目时返回"不存在"，不要提示"无权限"（会泄露存在性）。
2. **考生令牌不能调管理端接口**：`403`；前端按 `roles` 隐藏入口。
3. **成绩榜首次可能为空**：物化表自愈有 30 秒冷却，见 3.9。
4. **发布考试会自动生成分配**：发布后名单里新加的学生**不会**自动获得分配（已知缺陷，UI 可提示"发布后加人需重算分配"，并调用 `assignments/generate`）。
5. **题目必须先加入考试并锁定版本**，考生才能提交；否则 404。
6. **提交是异步判题**：`QUEUED → JUDGING → 终态`，UI 要轮询而不是立即显示结果。
7. **提交详情不含编译错误/耗时/逐点结果**，只有 verdict 与总分。
8. **公告不可编辑/撤回**，只能发新公告。
9. **风险事件审核终态不可改**，且必须填原因；并发终审会有一个 409。
10. **AI 作业是异步的**，`/provider` 可核对"数据发去哪"（ADMIN）。
11. **CSV 导入是逐行调用**，可能部分成功——UI 必须展示逐行结果。
12. **限流真实生效**：登录 6 次/分（边缘）+ 连续失败 5 次锁定（业务），UI 不要自动重试登录。

---

## 6. 尚未实现（**不要做 UI**，做了也没接口）

- **监考媒体链路**：截图、实时屏幕、摄像头、WebRTC —— media-service 只有占位 POM
- **WebSocket 推送**：`JUDGING_STATUS`/`RISK_ALERT`/`SCREENSHOT_READY` 等事件推送
- **指定题目公告**、**监考老师（INVIGILATOR）分配**、**成绩终审/发布状态机**
- **AI**：风险研判、语义查重（已删除）；出题草稿的"教师确认落库"入口
- **审计**：按 `targetId`/时间范围过滤、缺口/延迟检测
- **题目分配范围**：考生仍能读任意"已发布"题目的题面（只读本场分配的题未实现）

---

## 7. 快速上手（一条最小流程，可照抄验证）

```bash
# 1) 登录
TOKEN=$(curl -s -X POST http://localhost/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r .data.accessToken)

# 2) 建题 → 取版本 → 发布
PID=$(curl -s -X POST http://localhost/api/v1/problems -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"A+B","statement":"读两个整数输出和","sampleInput":"1 2","sampleOutput":"3","timeLimitMs":2000,"memoryLimitKb":262144,"allowedLanguages":"cpp","testcases":[{"name":"case1","inputContent":"1 2","expectedOutput":"3"}]}' | jq -r .data.problemId)
VID=$(curl -s http://localhost/api/v1/problems/$PID/versions -H "Authorization: Bearer $TOKEN" | jq -r '.data[0].versionId')
curl -s -X POST http://localhost/api/v1/problems/$PID/versions/$VID/publish -H "Authorization: Bearer $TOKEN"

# 3) 建考试 → 审核 → 发布
CID=$(curl -s -X POST http://localhost/api/v1/contests -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"演示考试\",\"startAt\":\"$(date -u -d '-5 min' +%Y-%m-%dT%H:%M:%SZ)\",\"endAt\":\"$(date -u -d '+2 hour' +%Y-%m-%dT%H:%M:%SZ)\"}" | jq -r .data.id)
curl -s -X POST http://localhost/api/v1/contests/$CID/review  -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost/api/v1/contests/$CID/publish -H "Authorization: Bearer $TOKEN"

# 4) 锁定题目 + 加学生
curl -s -X POST http://localhost/api/v1/contests/$CID/problems -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"problemId\":\"$PID\",\"problemVersionId\":\"$VID\"}"
curl -s -X POST http://localhost/api/v1/contests/$CID/students -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"studentNo":"S001","username":"stu001","displayName":"张三","initialPassword":"init-pass-123"}'
```

---

## 8. 当前环境状态与风险（写 UI 时请留意）

- **适合受控测试/演练，不适合真实计分考试**：还有 5 条上线阻断项未清（教师可重置任意学生密码、AI 跨考试读源码、风险上报缺报名校验等）。
- 服务可用性取决于本机是否在跑（10 个服务 + go-judge）；AI 的真实结论需要你提供模型端点与密钥，且必须显式设 `AI_EGRESS_APPROVED=true` 才允许把学生代码发到公网。
- `pageSize` 超过 100 会被截断；`pageSize` 传负数会让 `total` 变 0。
- 判题引擎（go-judge）偶发不可用时，提交会被**重试**而不是直接判 `SYSTEM_ERROR`（这条刚改过）。

---

**给前端 AI 的作业建议**：先把"登录 → 考试列表/详情 → 名单 → 题目与版本 → 发布 → 成绩榜"这条主干做通（这五个页面覆盖了管理端 80% 的价值），再按第 3 节逐组补；遇到本文与 Swagger 不一致时以 Swagger 为准并记下来。
