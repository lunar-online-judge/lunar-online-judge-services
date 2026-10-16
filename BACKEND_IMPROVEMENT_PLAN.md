# Lunar Online Judge：后续后端优化计划

编写日期：2026-10-13。核对项目：`D:\Users\10559\source\lunar-online-judge`，基线 commit `fa9a51cf63b52d7fd878a1be43028a3d17cca0b9`。本文件由源码静态核对得出，未运行集成测试、负载测试或安全利用验证；每项验收是后续实现要求，不代表已通过。当前只生成文档，未修改后端。

> **基线说明（使用前必读）**：本文件写于 `fa9a51c`，此后仓库已推进若干提交，下列条目**已经修复，请勿重复实现**：
>
> - **BE-09 的一部分**："`LoginRateLimiter` 用 `ConcurrentHashMap`、重启清零、多实例不共享" → 已迁 Redis（Lua 原子计数 + TTL + fail-open）；"锁定到期没有重置失败计数"也已在 Lua 脚本内修正；
> - **BE-08 / BE-06 的证据索引**：其中引用的 `services/contest-service/.../api/ApiExceptionHandler.java` **已被删除**——异常处理已统一到 `lunar-common` 的 `ApiExceptionHandler`（由 `WebCommonAutoConfiguration` 注册）；
> - **BE-06 的"待运行验证"已可确认**：`problem-service` **确实没有** `MybatisPlusConfig`（contest / anti-cheat / audit 有），分页行为仍需按真实 `selectPage` 验证。
>
> 另有一批本次审查新发现的"契约与实现不符"，可作为 BE-04 / BE-06 的补充输入（详见 `CHANGELOG.md` 的文档对齐条目与 `docs/api.md`）：事件信封只落库、上线消息只带 `payload`（`eventId` 幂等无法实现）；judge-service 未接入验签、`POST /api/v1/judge/submissions` 恒 401；`INTERNAL_ERROR` 注册但无产生路径、未分类异常不套统一信封；`CONTEST_NOT_ENROLLED` 被 submission 复用；风险复核的类注释自称"条件更新防并发"而代码是无条件更新。
>
> **BE-07 的前置已就位（2026-10）**：服务身份令牌（客户端凭证，`POST /api/v1/internal/service-tokens`，`roles=["SERVICE"]`）与 trace 贯通（`RequestIdFilter` 写 MDC、`AuditRecorder` 自动续接）已实现，审计动作 7 → 10 个（见 `CHANGELOG.md`）。**BE-07 的持久补偿仍未做**：审计目前是同步 HTTP 尽力而为，audit-service 不可用时会丢条目（仅结构化日志兜底），尚未改为"本地 audit outbox 同事务 + 异步投递 + `eventId` 幂等 + dead-letter + `originalOccurredAt`"。
>
> **BE-02 已落地（2026-10）**：`prod` profile 下的生产配置门禁（fail fast，含"缺持久 RSA 密钥""开发种子未关""数据库口令/客户端密钥是开发默认""配置中心未 fail-fast"）与密钥轮换窗口（JWKS 保留退役公钥、多实例互认）已实现并真机验收，见 `CHANGELOG.md` 与 `docs/deployment.md`。**仍未自动化**：首次管理员的"一次性受控初始化流程"目前是文档步骤（生产 `AUTH_DEV_SEED=false` + 受控初始化），未做成脚本/流程。
>
> **BE-01 已落地一部分（2026-10）**：contest / problem 的**归属范围**（`owner_teacher_id` + 管理端入口范围校验，越权按 404 隐藏存在；列表 total 也在范围内）、考生侧按报名放行、内部测试点接口收紧为只认服务身份、auth 密码重置的目标角色与"不改归属"、风险与审计查询先收紧为 ADMIN——均已实现并真机验收（见 `CHANGELOG.md`）。**仍未做**：`INVIGILATOR` 的"被分配考试"关系（监考分配模型）、题目分配与考生可见题目的范围、anti-cheat/audit 的跨服务范围查询（当前用 ADMIN-only 兜住）、权限转移的独立审核动作。
>
> **BE-04 已落地（2026-10）**：contest 的 `contest_problems`（加入题目即**锁定 `problemVersionId`**）+ submission 按锁定版本提交与判题（判题限制与测试点都取该版本）、幂等指纹补 `contestId`、题目不属于考试返回 `SUBMISSION_PROBLEM_NOT_IN_CONTEST`——已实现并真机验收（见 `CHANGELOG.md`）。**仍未做**：按学生随机分配（`docs/exam-process.md` §3 的 assignment 表与 randomSeed）、次数限制的原子校验（当前是"窗口内计数后插入"，并发下可能超出阈值；计数语义已在文档说明）。
>
> **BE-03 已落地一部分（2026-10）**：**复核写入的 CAS 经真机并发实测确认已生效**（两个并发终审恰好一个 200、一个 409；本文档"selectById→检查→updateById"的证据已过时，代码早已改为前态条件更新）；终态结论**必填原因**（空白 400 `MONITOR_REVIEW_REASON_INVALID`，上限 500 字符）、审核日志按纯文本处理——已实现并验收。**仍未做**：上报的 `contestId`/`problemId` 报名/会话/分配校验、迟到事件时间窗口、`clientEventId` 去重、上报速率与请求体大小限制。
>
> **BE-07 进行中（2026-10）**：已落地 ①审计账本**追加式保护**（DB 触发器拒绝 `UPDATE`/`DELETE`）②**`event_id` 幂等前置**（可空列 + 唯一索引）③**异步消费端**（audit-service 接入 RocketMQ，`AuditRecordedConsumer` 消费 `lunar-audit-recorded`，actor 取事件快照、按 `event_id` 唯一幂等）。**下一步（生产者侧，端到端验证在此之后）**：各服务敏感动作改为与本地 outbox 同事务写 `lunar-audit-recorded`（复用各自已有的 OutboxPublisher，无需新管道），并去掉同步 `AuditRecorder` 调用；随后补退避/dead-letter 与 `originalOccurredAt`。注意：消费端与 broker 的连接本轮**未验证**（消费组/主题此刻尚不存在），生产者落地后必须一并验证。
>
> **BE-10 已落地（2026-10）**：公告 `confirm` 的**并发确认幂等**（撞唯一约束视为成功、条件更新不覆盖首次确认时间）+ 正文 4000 上限——已实现并真机验收（并发 10 次全部 200、无 5xx、首次时间不变、确认行数 1）。考生读/确认的报名范围由 BE-01 的控制器层 `requireEnrolled` 拦截；"送达 ≠ 确认"语义本来已分离。**仍未做**：公告统计接口（分母语义未定，按计划先定再实现，管理端继续隐藏）。
>
> **BE-07 已落地一部分（2026-10）**：审计账本的**追加式保护**（DB 触发器拒绝 `UPDATE`/`DELETE`）与 **`event_id` 幂等前置**（可空列 + 唯一索引 `uk_audit_logs_event_id`，供异步消费端做幂等）已实现并真机验证；服务身份令牌、trace 贯通（`RequestIdFilter` 写 MDC）与**事件信封**（`EventEnvelope` + 消费端 `eventId` 幂等：judge 用 Redis 去重、submission 用 `consumed_events` 闸门）也都已就位——异步审计 worker 的前置已齐。**仍未做（下一轮的核心）**：各服务敏感动作改为"与本地 audit outbox 同事务提交" + 异步 worker 用服务身份投递 + audit-service 引入 RocketMQ 依赖与消费端（按 `eventId` 唯一幂等）+ 重试退避/dead-letter + `originalOccurredAt`；当前审计写入仍是同步 HTTP 尽力而为且转发用户 Bearer（token 到期会让补偿永远失败，这是必须改掉的点）。

## 1. 目标与执行规则

优先修复授权、并发和生产配置边界，再改善接口一致性与审计可靠性，最后补管理端真正需要的功能。沿用已有 Spring、PostgreSQL、MyBatis-Plus、Outbox 和服务模块，不为“完整架构”重写整套系统，不同时引入两套同类方案。

本文区分：**已确认实现缺口**（源码可定位）、**待运行验证**（静态线索不足以证明实际故障）、**新增能力建议**（当前没有API，未来单独交付）。所有建议路径、表和错误码都属于提案，不是当前契约。（原文此处链接的 `admin/api-contracts.md` 与所提到的管理后台前端 `admin-web/` 均已从仓库移除，相关引用只作历史上下文。）

P0 是上线真实考试或开放相关角色前的门槛；P1 是基线稳定性和可靠性；P2 是经过需求和测试后再扩展的能力。每个任务必须交付：数据库迁移（需要时）、代码、OpenAPI/契约文档、真实 PostgreSQL 下的关键测试、兼容说明和可观察失败路径。纯前端隐藏按钮不能算后端授权修复。

## 2. 优先级总览

| ID | 优先级 | 问题/目标 | 开放条件或影响 |
| --- | --- | --- | --- |
| BE-01 | P0 | 资源授权与内部账号操作范围 | TEACHER/INVIGILATOR开放前必须完成 |
| BE-02 | P0 | 生产种子、密钥和迁移配置门禁 | 任何生产部署前完成 |
| BE-03 | P0 | 风险复核CAS、终态保护 | 多人同时复核前完成 |
| BE-04 | P0 | 提交所属考试/版本与幂等指纹 | 真实考试提交前完成 |
| BE-05 | P1（并发使用前提升P0） | 题目版本号分配与发布指针 | 多人编辑发布一致性 |
| BE-06 | P1 | 服务端校验、分页与稳定错误 | 所有列表/表单可靠性 |
| BE-07 | P1 | 审计持久补偿与trace贯通 | 密码/复核/发布的可追溯性 |
| BE-08 | P1 | 学生身份、账号归属与跨服务报名 | 防误复用、孤立账号与500错误 |
| BE-09 | P1 | 登录限流、JWT验证与会话失效 | 多实例和禁用/重置语义 |
| BE-10 | P1 | 公告所属关系和确认幂等 | 考生读取/确认正确性 |
| BE-11 | P1/P2 | DTO及版本API可用性 | 减少前端适配和歧义 |
| BE-12 | P2 | 最小新增管理能力 | 按独立能力开通，不抢先做假模块 |
| BE-13 | P1持续 | 测试、监测、迁移与发布流程 | 每阶段的验证基础 |

### 已有实现应保留

考试流转已使用原状态条件更新，服务端已有生命周期调度；不要把它们列为缺失。题目创建和提交创建已有本地事务，提交及风险上报已有 Outbox 相关实现；不要误写成“完全没有事务/MQ/Outbox”。测试点查询已有角色限制，当前发布版题面不返回测试数据；应在这些基础上补资源范围。已有唯一约束和风险/审计索引继续保留，改造围绕确切不足。

## 3. BE-01：资源授权与内部账号操作（P0）

**证据。** `ContestService.list/get` 没有按当前操作人过滤/验证资源归属；`EnrollmentService.list/resetPassword/remove` 没有教师资源范围检查；`ProblemService` 和风险/审计查询主要按请求过滤项查询，不能把筛选参数当授权。各服务 `SecurityConfig` 的 authenticated/角色校验并不等于某考试资源授权。`StudentAccountService.resetStudentPassword` 未校验目标确为 CANDIDATE，也未核对账号管理范围，并会把 `managedByTeacherId` 改成最近重置者。网关未暴露 internal 路由是已有保护，但内部接口仍须信任边界明确。

**推荐模型。** ADMIN全局管理；TEACHER只管理所属或明确授权的考试/题目；INVIGILATOR仅处理被分配考试的风险范围，不默认拥有题目、密码、审计全权；CANDIDATE只能读取已报名且状态允许的考试资源、已分配题目和自己的事件/提交。保留 creator 作为事实，新增明确的 owner/grant 或 assignment 关系表达权限，不将最近一次操作人当归属。

**实现要求。** 查询先叠加服务端scope再应用用户filter，分页total和统计也在scope内；detail和每个write再次检查资源及子资源所属，防止直接ID绕过。身份从已验证JWT/服务身份取，不从body.actorId、query.actorId或任意代理头取。密码重置必须确认账号角色、可管理范围和报名状态，重置不改变owner；权限转移是独立审核动作。统一决定“无scope返回404隐藏存在”还是“403”，同类API保持一致。

服务间认证推荐可信service token（带issuer/audience）与可验证actor委托；逐步替换转发用户Bearer的过渡模式。只放内网和不经网关不能替代服务认证；也不能改成随便相信X-Actor-Id。保留网关不公开/internal的边界，单独约束internal账号与审计写接口。

**验收。** 教师A对教师B资源的列表/total为0或不包含，直接detail、名单、重置、公告、题目测试点、风险、审计都被拒绝；无分配监考不可查目标考试；学生不能读其它考试公告或题目；管理员操作正常。篡改actor/filter/child ID无法扩权；无授权的internal调用拒绝。账号密码重置后owner不变且非CANDIDATE目标拒绝。

**兼容。** 属于收紧授权，现有调用者可能从200变403/404；发布前盘点owner缺失数据并迁移，不能给全部旧数据默认公开。前端在完成测试并更新角色矩阵前继续ADMIN-only。

## 4. BE-02：生产配置门禁（P0）

**证据。** auth `application.yml` 的 `AUTH_DEV_SEED` 默认true、Flyway默认配置关闭；`AuthKeyConfig.parseOrGenerate` 在没有配置密钥时生成开发密钥并警告，但没有明确生产失败门禁。V3 migration存在条件开发种子逻辑。风险是误用开发默认，不能将其表述成已发现线上泄露。

**实现。** 明确dev/test/prod profile；prod禁开发种子，必须提供持久RSA密钥/密钥标识，缺失时启动失败。生产迁移采用受控部署步骤或明确Flyway策略，schema不匹配拒绝就绪；不要求业务实例各自盲目改表。首次管理员通过一次性受控初始化流程建立，生产不携带通用默认密码。定义轮换窗口，JWKS保留有效旧key直到旧token到期，不因多实例临时key造成互相拒绝。

**验收。** prod无key、开devseed、缺schema三种情况fail fast；两个实例JWT互认；轮换前token在规定窗口仍有效；新token使用新kid；日志不输出私钥/密码。部署说明给出配置名、来源和缺失行为，不写真实秘密。

**兼容。** 本地开发可保留显式dev便利配置；生产默认要安全，升级清点既有种子账号和密钥，不自动删除用户数据。

## 5. BE-03：风险复核并发、原因与上报范围（P0/P1）

**证据。** `RiskEventService.review` 在事务里 selectById、检查状态，再updateById；事务不阻止两个请求读到同一个OPEN。后提交者可能覆盖先提交的终态。note在Controller/Service可选，最终结论没有服务端必填规则。report使用JWT学生ID，但contestId/problemId缺少充分的报名/会话/分配校验。

**实现。** 复核用 `WHERE event_id=? AND review_status=?` 条件更新或明确version乐观锁，检查影响行数；0返回409并提供当前状态或可重新查询方式。OPEN允许ACKNOWLEDGE/CONFIRM/DISMISS，ACKNOWLEDGED只允许终态，终态永远不覆盖。CONFIRM/DISMISS原因非空且max500，在服务器执行；未知action400。追加review history记录保存旧/新状态、actor、原始发生时间、原因与trace，与状态更新同事务，避免只保留最后操作。

风险上报检查actor为合法考生、考试报名/题目归属和可信会话；事件严重度仍由服务端推导。对离线重连/考试结束后迟到事件定义可接受时间窗口和来源时间可信度，不能简单把所有迟到事件扔掉。限制detail、eventType、请求体大小和上报速率，必要时通过clientEventId做重放去重；输出/日志按文本处理。

**验收。** 两个并发终态请求最多一个成功，另一个409，history只有一次有效流转；终态不可重写。空白原因、超长和非法action400；其它学生/考试上报拒绝；合法迟到上报按规则接受/拒绝且可解释。日志中的换行和敏感文本不污染结构化字段。

**兼容。** 原有未填原因的最终结论保留历史不伪造；新写收紧校验。前端当前仍按旧API处理，CAS和最新状态字段上线后更新契约。

## 6. BE-04：提交所属关系、版本快照与幂等（P0）

**证据。** `SubmissionService.submit` 已检查上游会话等，但源码仍TODO“题目属于竞赛”与次数上限；指纹使用problemId、language、sourceHash，遗漏contestId，而幂等key作用域包含学生但可跨考试复用。`applyJudgment` 也有先读状态再updateById的路径。已有提交+Outbox本地事务应沿用。

**实现。** 建立contest_problem/assignment关联并固定problemVersionId，发布考试/分配题目时锁定版本，不让后续全局题目发布改变正在考试的题面/测试数据。提交校验报名、运行/允许窗口、题目分配、允许语言与版本快照；次数限制先明确计数语义（失败入队是否计数、重试是否计数）后实现原子校验，不随意设阈值。

判题命令、消费者和读取题面/测试点的内部调用也必须传递并使用固定problemVersionId，不能仅在提交表存快照、执行时仍取全局current版；测试需要验证实际执行使用的测试数据版本。

幂等语义定义为“同一学生、同一操作、同一考试、同一完整请求”。指纹包含contestId及所有影响结果字段，幂等表唯一范围和hash版本明确；同key不同请求返回稳定409，重试相同请求返回原资源。并发插入唯一冲突后重新读取判断，不变500。旧指纹必须版本化处理，不能上线后把历史合法重试都当冲突。

判题结果用结果eventId/attemptId去重和条件更新；终态不可由迟到/重复结果覆盖。重判是独立尝试和审计操作，不复用任意回调覆盖。继续使用已有Outbox并检查实际投递/消费者重放行为，而非另造第二队列。

**验收。** 同一key跨考试不同请求409而非返回旧考试submission；同请求并发只有一条提交/有效出站事件；非分配题目拒绝；题目全局发布新版不改变考试快照；重复/乱序判题结果不覆盖终态。验证超时后重试可获得同一资源。

## 7. BE-05：题目版本分配、发布与标题一致性（P1）

**证据。** `ProblemService.createNewVersion` 使用现有max versionNo+1后插入；唯一(problem_id,version_no)能阻止重复数据，但并发可能落唯一异常并变500。publishVersion检查DRAFT后updateById，再无条件更新problem.currentVersionId。发布只更新指针和updatedAt，主表title未同步，因此列表title可能与当前版本title不同。

**实现。** 新版本在本地事务锁problem行分配递增versionNo，或使用有版本保护的counter；保留唯一约束作为最后防线。发布同事务校验版本归属、DRAFT、必需数据/语言/至少一个测试点，条件更新版本状态和当前指针。定义并发发布策略：推荐expectedCurrentVersionId/锁内序列化，冲突409，避免无提示地覆盖当前版；如果允许发布旧版，应作为明确的切换行为并记录审计。

标题推荐主表作为列表显示标题，首次创建为初始标题，发布后同步为当前发布版标题；新draft的标题不提前改列表。该语义写入DTO文档。历史PUBLISHED版本保持不可变，不因切换current而降回draft。

**验收。** 并发派生版本编号唯一递增，无未映射500；发布两个版本时符合明确策略；版本与题目不匹配拒绝；非法/零测试点发布拒绝；发布后列表title与当前版一致。已有约束不删除。

## 8. BE-06：服务端校验、分页和稳定错误（P1）

**证据。** contest只要求name非空、publish只校验日期存在，缺start<end；ProblemController版本base未@NotBlank，数值/语言/嵌套testcases缺充分校验；公告、风险等字符串有DB上限但Controller未全部@Size。分页参数为long默认值，未统一上限。problem-service使用selectPage，但未发现PaginationInnerInterceptor bean；其Application默认包扫描、common自动配置只有RequestIdFilter。这是**待运行验证的分页风险**，先验证SQL和total/limit，不直接断言线上返回全部数据。

**实现。** Controller DTO和领域层共同保持不变量：名称/标题/题面非空、日期成对/顺序正确、正数资源限制和合理上限、支持语言集合、testcase名称/大小/唯一、note500/content4000/detail容量、nested @Valid。精确字符/字节规则与数据库/HTTP body限制对应；超大输入返回400/413，不让SQL截断或500。新版本提供字段继承时，校验最终解析后的完整版本，不只校验body。

分页统一page>=1，pageSize范围1..100（推荐），非法400；已配置分页插件服务设置maxLimit，problem-service确认并补齐实际配置。排序在时间/版本后加稳定ID tie-breaker，深分页性能通过真实EXPLAIN/压测决定优化，保留已有索引，只为证据中的查询加组合索引。

统一validation、参数类型/枚举、唯一约束、上游4xx、认证/授权错误的envelope和requestId；Spring Security过滤器失败也应由entryPoint/accessDeniedHandler处理。JWKS等标准协议不强行套envelope。错误响应不包含SQL、stack或秘密，字段错误结构可增fieldErrors保持兼容。

ContestService状态更新后返回最新updatedAt（RETURNING或重新查询），沿用现有条件更新和scheduler。scheduler增加错误/调度滞后指标，验证多实例同一条件更新仅一实例成功，不把多次扫描算多次流转。

**验收。** 临界长度/负数/零/非法语言/重复测试点/缺base/错误日期返回稳定400或409；真实DB准备超过两页题目，page1/2无重叠、数量遵守limit、total准确；pageSize超限不会返回无限量。所有401/403/400/409都携带可追踪ID，API失败不回HTML。流转响应updatedAt与GET一致。

## 9. BE-07：审计持久补偿和trace（P1，高优先）

**证据。** 风险review远程AuditClient失败catch后仅记录日志，无持久重试；风险report的Outbox已经存在，但不等于review审计已可靠。部分学生/报名动作只写AUDIT日志。AuditClient.record有traceId参数却未正确传递X-Request-Id，调用方传null，链路断开。审计数据库未见eventId去重，occurredAt在接收端生成，不能保证原始动作时间。旧注释“audit-service未建成”与当前源码不符，应修正。

**实现。** 敏感动作（账号创建/重置、报名增删、考试流转、题目发布、风险复核）与本地audit outbox同事务提交。异步投递至audit-service，consumer按eventId唯一幂等；保存actor/role/服务身份、目标、旧/新状态、originalOccurredAt、traceId，不收密码/token/完整测试材料。远程审计不可用不在提交之后把业务成功改失败，也不丢记录；采用已有Outbox模式扩展，明确重试退避、dead-letter和人工恢复。

异步worker使用自身可信服务身份投递，actor作为已验证的审计快照保存；不持久化和重放用户Bearer，否则token到期会导致补偿永远失败。网络HTTP串联及队列消息透传trace/requestId；请求ID过滤器已存在，应贯穿调用，不另造互不相干的追踪字段。审计列表也执行BE-01 scope。审计记录业务上只追加，DB角色限制UPDATE/DELETE，保留备份/留存策略；不是只有删除API缺失就算不可变。

**验收。** 关闭audit-service后执行复核/重置，业务成功且本地outbox可见；恢复后最终每个eventId只有一条审计。投递重复不重复记录，超时不丢动作，dead-letter有告警和可恢复步骤；原始动作时间与端到端trace一致；敏感数据未入审计。

**兼容。** 新eventId列/unique可先nullable给旧数据，逐步回填技术ID，不能伪造过去缺失的审计。前端不得因为可靠化完成就宣称历史全部完整。

## 10. BE-08：学生身份和跨服务报名（P1）

**证据。** StudentAccountService.findStudent在同时给学号/用户名时AND匹配，注释的“按学号或用户名”容易误导；DB实际学号全局唯一，注释的组织内唯一没有对应tenant字段。EnrollmentService先远程创建账号再本地关系insert，无法跨服务一个事务；select-then-insert可竞争。AuthInternalClient用原始Map解包/默认retrieve异常，Contest handler缺稳定上游异常映射，业务4xx可能变成500。

**实现。** 初期明确单机构全局studentNo，不为当前需求无端引入多租户。匹配策略：学号为稳定身份、username唯一登录名；两者都匹配才复用，一个匹配一个冲突返回明确STUDENT_IDENTITY_CONFLICT409，避免误合并账号。名字不自动覆盖existing账户；身份/用户名变更走独立流程。

跨服务创建采用可重放的operationId/idempotent account provisioning与本地事务关系写入。每一步可重试和恢复，关系失败不删除复用账号；新建孤立账号可标记pending并由对账任务恢复/人工处理。唯一冲突重新读关系判定已报名/已移除恢复，返回稳定结果。关系软删除保留历史。

AuthInternalClient使用typed DTO、显式timeout、合法错误解析和trace传播；上游身份冲突返回409、授权403、不可用503、超时504或统一稳定错误，不能把密码或原始远程响应写日志。账号owner规则按BE-01，密码重置影响全局账户需说明和审计；报名名单可增加enrollmentStatus服务端过滤，不由前端局部筛选冒充。

**验收。** 相同学生并发添加只一账号/一关系；学号相同用户名不同稳定409；账号已存在但无权管理时拒绝；远程成功本地失败可恢复，不删除历史账户；上游400/403/409/503映射可预期。重置不转移owner，disabled或非candidate目标按明确规则拒绝。

## 11. BE-09：登录限流、JWT与会话（P1）

**证据。** LoginRateLimiter是ConcurrentHashMap用户名计数，重启清零、多实例不共享；锁定到期没有重置失败计数，下次失败可能立即再次锁定；任意用户名状态无TTL清理会增长。JwtTokenService有iss/jti但未见aud；resource yml主要配置jwk-set-uri，未见统一issuer/audience validator。后者是**验证覆盖待确认**，需对实际decoder做集成测试，不能仅据此宣布可绕过认证。禁用/重置是否使已有离线JWT失效也须实际验证。

**实现。** 推荐Redis原子TTL计数与用户/IP适度组合，计数周期、锁定周期和恢复规则明确定义；不能让单IP攻击永久锁某账号。Redis不可用时明确降级/拒绝策略，有指标；返回429和Retry-After（若实现），日志不暴露账号存在性。小规模阶段也必须先修本地过期计数和清理。

统一验证签名、exp、issuer、audience、允许algorithm及角色来源；服务token/user token不同aud，key轮换沿BE-02。为禁用、敏感重置、退出定义tokenVersion/session或撤销缓存方案和传播时限，不能将离线JWT视为永远可立即注销。推荐短JWT加服务侧版本检查/撤销缓存，评估每请求延迟；不要随意变更当前前端sessionStorage为cookie而不设计CSRF/跨站迁移。

新增/me、改当前用户密码和passwordChangeRequired受限会话属BE-12；真正执行强制改密需要服务端限制其它业务，不只是UI提醒。refresh是后续可选设计，不是补齐基本管理端的必要前提。

**验收。** 两实例共享限流；到期后按新周期计数；大量随机用户名不会无限内存增长；伪issuer/audience/algorithm token拒绝；禁用/重置/撤销后已有token在承诺时限内拒绝，正常session仍可用。异常路径指标明确且不记录凭据。

## 12. BE-10：公告范围与首次确认幂等（P1）

**证据。** AnnouncementService.listForCandidate/confirm缺报名范围检查，confirm只验证announcement与contest对应；确认select-then-insert会遇并发唯一冲突，先读null再update可能覆盖首次确认时间。管理端deliveredAt/confirmedAt为null是operator DTO设计，并非统计为0；目前只对requireConfirmation公告记录delivery，含义需澄清。

**实现。** 考生读/确认必须处于授权报名范围，结合生命周期和BE-01；用唯一(announcementId,studentId)加原子upsert，COALESCE保留首次deliveredAt/confirmedAt，重复请求返回同一成功语义。content4000服务端限制。定义“送达”是拉取记录而非网络推送已读；确认是主动动作，不可由列表读取自动确认。

新增统计前确定分母是当前ENROLLED还是发布时名单快照，避免移除考生改变历史统计；返回摘要/明细时按考试scope分页，不能泄露其它学生。第一期推荐公告不可变，纠错发布新公告；编辑/撤回若确需增加，应保存修订及审计，不硬删证据。

**验收。** 非本场考生无法拉取和确认；同考生并发确认多次不500，时间不变；delivery不冒充confirm；重要/无需确认公告语义有测试；超长400。统计接口未实现前管理端继续隐藏。

## 13. BE-11：版本契约与DTO一致性（P1/P2）

**已确认差异。** 题目列表未发布currentVersionId用空字符串，创建用null；创建不返回初始versionId；版本详情只有完整数组；语言请求CSV响应数组；null/[] testcases都继承所以无法清空；接口校验与DB容量不一致。

**推荐目标。** currentVersionId统一nullable string；创建返回problemId和initialVersionId；新增管理版单版本GET（仍不对candidate暴露测试点）；语言array编码并提供`GET /capabilities/languages`或同等注册表接口，真实来自judge模板，不以SQL注释宣称支持更多语言。DTO强类型，少用Map，保留业务错误码。

测试点编辑推荐显式`testcaseMode: INHERIT|REPLACE`，REPLACE接收完整集合并按发布要求限制空；或新版本API明确“省略=继承、[]=清空”。这与当前[]继承冲突，必须版本化或加显式字段，不能直接改旧行为导致旧客户端误清测试点。允许清空draft与要求publish至少1点可同时成立，但操作及错误需明确。

**验收。** 旧客户端按旧行为运行，新客户端能区分继承/替换/清空；publish不通过零测试点；create拿到初始versionId且无竞态猜v1；candidate无法查询版本管理或测试点；language registry只输出已能执行语言。大测试数据迁移object storage仅在体积/性能测量证明需要时启动，DB小测试点无需先重写。

## 14. BE-12：新增管理能力（P2，独立交付）

以下是推荐能力清单，**路径为设计建议，当前不可调用**。先做需求最明确、能解除现有前端限制的项，避免一次扩展实时监控/成绩/多租户。

| 能力 | 建议契约与规则 | 依赖与验收 |
| --- | --- | --- |
| 认证资料/改密 | GET /auth/me；POST /auth/password-change；旧密码或受限会话验证，新密码策略与token失效 | BE-01/09；强制改密用户不能绕过执行其它业务，passwordChangeRequired只在成功后清 |
| 草稿考试编辑 | PATCH /contests/{id}，仅DRAFT、expectedVersion；name/日期服务端验证 | BE-01/06/07；并发409，review后拒绝，不替换现有create |
| 考试题目绑定 | 管理关联列表/增删，绑定固定版本，review/publish前检查完整性 | BE-04/05；published以后关联不可随意变，提交基于快照 |
| 提交列表 | GET /submissions，contestId/studentId/problemId/verdict/page/pageSize，DTO不含全部源码 | BE-01/04/06；权限scope、稳定排序、total和单详情一致；源码按需单独权限 |
| 风险单详情 | GET /monitor/events/{eventId}，scope内取最新状态，可附version | BE-01/03；其它考试404/403，409后可刷新目标而不依赖当前页 |
| 公告确认摘要/明细 | summary+分页confirmations，明确分母和首次时间 | BE-01/10；统计和明细一致，非本场资源拒绝 |
| 名单批量导入 | import任务预览/逐项错误/操作ID，不直接一笔无限大POST | BE-01/08；重复导入幂等、密码不入错误下载、部分失败可恢复 |
| 审计时间筛选 | from/to，UTC明确，限定跨度，组合索引经EXPLAIN验证 | BE-01/07；分页scope，越界400，无全表无界导出 |

实时视频/截图/查重/成绩需要独立业务与隐私/数据模型设计，当前缺证据不能从风险事件文本扩写成完整系统。先提供已实现能力的可信管理，再按实际需求扩展。

## 15. BE-13：测试、可观察性和发布（持续）

测试必须覆盖真实PostgreSQL约束/隔离，不只用Mock或H2证明CAS、upsert、唯一、分页正确。推荐Testcontainers或现有等价隔离数据库方案，选择一种，沿用现有测试结构。并发测试用barrier让两个请求进入相同读状态，不靠随意sleep；检查affected rows和数据库最终数据。

关键故障注入：auth超时/4xx、audit停机/重放、数据库唯一冲突、写请求响应丢失、重复/乱序判题结果、两个实例调度、JWT轮换/限流。关键指标：401/403/429、CAS409率、上游错误/延迟、Outbox待处理数/最老年龄/重试/死信、审计补偿延迟、生命周期调度滞后、分页查询耗时。阈值以测试和业务目标定，不编造SLA。

OpenAPI描述角色、参数边界、envelope、错误码；CI做编译/单测/真实DB关键集成/迁移验证/契约差异。prod swagger和直接服务端口按部署策略受限，保持internal不对外网关开放。安全日志脱敏，requestId贯通，不把用户名/学生编号在所有日志任意扩散。

迁移采用先扩展后切换后清理：新增nullable列/索引、回填验证、双读/明确版本兼容、启用约束、最后清废弃字段。不改已发布Flyway脚本，新增版本。每次发布准备backup、schema核对、数据验证和代码兼容回退；不可逆数据变化不承诺简单down migration。

## 16. 阶段路线与前端解锁条件

| 阶段 | 任务 | 完成门槛 |
| --- | --- | --- |
| A：安全基线 | BE-01/02/03/04 | 授权矩阵负例通过；生产配置失败门禁；并发终态和提交幂等通过 |
| B：可靠运营 | BE-05/06/07/08/09/10 | 分页真实DB验证；版本和身份冲突稳定；审计停机可补偿；会话/公告边界通过 |
| C：管理补齐 | BE-11和BE-12逐项 | 新API有scope/契约/迁移/测试，旧客户端兼容；前端按单模块接入 |
| 持续 | BE-13 | CI、监测、迁移和发布报告与实际实现一致 |

建议拆成可审查的小任务：先授权模型与回填，再逐模块scope；CAS单独交付；生产配置单独交付；Outbox可靠审计单独交付；分页与校验按服务交付；新能力每次只开一个完整闭环。依赖交叉时先落通用契约，再改业务服务，不用一个巨型PR混合所有缺陷。

前端解锁TEACHER要求BE-01完整覆盖考试/名单/题目/公告/风险/审计及密码范围，不能只修列表；INVIGILATOR要求有服务端考试assignment和明确读/复核权限。提交列表要求API稳定且BE-04已修，统计要求定义清楚且真实total。任何建议完成前，当前ADMIN-only与隐藏规划模块继续保持。

## 17. 源码证据索引

以下路径相对后端根；方法名用于定位，不依赖后续会变化的行号。

| 任务 | 主要核对源码 |
| --- | --- |
| BE-01 | 对应服务的config/SecurityConfig.java；ContestService.list/get；EnrollmentService；ProblemService；RiskEventService.list；AuditService.query；services/auth-service/src/main/java/io/lunar/auth/api/InternalStudentController.java；services/auth-service/src/main/java/io/lunar/auth/domain/StudentAccountService.java |
| BE-02/09 | services/auth-service/src/main/resources/application.yml；services/auth-service/src/main/resources/db/migration/V3__seed_dev_admin.sql；services/auth-service/src/main/java/io/lunar/auth/config/AuthKeyConfig.java；services/auth-service/src/main/java/io/lunar/auth/domain/LoginRateLimiter.java；services/auth-service/src/main/java/io/lunar/auth/domain/JwtTokenService.java |
| BE-03/07 | services/anti-cheat-service/src/main/java/io/lunar/anticheat/domain/RiskEventService.java（review/report/writeOutbox）；services/anti-cheat-service/src/main/java/io/lunar/anticheat/infra/AuditClient.java；services/audit-service/src/main/java/io/lunar/audit/api/AuditController.java；services/audit-service/src/main/java/io/lunar/audit/domain/AuditService.java |
| BE-04 | services/submission-service/src/main/java/io/lunar/submission/domain/SubmissionService.java（submit/applyJudgment）；相关幂等/Outbox迁移 |
| BE-05/11 | services/problem-service/src/main/java/io/lunar/problem/domain/ProblemService.java；services/problem-service/src/main/java/io/lunar/problem/api/ProblemController.java；services/problem-service/src/main/resources/db/migration/V1__problem.sql |
| BE-06 | 各Controller参数/DTO；contest、anti-cheat、audit包下config/MybatisPlusConfig.java；services/problem-service/src/main/java/io/lunar/problem/ProblemServiceApplication.java；lunar-common/src/main/java/io/lunar/common/web/WebCommonAutoConfiguration.java；services/contest-service/src/main/java/io/lunar/contest/domain/ContestService.java、ContestScheduler.java |
| BE-08 | services/auth-service/src/main/java/io/lunar/auth/domain/StudentAccountService.java；services/auth-service/src/main/resources/db/migration/V2__student_account_fields.sql；services/contest-service/src/main/java/io/lunar/contest/domain/EnrollmentService.java；services/contest-service/src/main/java/io/lunar/contest/infra/AuthInternalClient.java；services/contest-service/src/main/java/io/lunar/contest/api/ApiExceptionHandler.java |
| BE-10 | services/contest-service/src/main/java/io/lunar/contest/domain/AnnouncementService.java；services/contest-service/src/main/java/io/lunar/contest/api/ContestController.java；公告与确认关系迁移 |
| 部署 | services/gateway-service/src/main/resources/application.yml；docker/openresty/nginx.conf；docker/compose.edge.yml |

同包简写的类可在对应服务 `src/main/java/io/lunar/<模块>/` 定位；真正改动前使用rg读取实际代码和测试，再确认问题仍存在。不要因为本文件保留问题描述就重复修已解决问题。
