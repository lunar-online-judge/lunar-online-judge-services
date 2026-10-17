### 变更

- **第二批正确性：分页、源码上限、引擎瞬时故障**
  - **problem-service 补 MyBatis-Plus 分页插件**（新建 `MybatisPlusConfig`）：此前 `selectPage` 生成的 SQL **没有 LIMIT**、`total` 恒为 0，且 `pageSize` 传负数时等于全表导出；现设单页硬上限 100 行
  - **提交源码上限 64 KiB**（`@Size`）：源码随 created 事件进 RocketMQ，broker 拒收 >4MB 消息——没有上限时一条超大源码会让事件转死信、提交**永久卡 QUEUED**；现在入口直接 400
  - **引擎瞬时故障不再固化为 SYSTEM_ERROR**：`GoJudgeAdapter` 把 `RestClientException`（连不上/超时/5xx）改抛 `JudgeEngineUnavailableException`，由消费端转成 RuntimeException 交给 RocketMQ 重投（消费端有 `eventId` 幂等闸门）。此前任何异常都被映射成 SYSTEM_ERROR = 判题终态，等于给考生判 0 分且无法改判
  - 验证：problem 13 / submission 17 / judge 23 用例全绿；**分页与源码上限的真机验证待服务重启后补**
### 变更

- **风险上报的格式与频率校验（BE-03 剩余项，第 1 批）**
  - `eventType` 只校验**形式**（大写下划线、≤64），不设白名单——文档约定"未知类型按 MEDIUM 保守处理"，新增类型不该因枚举没更新而丢账；但任意文本（含换行）会被写进结构化字段、日志与 analytics 读模型，所以形式必须收口
  - **上报限流**：按学生做窗口计数（每 5 分钟 30 条，用事件表自身的 `reported_at` 计数，不引入新基础设施），超限 429 `MONITOR_REPORT_RATE_LIMITED`
  - 真机验收：非法 eventType → **400** `MONITOR_EVENT_TYPE_INVALID`；正常 → 200；连续上报 32 次 → **29×200 + 3×429**
  - **仍未做**：上报时对 `contestId` 的**报名/会话**校验——anti-cheat 目前没有 contest 客户端（BE-07 时删掉了它的 `ServiceClientConfig`），需要重新引入一个转发考生令牌的内部调用；这是本条剩下的最后一块
# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 结构；版本号语义化。
当前阶段为开发期，服务端能力按实现顺序逐步落地（见 `docs/development-guide.md`）。

## [Unreleased]

### 重构

- **服务间调用改用 Spring 原生声明式 HTTP 接口（`@HttpExchange` + `RestClient`）**（原为各服务手写 `RestClient` 调用）
  - 四个接口（`AuthServiceApi` / `ContestServiceApi` / `ProblemServiceApi` / `AuditServiceApi`）声明路径、参数与 `Authorization` 头；各服务的 `ServiceClientConfig` 用 lunar-common 的 `HttpServiceClientFactory` 把它们装配成 Bean——Spring Framework 6.2 只有 `HttpServiceProxyFactory`，接口自动注册（`@ImportHttpServices` 分组）要 Framework 7 / Boot 4，所以显式装配；baseUrl 默认服务名、`*_BASE_URL` 可覆盖为固定地址排障
  - 三个客户端类（`AuthInternalClient` / `UpstreamClient` / `AuditClient`）保留为适配层，只做三件事：解上游 `ApiResponse` 信封、把"查不到（404）"收敛成 null、转发 Bearer 令牌——业务调用点零改动
  - **评估过 OpenFeign 并放弃**：官方已把该项目标为 feature-complete 并建议迁往 Spring HTTP Service Clients，且需多引一个 starter；原生方案零新增依赖、与 MVC 共用 `HttpMessageConverters`、自带 Micrometer Observation
  - 恢复 lunar-common 的 `LoadBalancedRestClientAutoConfiguration`（这次交给 Boot 的 `RestClientBuilderConfigurer` 配置，从而继承 `spring.http.client.*` 超时与 observation；用 `ObjectProvider` 容忍该 Bean 缺席），并恢复装配冒烟测试（唯一 Builder、Boot 的 Builder 退避、无 loadbalancer 时不装配）
  - 超时显式固化：`spring.http.client.connect-timeout: 2s` / `read-timeout: 10s`（启动期解析，改后需重启）
  - 新增 3 个适配层单测（信封解包、404→null、令牌与 traceId 透传）；顺带修掉 BE-07 记录的一处缺陷：`AuditClient.record` 的 `traceId` 参数此前从未使用，现作为 `X-Request-Id` 透传，audit-service 用它作为账本 traceId
- **全局异常处理统一到 lunar-common**（原来 6 份重复实现，其中 4 份内嵌在 Controller 里）
  - 新增 `ApiException` 抽象基类（`code` + `httpStatus`），六个域异常（`AuthException` / `UserException` / `ContestException` / `ProblemException` / `SubmissionException` / `ReviewException`）改为继承它，删掉各自复制的字段与访问器
  - 新增 `ApiExceptionHandler`（`@RestControllerAdvice`，由 `WebCommonAutoConfiguration` 注册为 Bean）：处理所有 `ApiException` 子类 + `MethodArgumentNotValidException`，错误响应格式全平台一致
  - 删除 auth / contest / submission 三个独立的 `ApiExceptionHandler` 类，以及 `MonitorController` / `AuditController` / `ProblemController` / `InternalStudentController` 内嵌的 Handler
  - 新增 `RequestIds.currentRequestId()` 取代 6 份复制的 `currentRequestId()`；新增 `Pagination.DEFAULT_PAGE` / `DEFAULT_PAGE_SIZE` 与 `Role.AUTHORITY_PREFIX` 收敛散落的魔法字面量
- **按 java-code-style 规范整改全部服务**（`C:\Users\10559\.agents\skills\java-code-style`）
  - 清除内联全限定名、单字母/无意义变量名（含纯类型名 `entity`）、通配符 import、未使用 import、类型不明显的 `var`、超 120 字符长行
  - 分页列表与重置密码响应由 `Map<String, Object>` 改为具名 record（JSON 字段与取值不变）；`ContestService.list` / `ProblemService.list` 不再把 MyBatis `Page` 泄漏给调用方
  - 提取魔法值常量：判题 verdict 与沙箱状态名、题目默认限制、outbox 批量大小、详情截断长度等
  - 风格扫描器命中 189 处 → 2 处（剩余 2 处为 `ApiResponse` 的 `data` 字段：`docs/api.md` 已定的 wire 契约名，按"项目约定优先"保留）；103 个测试全绿
  - 控制器内嵌的请求/响应 record 全部抽成独立类型，落在各服务的 `api.dto` 包（21 个：auth 6 / contest 7 / problem 2 / submission 2 / anti-cheat 2 / judge 1 / audit 1），Controller 只留端点与装配；`StudentResponse.of` / `ContestResponse.of` 由包级改为 `public` 以跨包调用，`docs/development-guide.md` 的包结构说明同步补上 `api/dto/`
  - domain 层 Service 内嵌的 22 个 record（视图/分页/结果）同样抽成独立类型，收进各服务的 `domain.dto` 包（auth 3 / contest 7 / problem 6 / submission 1 / anti-cheat 3 / audit 2）；原先靠同包可见性写 `ProblemService.ProblemView` 这类限定名的引用方（10 个 Controller、3 个 `api.dto`、8 个测试）一律改为显式 import，同时清掉 4 个失效 import——JSON 字段与行为不变；嵌套类型扫描 58 处 → 36 处（剩余为 `XxxException` 子类与 config/infra 辅助类型，本轮不动），`docs/development-guide.md` 包结构说明补上 `domain/dto/`
  - 补抽 `ApiResponse` 内嵌的 `record ApiError` → `ApiError.java`（JSON 形状不变）：上一轮的嵌套扫描没有覆盖 record 体内的类型声明，属漏网
- **文档与实现对齐**（对照源码逐条核对 `api.md` / `architecture.md` / `services.md` / `middleware.md` / `deployment.md` / `README.md`，不改代码）
  - 修正"说已实现、代码里没有"的表述：发布时随机分配题目、`judge_task` 与 `DISPATCHING/RUNNING/FINISHED` 状态机、go-judge 退避/超时补偿、Outbox 死信表与管理员重放、contest/gateway/audit 消费事件（这三个服务没有 rocketmq 依赖）、`POST /api/v1/judge/submissions` 可用性（实际因未接验签恒 401）
  - 修正与代码不符的契约描述：事件信封只落 `outbox_events` 行、上线消息只带 `payload`（因此"按 `eventId` 幂等"无法实现）；`ApiExceptionHandler` 没有兜底 `Exception` 处理器（`INTERNAL_ERROR` 从未产生、未分类异常不套统一信封）；风险复核与判决回写是"读-判-写"无条件更新而非条件更新；`CONTEST_NOT_ENROLLED` 被 submission 复用（会话过期也返回该码）；文档写的 `CAMERA_UNAVAILABLE` 实为代码里的 `CAMERA_MISSING`
  - 修正文档滞后：`services.md` 与 `middleware.md` 的总览表（Nacos `v2.4.2`→`v3.0.3`、Redis"Starter 未接入"、gateway"未实现路由"、Flyway"仅 auth"、judge 的 `judge_schema` 实际不存在）、`api.md` 端点总表（去重复行、去错误的"规划"标注、改为 37 个端点并补"鉴权"列）、`architecture.md` §15 当前实现、`README` 依赖矩阵与 Starter 清单、`deployment.md` 的 judge 端口/profile 与环境变量默认值、本文件"计划中"
  - 悬空引用清零：`admin-web/`（已在 `fa9a51c` 中删除）、`admin/api-contracts.md`、`architecture.md §16`
  - `BACKEND_IMPROVEMENT_PLAN.md` 补"基线说明"（标注 `fa9a51c` 之后已修的条目）并在 `README` 目录登记
- **删除 `lunar-contracts` 空壳模块**：全模块只有一个 `GoJudgeSubmissionRequest` record（4 字段），全仓库 **0 处引用**——judge-service 在 pom 里依赖它，却从未 import 过任何 `io.lunar.contracts` 类型
  - 它描述的其实是 go-judge 的私有 HTTP 协议，与 judge 自己的 `GoJudgeModels`（`RunRequest` / `Cmd` / `Fd`，才是协议权威）不一致，本就不属于"跨服务契约"；而且它自身依赖 `lunar-common`，连"依赖轻量、可给 WebFlux 网关共享"这个唯一站得住的理由也不成立
  - 删除模块目录、根 `pom.xml` 的 `<module>` 与 judge-service 的依赖；`docs/services.md` 改为说明"跨服务契约不单设模块"（服务间 REST 契约走各服务的 `api.dto` / `domain.dto`，引擎契约在 `lunar-common`）。将来若要做类型化事件契约（如 `lunar-submission-judged` 的 payload 类型、可被无 servlet 依赖的 gateway 消费），再按需决定放 `lunar-common` 还是新建一个真正零依赖的模块
- **AI Provider 改为只接入云端 API 模型**（不再支持本地 Ollama 这类跑在本机的模型运行时）
  - `AiProviderProperties` 的 `mode` 默认值 `LOCAL` → `CLOUD`，`base-url` 默认值 `http://localhost:11434/v1` → **空**；`chat-model` / `embedding-model` 的默认值改为中性占位（`gpt-4o-mini` / `text-embedding-3-small`，注释写明"按所选厂商的模型名填"），不绑死某一家厂商
  - **未配置就失败（fail closed）**：`AiPrivacyGuard.verifyCurrentProvider()` 在 base-url 为空/空白时抛 `IllegalStateException`，消息点明"本项目只接入云端 API 模型，必须显式配置 Provider 地址（`AI_PROVIDER_BASE_URL` 或配置中心 `ai.provider.base-url`）"。启动期它让服务起不来，运行期让该次作业失败，两种情况都不会把源码发出去。默认值等于"不配置也能跑"，而"跑起来用的到底是哪个端点"恰恰是数据出境这件事上最不能猜的一项——宁可起不来
  - 配置来源同步：`docker/nacos/config/ai-service.yml` **删除 `base-url` 键**（在 git 真源里写死一个地址，等于把"某个碰巧能通的端点"变成仓库的默认事实标准），`application.yml` 同样不给 base-url 默认值（连空兜底都不写，由 Spring Boot 宽松绑定直接吃 `AI_PROVIDER_BASE_URL`）；接入方式仍是 `langchain4j-open-ai` + base-url，**未引入任何厂商专属 SDK**
  - `docker/compose.ai.yml`（Ollama）内容替换为**一段纯注释的弃用说明**（有意保留占位而不删文件：直接删会让照旧执行的人只看到"文件不存在"这种易误判的报错；YAML 只有注释合法，而 compose 会拒绝它——本机实测 `up -d` 与 `config` 都以 `empty compose file` 退出、exit 1，既不建工程也不起容器，这正是想要的效果）。已确认它**没有被任何其它 compose 文件 `include`/`extends`**（`docker/compose.full.yml` 只收 core / platform / edge / judge）
  - **为什么保留"按地址判内网"这套逻辑**：门禁的判据是 base-url 的地址而不是 `ai.provider.mode`，而自建的内网 OpenAI 兼容网关**同样属于 API 模型**——"只接云端"不等于"只会出网"。更关键的是它是整个出境门禁的基础：没有地址判据就无从定义"出境"，后面"授权只认环境变量 `AI_EGRESS_APPROVED`"与"每次构建模型前复核"（防"把 base-url 热切成公网"绕过）都会失去前提。因此本轮只删掉"本地模型"这个默认落脚点，**没有放宽任何一条出境检查**，也没有改动 `AiModelFactory` 的构建前复核
  - `AiPrivacyGuardTest` 按新语义改写并扩到 11 个用例：默认值即 `CLOUD` + 空地址、空/空白/null 地址在"已授权 + 有 api-key"时同样失败、自建内网网关（回环 / 私有网段 / 容器短名）免授权、公网地址无授权失败、公网 + 已授权但缺 api-key 失败、公网 + 已授权 + api-key 通过、地址不可解析 fail closed

### 修复

- **编译命令缺 `procLimit` 导致真实评测必然编译失败**（真机 C++ 评测发现）：`GoJudgeAdapter.compileCmd` 的 `procLimit` 传了 null，go-judge 默认（0）下编译器只能启动自身、无法派生 `cc1plus`/`as`/`ld`，g++ 报 `posix_spawn: Resource temporarily unavailable`——真机上**所有**提交都会被误判成 `COMPILE_ERROR`
  - 编译命令改用与运行命令同级的进程上限（128）；新增单测断言编译与运行两条命令都带正的 `procLimit`
  - 真机验证：修复后 C++ 的编译 → 按测试点运行 → 输出比对全链路可用，`ACCEPTED`/`WRONG_ANSWER`/`COMPILE_ERROR`/`RUNTIME_ERROR`/`TIME_LIMIT_EXCEEDED` 五类 verdict 全部按预期归一化
- **评测镜像缺工具链导致真实评测无法编译**（真机 C++ 评测发现）：`criyle/go-judge` 官方镜像内没有任何编译器（Debian 13 基础镜像，gcc/g++/python3/javac 全无），编译直接报 `execve: handle: g++: executable file not found in $PATH`
  - 新增 `docker/go-judge/Dockerfile`（在官方镜像上装 gcc/g++/python3/default-jdk-headless，与 `LanguageTemplate` 的语言清单一致），`docker/compose.judge.yml` 由 `image:` 改为 `build:`；此前"官方镜像是否自带工具链未实测"的猜测到此有了确定答案
  - 多语言实测（同一实例）：C++ / Python / Java 三种语言均 `Accepted` 且输出正确；并查明官方 `mount.yaml` 的绑定源**必须在 go-judge 启动时存在**（运行中补装 JDK 后 Java 仍加载不到 `java.security`，重启容器即恢复），因此工具链必须装进镜像；官方 mount.yaml 已为 `/etc/java-21-openjdk`、`/etc/fpc.cfg`、`/etc/mono`、`/var/lib/ghc` 等预留路径，加语言只需补 `LanguageTemplate` + 镜像工具链
- **Outbox 事件因空的 traceId 头永远发不出去**（真机全量 API 测试发现）：`OutboxPublisher` 无条件 `setHeader("traceId", event.getTraceId())`，调用方未带 `X-Request-Id` 时该值为 null → RocketMQ 抛 `IllegalArgumentException: The name or value of property can not be null or blank string!`，事件**永久卡在 Outbox 里每 2 秒重试一次**（真机表现：提交后判题结果永远不回来）
  - 改为仅在 traceId 非空时设该头（submission 与 anti-cheat 两个发布器同修）；修复后两个服务未发布事件数归零，历史积压的 21 条全部投递成功
  - 冒烟用例专门覆盖该场景：不带 `X-Request-Id` 提交 → 事件链路（Outbox → RocketMQ → judge → judged → 回写）端到端跑通、状态变 `ACCEPTED`
- **`baseVersionId` 缺省时新建题目版本返回 404**（真机全量 API 测试发现）：`CreateVersionRequest` 的注释与 `docs/api.md` 都写明"baseVersionId 为空则基于当前发布版本"，但 `createNewVersion` 直接 `selectById(null)` → 抛 `PROBLEM_VERSION_NOT_FOUND`，把"缺参数"报成了"版本不存在"
  - 按注释实现回退：缺省时取题目当前版本，题目尚无发布版本时仍返回 404（语义正确）；补 1 个单测覆盖回退路径
- **并发终态保护缺失（TOCTOU）**：风险事件审核与判题回写都是"读-判-写"的**无条件更新**——两个并发终审会互相覆盖，重复或乱序的判题消息也可能把终态改回去（`RiskEventService` 的类注释还自称"条件更新防并发"，与代码不符）
  - 两处写入改为**前态条件更新**（`where id=? and status/review_status=读到的前态`），影响 0 行时重新读取实际状态并返回 409（`MONITOR_INVALID_TRANSITION` / `SUBMISSION_INVALID_TRANSITION`）：并发下最多一个成功，终态不可再动，失败方也不会补发事件
  - 各加 1 个测试覆盖"并发失败方"路径（`RiskEventServiceTest.concurrentReviewLosesConditionalUpdate`、`SubmissionServiceTest.judgmentLosesConditionalUpdateOnConcurrentWrite`）
- **未分类异常不套统一信封、`INTERNAL_ERROR` 从未产生**：`ApiExceptionHandler` 只处理域异常与校验失败，其余异常落到框架默认错误体（没有统一结构、没有 `requestId`）
  - 新增兜底 `@ExceptionHandler(Exception.class)` → 500 `INTERNAL_ERROR`，异常细节只进日志、不进响应体；`HttpMessageNotReadableException`（请求体缺失/JSON 不可解析）映射为 400 `VALIDATION_FAILED`
  - 兜底会**原样抛出**两类异常以免误伤状态码：框架自带 `ErrorResponse` 语义的异常（404/405/415 等）与 Spring Security 的认证/授权异常（`lunar-common` 不依赖 spring-security，按类名前缀判定后交回过滤器链）
  - `lunar-common` 补 `slf4j-api`（`optional`，运行时由各服务的 logging starter 提供）与 `spring-boot-starter-test`，并首次为该模块加测试：4 个用例覆盖错误码透传、`INTERNAL_ERROR` 信封、不泄露异常细节、请求体不可解析、框架异常不被改写成 500
- **会话未激活借用名单错误码**：提交链路遇到会话不存在/过期/终止时抛 `CONTEST_NOT_ENROLLED`（"不在竞赛名单"），调用方按错误码分支会误判
  - 改用注册表里已有的 `SESSION_NOT_ACTIVE`（409），新增 `SubmissionException.SessionNotActive`（未新增错误码，两者都已在 `docs/api.md` 注册）
- **管理端题目列表未限角色**：`GET /api/v1/problems` 是管理端分页接口（keyword 检索、含未发布题目），原先只要求登录令牌，考生可枚举全部题目
  - 补 `@PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")`，与创建/版本管理同权限；考生取题仍走 `GET /api/v1/problems/{id}`
- **判题 verdict 词表漂移导致真机结果被误判**：`GoJudgeAdapter` 输出短名（TLE/MLE/OLE/RE），而 `SubmissionCreatedConsumer` 白名单与 `SubmissionService.TERMINAL_STATUSES` 只认长名，真机下所有超时/超内存/超输出/运行时错误都会被归一成 `SYSTEM_ERROR`（Mock 引擎只产 ACCEPTED，测试与本地演示均未暴露，且消费者此前零测试覆盖）
  - 词表收敛为 `lunar-common` 的 `JudgeVerdict` 枚举（枚举名即事件与 `submissions.status` 的契约值），成为全平台唯一权威：adapter 直接映射枚举、消费者用 `parse()` 判定、submission 的终态校验由枚举派生；三份手写字符串清单（adapter 的 8 个常量、消费者的白名单 switch、`TERMINAL_STATUSES`）全部删除
  - `docs/services.md` 中与 `docs/architecture.md` §8、库表列注释相矛盾的 `TLE、MLE、RE、OLE` 表述已改正
  - 新增 `SubmissionCreatedConsumerTest` 钉住发布契约：契约长名原样通过，短名与 `PENDING` 兜底 `SYSTEM_ERROR`
  - 无需数据迁移：短名在落库前已被归一，`submissions.status` 中不存在 `TLE` 等取值

- **judge-service 设置 `GO_JUDGE_BASE_URL` 时启动失败**（既有缺陷，全量 API 测试暴露）：`GoJudgeAdapter` 同时有容器装配构造器（带 `@Value`）与单测注入构造器（包级 `GoJudgeTransport`），前者未标注 `@Autowired`，Spring 无法选择 → `Failed to instantiate: No default constructor found`。后果是真实 go-judge 链路从未真正启动过（也解释了 verdict 词表漂移为何长期未被发现）。补 `@Autowired` 后真实引擎可正常启用

- **添加学生响应缺少文档声明的 `passwordChangeRequired`**（全量 API 测试发现）：`docs/api.md"添加学生"` 的响应契约含该字段，实现返回的却是名单视图 `CandidateView`（含加入人/加入时间、无该字段）。现为 `add()` 引入与文档一致的 `EnrollmentResult`（studentId/studentNo/username/displayName/enrollmentStatus/passwordChangeRequired）：新建账号时取 auth 响应值（true），账号复用（学生已在其他竞赛报名）时为 false——本次未下发任何密码；加入人/加入时间仍由名单查询返回，与文档分工一致。`EnrollmentServiceTest` 新增两条断言覆盖新建与复用两种分支

### 变更

- **AI 能力简化（按产品决定裁剪，并把两个新服务纳入版本库）**
  - **保留**：代码评审（`CODE_REVIEW`）、出题草稿（`PROBLEM_DRAFT`）
  - **删除**：风险辅助研判（`RISK_ASSESSMENT`）、语义查重（`SIMILARITY_SCAN`，本就无需模型）——连同其 Handler/Assistant/DTO/测试与 `AntiCheatInternalApi`
  - **去掉隐私守卫**（`AiPrivacyGuard`）：出境门禁与"公网地址必须带 api-key"的规则随之取消，`AiModelFactory` 只保留 base-url 必填的最小校验
  - **findings 不再回写 analytics**：删掉 ai-service 的 Outbox/发布器与 rocketmq 依赖，analytics 删掉 AI findings 读模型（新增 V3 drop 迁移），风险面板不再并入模型结论（`RiskStudentView.findings`、`RiskSummaryView.aiFindingCount` 移除）
  - 连带影响：AI 服务的三个审计动作（`AI_JOB_REQUESTED` / `AI_FINDING_CREATED` / `AI_JOB_FAILED`）不再走 Outbox 入审计账本，改为结构化日志
  - 顺带修复：`analytics-service` 的 `RankingOrder` 漏了 `@Component`，导致该服务**从未成功启动过**（`No qualifying bean of type RankingOrder`）
  - 真机验收（`scripts/ai-test.sh`，16 项 0 失败）：2 个保留端点入队成功；2 个删除端点不再可调用；`/jobs`、`/findings`、`/provider`（不含 embedding 字段）、`/drafts` 正常；考生访问 AI 端点 403；analytics 成绩榜与风险汇总正常
### 新增

- **会话失效承诺：令牌失效时间线（`BACKEND_IMPROVEMENT_PLAN` BE-09 第二部分）**
  - 问题：JWT 是离线校验的，**禁用账号或重置密码不会让已签发的令牌失效**——旧令牌在到期前（默认 30 分钟）照样能用
  - `auth_schema.users` 加 `tokens_valid_after`（V4）：记录"此时间之前签发的令牌一律无效"；敏感重置（`resetStudentPassword`）把它置为当前时刻
  - auth 新增 `GET /api/v1/internal/users/{userId}/tokens-valid-after`（**与 JWKS 同一信任模型**：只在集群内部可达、网关不路由、无需令牌；已在 `SecurityConfig` 放行）
  - lunar-common 的 `TokenRevocationValidator`：拿令牌 `iat` 与该值比较，早于失效点即拒绝；**本地缓存 30 秒**（`auth.jwt.revocation-cache-seconds`）= 失效传播的**承诺时限**；auth 不可达时 **fail-open**（只 warn，与登录限流/消费去重的降级策略一致）
  - **真机验收**：旧令牌先 200 → 置失效点 → 等缓存过期（35s）→ **旧令牌 401** → 重新登录的新令牌 200
  - **过程记录（重要）**：第一次验证时旧令牌仍是 200——原因是新内部端点被 auth 自己的安全过滤链拦成 **401**，资源服务按 fail-open 放行，**整套吊销静默失效**。放行白名单后重验通过；这条"配了但不生效"的坑已写进本节，避免以后重犯
  - **仍未做**：账号**禁用**路径（当前只有敏感重置会置失效点，禁用接口/状态机未实现）；退出登录（无 logout 端点）
- **JWT 的 issuer/audience 校验（`BACKEND_IMPROVEMENT_PLAN` BE-09 第一部分）**
  - 问题：各资源服务只配了 `jwk-set-uri`，于是**只验签名与时间**——任何用同一把密钥签出的令牌都能通过，`iss`/`aud` 形同装饰
  - auth 签发时带上 **`aud`**（`auth.jwt.audience`，默认 `lunar-api`；用户令牌与服务身份令牌共用同一签发路径，两者都带）
  - lunar-common 新增 `JwtValidationAutoConfiguration`（自动装配，默认开启）：按"签名 + exp/nbf + **iss** + **aud**"构造 `JwtDecoder`，`auth.jwt.issuer`（默认 `auth-service`）/`auth.jwt.audience` 可配，`auth.jwt.strict-validation=false` 可临时关闭；已有自定义 `JwtDecoder` 的服务不受影响
  - **真机验收**：①令牌 payload 实测带 `iss=auth-service`、`aud=lunar-api`；②把 contest 的期望 issuer 故意配成 `evil-service` → **合法令牌直接 401**（证明校验真的在跑，而不是配了不生效）；③恢复正确配置 → 200；④服务身份令牌签发 200（内部调用不受影响）
  - **仍未做（BE-09 第二部分）**：禁用/敏感重置/退出后的**会话失效承诺**——当前旧 JWT 在到期前（默认 30 分钟）仍然有效；需要定 tokenVersion/撤销缓存方案与传播时限（含"每请求延迟"的取舍），不能把离线 JWT 当成可立即注销
- **judge 的判决投递补 Outbox（后续后端优化计划 ②）**
  - 此前 judge 评测完**直接 `syncSend`** `lunar-submission-judged`：broker 抖动/重启/网络分区时这一发就丢，结果是**考生成绩永久缺失、提交永远停在 QUEUED**（没有任何地方能补投）
  - judge 引入 `judge_schema`（只一张 `outbox_events` 表，V1 迁移）+ 数据源/Flyway 配置；消费者改为**判决先落本地 Outbox**，由新增的 `OutboxPublisher` 异步投递（含 attempts/指数退避/死信，与 submission/anti-cheat 同一模式）；`@EnableScheduling` 打开调度
  - **真机验收**：①正常链路——judged 事件经 judge 的 Outbox 投递（`published=t`、`attempts=0`），提交最终 `WRONG_ANSWER`，端到端无回归；②5MB 毒消息 → `attempts=3` 且有 `next_attempt_at`（退避生效）→ 顶到 9 再跑一轮 → `attempts=10`、`dead_lettered_at` 置位（不再自动重试）
  - **仍未做**：judge 的死信重放接口（submission 已有 `POST /api/v1/submissions/admin/dead-letters/{eventId}/replay`，judge 未提供）
- **随机题目分配（`docs/exam-process.md` §3）**
  - 新增 `contest_schema.contest_assignments`（V7）：保存 `contestId`/`studentId`/`problemVersionId`/`randomSeed`/`assignmentVersion`/`assignedAt`，保证考试后可复现"这位学生当时看到哪些题、哪个版本"；`contests` 加 `problems_per_student`（每位学生抽取数量，NULL/0 表示不随机）
  - **发布考试时自动生成**：按在册考生逐个抽取；**种子由 (contestId, studentId) 稳定推导**，同一学生重算得到同一组题（不会把正在做的题换掉），生成幂等（已有分配的学生跳过）
  - 新增接口：`GET /api/v1/contests/{contestId}/assignments/me`（考生看自己）、`GET .../assignments/{studentId}`（老师/管理员复盘，限归属）、`POST .../assignments/generate`（显式重算）
  - **提交侧的"题目属于竞赛"改为按分配校验**：已生成分配时内部接口返回"分配给学生的那道题 + 该版本"，没分给他的题按不存在处理（404 `SUBMISSION_PROBLEM_NOT_IN_CONTEST`）；未生成分配时退回考试题池，行为不变
  - **真机验收**：2 道题 + 2 名学生 + 每人抽 1 题 → 发布后**每人恰好 1 条分配**、两人拿到不同题；重复 generate `created=0`；考生 `assignments/me` 返回自己的 1 条；**提交分配的题 200、提交未分配的题 404 `SUBMISSION_PROBLEM_NOT_IN_CONTEST`**
  - **未做**：题目顺序/分值/评分规则的可配置化（§3 里老师可配的其余项）、指定题目公告
- **退避/死信扩展到 anti-cheat 的 Outbox（`BACKEND_IMPROVEMENT_PLAN` BE-07）**
  - 与 submission 同一模式：`anti_cheat_schema.outbox_events` 加 `attempts`/`next_attempt_at`/`dead_lettered_at`（V2 迁移 + 待投递部分索引），发布器失败累计次数 + 指数退避、超上限转死信
  - **真机验收**：插入 5MB 毒消息 → `attempts=3` 且有 `next_attempt_at`（退避生效、未死信）→ 顶到 9 再跑一轮 → `attempts=10`、`dead_lettered_at` 置位 ✓；毒消息已清理
  - **注意**：**judge 没有数据库、也没有 Outbox**（评测结果直接发送，broker 不可用即丢判决）——它要补的不是退避，而是整套 outbox 基础设施，成本更高，已记入计划
  - **仍未做**：死信的**管理员重放接口**
- **Outbox 投递的重试退避与死信（`BACKEND_IMPROVEMENT_PLAN` BE-07，submission 先行）**
  - 原实现投递失败只记一条 warn 就"留待下一轮"——坏 broker 或坏消息会**永远每 2 秒重试**，日志与 CPU 白烧且运维看不出哪条事件投不出去
  - 新增 `attempts` / `next_attempt_at` / `dead_lettered_at` 三列（V3 迁移 + 待投递部分索引）：失败累计次数并**指数退避**（2s 起、上限 300s），超过 `outbox.max-attempts`（默认 10）转**死信**、不再自动重试，等待人工重放
  - **真机验收**：向 outbox 插入一条 5MB 的毒消息（RocketMQ 拒收 >4MB）→ 观察 `attempts=4` 且 `next_attempt_at` 递增（退避生效、未死信）→ 把 `attempts` 顶到 9 再等一轮 → `attempts=10`、`dead_lettered_at` 置位、不再自动重试 ✓
  - **仍未做**：同样缺退避/死信的是 **anti-cheat 与 judge 的 OutboxPublisher**（本轮只改 submission，下一轮按同一模式补齐）；死信的**管理员重放接口**尚未提供
- **审计记录带"动作实际发生时间"（`BACKEND_IMPROVEMENT_PLAN` BE-07 的 originalOccurredAt）**
  - 账本新增 `original_occurred_at`（V5 迁移），消费端把**事件信封的 `occurredAt`** 落库：审计改异步后 `created_at` 是入账时间，投递可能因 audit 重启/积压延迟，排查"什么时候发生的"必须看动作时间
  - 真机验收：V5 已应用；新入账的 `SUBMISSION_CREATED`/`SUBMISSION_JUDGED` 均带 `original_occurred_at`，实测投递延迟 0.6s / 1.9s（入账时间 − 动作时间），改造前的旧记录为 NULL（符合预期）
- **审计异步留痕：submission 两个动作改走本地 outbox（`BACKEND_IMPROVEMENT_PLAN` BE-07）**
  - `SUBMISSION_CREATED`（actor=考生，role=CANDIDATE）与 `SUBMISSION_JUDGED`（actor=服务身份，role=SERVICE、`serviceId=submission-service`）不再同步调 audit-service，改为**与业务同事务**写 `lunar-audit-recorded`；`SubmissionService` 去掉 `AuditRecorder` 依赖、改注入 `spring.application.name` 作为服务身份
  - **真机验收**：提交→判决后，账本里两条记录均由异步路径到达且带 `event_id`（`SUBMISSION_JUDGED` actor_role=SERVICE、service_id=submission-service；`SUBMISSION_CREATED` actor_role=CANDIDATE）
  - **仍未做**：auth 学生账号与 contest 名单/考试流转两个生产者；退避/dead-letter 与 `originalOccurredAt`
- **审计异步留痕的生产者侧（`BACKEND_IMPROVEMENT_PLAN` BE-07 第二步，以 anti-cheat 审核为样本）**
  - 审核不再同步调 audit-service，改为**与审核结果同事务**写一条 `lunar-audit-recorded` 本地 Outbox 事件（复用已有 `OutboxPublisher`，不需要新管道）；**actor 作为事件快照**（actorId/actorRole/targetType/targetId/contestId/detail/traceId）
  - `review` 的签名从"转发的操作者令牌"改为"审核人角色 + traceId"（角色只从已验证令牌的 authorities 取），并删掉 anti-cheat 中已无用的 `ServiceClientConfig` 与 `AuditRecorder` 依赖
  - **真机验收（BE-07 的关键验收项）**：停掉 audit-service 执行审核 → 审核仍 **200 成功** ✓、本地 outbox 有审计事件且已投递 ✓；恢复 audit → 该事件被消费，账本出现 `RISK_EVENT_REVIEWED`（actor=管理员、role=ADMIN、带 eventId 与 traceId）✓；**重投同一事件 → 账本仍恰好 1 条** ✓（`uk_audit_logs_event_id` 唯一索引幂等生效）
  - **仍未做**：其余生产者（auth 学生账号、contest 名单/考试流转、submission 提交与判决）改走同一路径；退避/dead-letter 与 `originalOccurredAt`
- **审计异步留痕的消费端（`BACKEND_IMPROVEMENT_PLAN` BE-07 第一步）**：audit-service 引入 `rocketmq-spring-boot-starter`，新增 `AuditRecordedConsumer` 消费 `lunar-audit-recorded`
  - **actor 取事件里的审计快照**（`actorId`/`actorRole`/`serviceId` 都在 payload 里），不再重放用户 Bearer——token 到期不会让补偿永远失败
  - **幂等靠账本唯一索引** `uk_audit_logs_event_id`：重复投递命中唯一冲突即幂等跳过，"至少一次"投递收敛成"恰好一条"
  - 兼容旧的裸 payload 消息（`EventMessages` 解包），缺 `action` 的消息丢弃并告警
  - `AuditService.record` 增加带 `eventId` 的重载（旧签名委托为 null，同步写入不受影响）；单测 4 个通过、服务健康启动
  - **下一步（未做）**：各服务敏感动作改为与本地 outbox 同事务写 `lunar-audit-recorded`（生产者侧），届时才能端到端验证；本轮**未能验证消费端与 broker 的连接**（该消费组/主题此刻在 broker 上尚不存在），留待生产者落地后一并验证
- **公告范围与首次确认幂等（`BACKEND_IMPROVEMENT_PLAN` BE-10，P1）**
  - `confirm` 的 select-then-insert 改为**并发容忍**：撞唯一约束（并发确认）视为幂等成功并保留首次时间；已有记录时改用**条件更新**（仅 `confirmed_at` 为空才写），不再覆盖首次确认时间
  - 公告正文加服务端长度上限（`@Size(max = 4000)`，超长 400）
  - 真机验收：并发 10 次确认**全部 200、无 5xx、首次确认时间不变、确认行数仍为 1**；超长公告 400；考生读/确认的报名范围由 BE-01 在控制器层拦截（`requireEnrolled`）
  - **未做**：公告统计接口（分母用当前 ENROLLED 还是发布时名单快照尚未确定，按计划先定语义再实现，管理端继续隐藏）
- **审计账本的 eventId 幂等前置（`BACKEND_IMPROVEMENT_PLAN` BE-07 的一部分）**：`audit_schema.audit_logs` 加 `event_id`（可空）与唯一索引 `uk_audit_logs_event_id`——下一轮把审计写入改成"本地 outbox + 异步投递"时，消费端直接靠这个唯一约束做幂等，不必再改表。列可空、唯一索引对 NULL 不生效，因此现有同步写入（106 条，全部 event_id 为 NULL）不受影响；真机验证 V4 已应用、约束就位
- **审计账本的追加式保护（`BACKEND_IMPROVEMENT_PLAN` BE-07 的一部分）**：`audit_schema.audit_logs` 加 DB 触发器，任何 `UPDATE`/`DELETE` 一律被拒绝（应用侧本来就只有 insert/select，这是"就算有人拿到连接也改不了"的兜底）。用触发器而不是 `REVOKE`：所有服务共用同一个数据库角色且是表 owner，对 owner 做 REVOKE 不生效。真机验证：`UPDATE`/`DELETE` 报"审计账本只追加"，`INSERT` 正常
  - **BE-07 的核心仍未做**：敏感动作与本地 audit outbox 同事务提交、异步投递、audit 消费端按 `eventId` 幂等、重试退避与 dead-letter、`originalOccurredAt`；当前审计写入仍是同步 HTTP 尽力而为（audit-service 不可用时丢条目，仅结构化日志兜底），且仍转发用户 Bearer（异步 worker 应改用服务身份，避免 token 到期导致补偿永远失败）
- **风险复核的原因校验与并发实测（`BACKEND_IMPROVEMENT_PLAN` BE-03，P0）**
  - 终态结论（`CONFIRM`/`DISMISS`）**必须填写原因**（空白/纯空格 400 新错误码 `MONITOR_REVIEW_REASON_INVALID`），原因长度上限 500 字符；单一事件不能自动判作弊，处置必须可追溯
  - 审核日志按纯文本处理（换行/制表符折成空格并截断），避免污染结构化日志字段
  - **并发 CAS 经真机实测确认已生效**（计划里"selectById→检查→updateById"的证据已过时）：两个并发终审恰好一个 200、一个 409，终态只有一次有效流转且不可重写
  - **未做**（计划 BE-03 的其余部分）：上报时对 `contestId`/`problemId` 的报名/会话/分配校验（需 anti-cheat 跨服务查询）、迟到事件的可接受时间窗口、`clientEventId` 重放去重、上报速率与请求体大小限制
- **提交所属关系与版本快照（`BACKEND_IMPROVEMENT_PLAN` BE-04，P0）**：考试锁定的题目版本成为提交与判题的权威
  - contest 新增 `contest_problems`（V6 迁移）：加入题目时**必须指定并锁定 `problemVersionId`**，之后全局再发布新版本也不会改变正在进行的考试的题面与测试数据；提供加入/清单/移出（限考试归属者或 ADMIN，BE-01 范围校验）与服务间"取该考试锁定版本"接口
  - submission 的版本来源改为**考试锁定值**（新增 `ContestServiceApi.lockedProblemVersion`），判题限制也取自该版本（新增 problem 的 `GET /api/v1/internal/problems/{problemId}/versions/{versionId}`，只认服务身份）；`problemVersionId` 随 `lunar-submission-created` 事件送到判题引擎，**测试点也按这个版本取**——不再有"提交表存快照、执行时取全局 current 版本"的错位
  - 幂等指纹补上 `contestId`：此前同一个幂等键可以跨考试复用，会被判成"同键异体"或误返首次结果；题目不属于该考试时返回新错误码 `SUBMISSION_PROBLEM_NOT_IN_CONTEST`（404，上游 `CONTEST_PROBLEM_NOT_IN_CONTEST`）
  - 单测覆盖"提交用的是锁定版本而不是当前发布版本"
- **资源授权范围（`BACKEND_IMPROVEMENT_PLAN` BE-01，P0）**：把"角色校验"补成"角色 + 资源归属"两层
  - `lunar-common` 新增 `AccessScope`：ADMIN 全局、TEACHER 仅自己拥有的资源；身份只取令牌的 authorities/sub，**不取请求体、查询参数或任何代理头里的 actor 字段**
  - contest / problem 新增 `owner_teacher_id`（迁移按 `created_by` 回填，**回填不到的存量行仅 ADMIN 可见**——不给旧数据默认开放）；创建即归属创建者，`created_by` 保留为"谁创建"的事实
  - 管理端入口一律先做范围校验，越权与不存在同样返回 **404**（不泄露资源是否存在）：contest 的详情/名单/导入/重置密码/移出/终止会话/公告/提交审核，problem 的列表/新版本/版本列表/发布/测试点；列表查询**先叠加服务端范围再套用户筛选，分页 total 同样落在范围内**
  - 考生侧：考试详情与公告按"是否已报名"放行（新增 `EnrollmentService.isEnrolled`），读别人报名的考试按不存在处理
  - **内部测试点接口收紧为只认服务身份**（`roles=["SERVICE"]`）：此前 submission 用考生令牌调用，等于"内网 + 任意已认证令牌"就能取走测试数据；现在 submission 用自己的服务令牌取
  - auth 密码重置：目标必须是 CANDIDATE（否则按不存在处理），且**重置不再改写 `managedByTeacherId`**（归属不是"最近操作人"）
  - 风险事件查询/审核与审计账本查询**先收紧为 ADMIN 专用**：它们只知道 contestId、不知道竞赛归属，跨服务的范围查询落地前按计划兼容说明继续 ADMIN-only
  - 真机验收（教师 A / 教师 B 两个账号）：B 读 A 的竞赛详情、名单、题目版本、测试点，以及提交 A 的审核、发布 A 的版本，全部 **404**；B 的竞赛/题目列表不含 A 的资源；A 与 ADMIN 正常；教师/ADMIN 令牌调内部测试点接口 **403**、服务令牌 **200**；教师查风险/审计 **403**、ADMIN **200**
- **生产配置门禁与密钥轮换**（`BACKEND_IMPROVEMENT_PLAN` BE-02，P0：上线真实考试前的门槛）
  - `lunar-common` 新增 `ProductionConfigGuard` 与自动装配：激活 `prod` profile 时在应用就绪前校验"绝不能是开发默认值"的配置，不通过**直接启动失败**；消息只含属性名与原因、**不回显取值**（不把口令/私钥写进日志）。通用规则：迁移必须开启、数据库口令与服务客户端密钥不得以 `change-me` 开头、配置中心必须 fail-fast；auth 追加"必须有持久 RSA 私钥""开发种子必须关闭"。属性缺失视为该服务没有这项配置而跳过（如 judge 无 DataSource），本地 dev/test 行为不变
  - **密钥轮换**：auth 的 JWKS 与内部验签改为接受"当前密钥 + `auth.jwt.retired-public-keys` 中的退役公钥"，换密钥不会把未过期的旧 token 全部作废；新 token 自然带新 kid；JWKS 仍只发布公钥（无 `d` 等私钥字段）。顺带把内嵌的 `KeyMaterial` record 抽成独立文件 `JwtKeyMaterial`（仓库"一个类型一个文件"约定）
  - 服务客户端密钥提升为具名属性 `service.identity.client-secret`（contest/submission），使门禁能统一校验
  - 真机验收：`prod` + 开发默认值 → 四条违规一次列出并 fail fast；`prod` + 正确配置 → 门禁通过并正常启动；JWKS 同时发布 `kid-current` / `kid-retired` 且无私钥字段；持久密钥下令牌在资源服务互认（200，重启不再"集体 401"）
- **服务间独立身份（客户端凭证）落地**（`BACKEND_IMPROVEMENT_PLAN` BE-01 推荐的路径，也是 BE-07 异步审计 worker 的前置）
  - auth-service 新增 `POST /api/v1/internal/service-tokens`（免认证端点，凭证在请求体；不经网关）：校验 clientId（服务名）+ clientSecret 后签发 `sub=服务名`、`roles=["SERVICE"]` 的短 TTL 令牌（默认 10 分钟），密钥用常量时间比较，客户端未注册与密钥错误返回同一新错误码 **`AUTH_INVALID_SERVICE_CLIENT`**（401）
  - `lunar-common` 新增共享的 `ServiceTokenApi` / `ServiceTokenRequest` / `ServiceTokenResponse` / `ServiceTokenProvider`（缓存 + 到期前 60 秒自动续签 + 并发只发一次请求）；`Role` 增加 `SERVICE`
  - `AuditRecorder` 支持**服务身份回落**：操作者令牌为空时自动改用服务令牌（账本 actor=服务名、role=SERVICE）。于是补齐了此前只能落结构化日志的 3 个动作——contest 的 `EXAM_STARTED` / `EXAM_FINISHED`（定时任务）与 submission 的 `SUBMISSION_JUDGED`（MQ 消费者），审计动作合计 **7 → 10 个**
  - **顺手修掉 BE-07 点名的"trace 链路断开"**：`RequestIdFilter` 把请求 ID 写入 MDC（请求结束清理，避免线程池串号），`AuditRecorder` 在调用方未传 traceId 时自动取当前请求 ID——此前多个调用点传 null，账本 traceId 断链
  - **再修一个只有真机才暴露的 schema 冲突**：账本 `actor_id` 是 uuid 列，而服务令牌的 `sub` 是服务名（`contest-service`）→ 写入被 PostgreSQL 拒绝（500）。按 BE-07"保存 actor/role/服务身份"新增 `service_id` 列（audit-service V2 迁移），角色为 SERVICE 时 `sub` 落 `service_id`、`actor_id` 留空
  - **仍未做（BE-07 的核心）**：敏感动作与本地 audit outbox 同事务提交、异步投递、consumer 按 `eventId` 幂等、重试退避与 dead-letter、`originalOccurredAt`；当前仍是同步 HTTP 尽力而为，audit-service 不可用时会丢条目（仅结构化日志兜底）
- **审计留痕接入四个服务**（`docs/services.md` audit 节"下一步"：把各服务"先落结构化日志"的点迁入账本）
  - `lunar-common` 新增共享的 `AuditServiceApi`（写入契约）、`AuditRecordCommand`（请求体）与 `AuditRecorder`（尽力而为封装：失败只告警、缺令牌跳过，绝不阻断主流程）——四个调用方需要**完全相同**的审计写入契约，因此不再每个服务复制一份；各服务仍在自己的 `ServiceClientConfig` 里装配 Bean
  - 新接入 7 个动作：contest 的 `STUDENT_ENROLLED` / `STUDENT_REENROLLED` / `STUDENT_PASSWORD_RESET` / `STUDENT_REMOVED`（`remove` 补传操作者令牌）、auth 的 `STUDENT_CREATED` / `STUDENT_PASSWORD_RESET`（服务间接口补传令牌）、submission 的 `SUBMISSION_CREATED`（traceId 一并透传）
  - anti-cheat 从自带的 `AuditClient` 迁到共享实现，删掉两份重复文件；行为不变（审核留痕尽力而为）
  - **仍未入账本**（需要服务间身份，代码中已就地注明）：`ContestScheduler` 的 `EXAM_STARTED` / `EXAM_FINISHED` 与 MQ 消费者触发的 `SUBMISSION_JUDGED`——它们没有用户令牌，服务间独立身份（客户端凭证）落地后再补
- **测试点随事件传输到评测引擎**（打通真实判题的最后一块）
  - problem-service 新增服务间接口 `GET /api/v1/internal/problems/{problemId}/versions/{versionId}/testcases`（`isAuthenticated()`，不经网关、只能内网直连——与 audit 的服务间写入同一约定）；新增可配置体积上限 `problem.testdata.max-bytes`（默认 1 MiB），超限抛新错误码 `PROBLEM_TESTDATA_TOO_LARGE`（413）：测试点要进 MQ，超限的消息会永远发不出去（Outbox 每轮重试）
  - submission-service 提交时取测试点（新增 `ProblemInternalApi` 声明式接口 + `UpstreamClient.problemTestcases`），按 `<name>.in` / `<name>.out` 组装进 `lunar-submission-created` 的 `testFiles` 字段
  - judge-service 的 `SubmissionCreatedConsumer` 从 payload 读 `testFiles`（取代硬编码的 `Map.of()`）——真实引擎因此第一次能拿到测试数据
  - 顺带把新建题目的默认语言白名单从 `c,cpp,java,python` 扩到含 `javascript,typescript`
- **按测试点通过比例给分**（`docs/exam-process.md` §7 早已确认，此前硬编码 `ACCEPTED=100 否则 0`）
  - `JudgeResult` 增加 `passedCases` / `totalCases`（保留 5 参构造器，兼容不区分测试点的引擎）；`GoJudgeAdapter` 把已统计的通过数/总数带出，`MockJudgeEngine` 按 `testFiles` 推算
  - `SubmissionCreatedConsumer` 把两个计数写进 judged 事件，`score = round(passed/total*100)`（未上报计数时退回"满分或 0 分"）；submission 侧 `JudgedConsumer` 原本就会读这两个字段，无需改动
  - 语义：适配器"首个失败即停"，`passedCases` 是失败前通过的数量；"该题最高有效分"聚合仍属成绩读模型（未实现）
- **判题语言扩到 JavaScript / TypeScript**（`docs/architecture.md` §9 早已确认"TypeScript 采用 `tsc` 生成 JavaScript 后由 Node.js 运行"）
  - `LanguageTemplate` 新增 `JAVASCRIPT`（无编译步骤，`node main.js`）与 `TYPESCRIPT`（`tsc --target es2020 --module commonjs --typeRoots /usr/share/nodejs/@types main.ts` → `node main.js`），别名 `js`/`javascript`/`node` 与 `ts`/`typescript`；别名表超过 `Map.of` 的 10 项上限，改用 `Map.ofEntries`
  - `docker/go-judge/Dockerfile` 增装 `nodejs` + `node-typescript`（后者同时提供 `tsc` 与 `/usr/share/nodejs/@types`，TS 模板的 `--typeRoots` 指向它，学生代码可直接用 `require`/`process` 而不必自己 `declare`）；JDK 从 `default-jdk-headless` 固定为 `openjdk-21-jdk-headless`——官方 `mount.yaml` 为 Java 预留的路径就是 `/etc/java-21-openjdk`
  - **修掉一个真机暴露的问题**：`tsc` 把编译诊断写到 **stdout**（不是 stderr），适配器原先只看 stderr，TS 的编译错误信息会整条丢失；`compileStderr` 改为 stderr 为空时回落到 stdout、再回落 `error`
  - 真机验证（同一个 go-judge 实例）：JS `node main.js` → `Accepted` 输出正确；TS `tsc` → `node main.js` → `Accepted` 输出正确；类型错误 → `COMPILE_ERROR` 且消息带 `TS2322`；适配器单测 13 → 16 个
- **CSV 批量导入名单**（`docs/exam-process.md` §9 早已确认，此前只有逐个添加）
  - 新增 `POST /api/v1/contests/{contestId}/students/import`（老师/管理员，`multipart/form-data` 字段 `file`）：列顺序 `studentNo,username,displayName,initialPassword`，首个非空行是同名表头则跳过，空行跳过，字段支持双引号包裹（内部逗号、`""` 转义）
  - **逐行等价于一次"添加学生"**：复用同一套唯一性校验与审计；部分行失败不回滚已成功行，响应逐行返回 `status`（ENROLLED/FAILED）、`lineNumber`、稳定 `errorCode` 与可读原因；未分类失败（上游不可用）只记日志、原因不泄漏内部细节；竞赛不存在时整批 404
  - 幂等靠唯一约束而非显式幂等键：账号唯一性 + 名单唯一约束保证重复导入不产生重复数据，已在名单中的行按 `CONTEST_STUDENT_ALREADY_ENROLLED` 逐行报回（`docs/api.md` 已注明这一与 §9 表述的差异）
  - 新增 `StudentRosterCsv` 解析器（无第三方依赖）与 `RosterEntry` / `RosterRowResult` / `RosterImportResult` 三个 record；单测 6 个（解析器 5 个 + 服务层"部分失败不回滚"1 个），真机 multipart 上传验证 3 行 → `succeeded=1, failed=2` 且成功行未被回滚
- **考试会话的失效路径补齐**（`docs/exam-process.md` §6/§9 早已确认，此前只有"读时拒绝"、没有写入方）
  - 新增 `POST /api/v1/contests/{contestId}/students/{studentId}/session/terminate`（老师/管理员）：`ACTIVE`/`EXPIRED` → `TERMINATED`（终态，考生重进被拒）；写入走前态条件更新，并发下不谎报成功，已是终态时幂等返回
  - 移出名单（软删除）时同步使当前会话失效——原先只在下次心跳/进入时才被拒绝，会话行一直是 `ACTIVE`
  - `ExamSessionService` 的四处会话查询收敛为 `findSession(...)`；新增 5 个单测（终止、过期会话终止、幂等、无会话报错、静默失效）
- **提交频率限制（校验五）**（`docs/exam-process.md` §7"学生可以无限次提交，但服务端仍会做频率限制和反滥用控制"）
  - `POST /api/v1/submissions` 按学生做窗口计数（`submissions` 表为权威，本服务不依赖 Redis），达上限返回 429 `SUBMISSION_LIMIT_EXCEEDED`（该错误码此前已注册但没有任何产生路径），且不落库、不发事件
  - 窗口与阈值可配且**热更新**：`exam.submission.window-seconds` / `exam.submission.max-per-window`（Nacos `submission-service.yml`，代码默认 60 秒 30 次）——§10 的六项待确认里没有提交频率，因此按可配参数实现，不写死常量
  - 并发提交可能同时通过计数，属软限制，不为此引入分布式锁
- **服务间通信改走 Nacos 服务发现**（`docs/middleware.md`"Nacos"节）
  - 8 个服务启动注册到 Nacos `DEFAULT_GROUP`；依赖收敛为根 POM 的 `spring-cloud-alibaba.version=2025.0.0.0`（与 spring-cloud 2025.0.3 同代）+ 各服务 `spring-cloud-starter-alibaba-nacos-discovery`
  - 网关 6 条路由由 `http://localhost:端口` 改为 `lb://<服务名>`（`*_SERVICE_URI` 可覆盖回固定地址排障），因此 gateway 额外引入 `spring-cloud-starter-loadbalancer`
  - 服务间三个客户端（`AuthInternalClient` / `UpstreamClient` / `AuditClient`）改为注入 `@LoadBalanced RestClient.Builder`，baseUrl 默认值由 `http://localhost:端口` 改为服务名；该 Builder 由新增的 `LoadBalancedRestClientAutoConfiguration`（lunar-common）统一提供，条件为负载均衡器在类路径，不按服务名寻址的服务整体退避
  - 注册 IP 固定 `127.0.0.1`（`NACOS_DISCOVERY_IP` 可覆盖）：自动探测会误选本机虚拟网卡（实测 VMware VMnet1 的 192.168.48.1），故与 RocketMQ `brokerIP1=127.0.0.1` 采取同一策略
  - `fail-fast=false`：Nacos 不可达时服务照常启动（仅不注册），`NACOS_FAIL_FAST=true` 切回严格模式
  - JWKS 验签链路（`AUTH_JWK_SET_URI`）**保持固定地址**：auth 是唯一签发方且单实例，改造需自建 `JwtDecoder` 替换 Boot 自动配置，收益不抵风险，列为后续项
  - 实测结论：SCA 客户端 3.0.3 + Nacos 服务端 2.4.2 + public 命名空间**注册与订阅均正常**（SCA 文档对此组合有升级服务端的提示，本项目未触发），因此未升级镜像、未改命名空间

- **登录失败限流迁到 Redis**（`docs/middleware.md`"Redis"节）
  - auth-service 引入 `spring-boot-starter-data-redis`；`LoginRateLimiter` 由单实例内存 `ConcurrentHashMap` 改为 Redis 存储（`auth:login:fail:*` / `auth:login:lock:*`），公开方法签名不变、`AuthService` 零改动
  - 计数与锁定用 **Lua 脚本原子执行**（INCR → 首次设 PEXPIRE → 达阈值写锁标记并重置计数）：拆成多条命令时"INCR 成功但没设上 TTL"会留下永不过期的计数键，即旧实现的两个缺陷之一；另一处修复是锁到期后计数随锁定重置，不再"下次失败立刻再锁"
  - **fail-open**：Redis 不可用时放行登录并打 warn（限流属短期状态层，其不可用不应让全站无法登录）；连接/命令超时设短（500ms / 1000ms）以保证快速失败
  - 实测：实例 A 连续失败 5 次后，**另一个独立 JVM 实例 B 首次尝试即 429**（内存实现下不可能）；停掉 Redis 后登录 0.13s 返回 200 且日志有 warn，恢复后限流立即生效；锁标记 TTL 与配置的 5 分钟一致
  - `LoginRateLimiterTest` 改为对真实 Redis 跑（TTL 过期与多实例共享由服务端控制，假时钟覆盖不了），Redis 不可达时跳过而非失败；新增 `LoginRateLimiterContextTest` 装配守卫，把"两个构造器漏标 `@Autowired`"这类启动期失败拦在测试阶段（`GoJudgeAdapter` 踩过同一坑，已实测移除注解后该守卫会失败）
  - **幂等键与会话状态有意不迁**（理由写入 `docs/middleware.md`）：幂等键以 DB 唯一约束为权威——Redis 抖动或 TTL 过期会导致同键重试新建 submission 并重复触发评测，违反 `docs/api.md` 契约；会话 `status` 是提交通道且 TERMINATED 为终态，Redis 全丢会让超时扫描误判在考会话为 EXPIRED

- **8 个服务引入 Spring Boot Actuator，暴露 `/actuator/health`**（`docs/deployment.md`"健康检查"节）
  - 只通过 HTTP 暴露 `health`（其余端点保持不可达），只返回 `UP`/`DOWN`（`show-details: never`）；health 匿名可访问——否则编排与运维探测必须带令牌，加了等于没用
  - **健康语义按依赖强度区分**：PostgreSQL 计入健康（硬依赖）；**Nacos 与 Redis 不计入**——`NACOS_FAIL_FAST=false` 与限流 fail-open 的语义就是"它们不可用时服务照常工作"，计入会让编排误杀可用实例（auth-service 显式关闭 `management.health.redis.enabled`）
  - judge-service 此前无 `SecurityConfig`，靠 Spring Security 默认行为（全端点 401），会把 health 一并挡掉；新增最小 `SecurityConfig` 把原有基线显式化（用 `HttpStatusEntryPoint` 保持 401 语义而非默认回退 403）并放行 health
  - gateway 类路径无 spring-security，health 直接可达；注意它经 OpenResty 对外可达（仅状态、无细节），其余服务的 health 不经过网关
  - `docs/deployment.md` 冒烟验证新增健康检查步骤，并更新"无健康检查端点"的已知限制表述

- **接入 Nacos 配置中心（Spring Cloud Alibaba Nacos Config）**
  - 8 个服务统一引入 config starter + `spring.config.import=optional:nacos:<服务名>.yml`；**只托管"运行时可变项"**——部署期配置（端口、连接串、服务地址、go-judge 超时）与密钥仍留在 git 与环境变量，自举参数（Nacos 自身地址与命名空间）必须留本地
  - 真正热更新的两项：`auth.login.max-failures` / `lock-minutes`（考试期间可临时调紧锁定策略）、`exam.session.heartbeat-timeout-minutes`（网络抖动时放宽）。`LoginRateLimiter` 与 `ExamSessionService` 改为 `@ConfigurationProperties`（可变 JavaBean）+ **每次调用时读取**——`@Value` 不参与刷新，构造期读入等于固化
  - 调度间隔（`outbox.publish-interval-ms`、`exam.status.sweep-interval-ms`）同样纳管，但 `@Scheduled` 在启动时解析，明确标注"重启生效"
  - **前置修复**：`docker/compose.platform.yml` 给 nacos 挂上命名卷 `nacos-data`——此前无卷，写入的配置在容器重建后会全部丢失（已实测强制重建后配置仍在）
  - **坑一（关键）**：配置必须放非 public 命名空间。nacos-client 3.x 默认按 `public` 取配置，而 Nacos 2.x 的 public 命名空间真实 ID 是空字符串，gRPC 拉取返回 `not-found`；服务端 `config-client-request.log` 中 `tenant=public → not-found` 与 `tenant=null → ok` 两行是直接证据。HTTP API 会做命名空间别名，**用 curl 测不出来**，客户端侧只表现为 `[Nacos Config] config[…] is empty`。改用独立命名空间 `lunar-dev`（服务发现不受影响，继续留在 public）
  - **坑二**：`spring.config.import` 导入的配置**优先级低于本地 yml**——被托管的键若同时在本地 yml 里定义（auth 的 `auth.login.*` 原本就是），Nacos 的值会被永远盖住。已清理本地重复项，离线兜底改由属性类的字段默认值承担
  - 实测热更新：Nacos 里 `max-failures` 由 2 改为 3 后**未重启即生效**（失败 2 次时第 3 次仍可尝试、失败 3 次后第 4 次锁定，与改前的"2 次即锁"形成对照），日志链路 `Receive Nacos config change` → `Refresh keys changed: [auth.login.max-failures]` 完整可见
  - 新增 `LoginRateLimiterContextTest.propertiesBindFromEnvironment` 钉住 `@DurationUnit(MINUTES)` 语义（若误按毫秒绑定，锁定会静默变成 3 毫秒）
  - 已知瑕疵：暂无托管配置的服务（problem / judge / audit / gateway）启动时会打一条 `config[…] is empty` 警告，属预期；Nacos 2.4.2 内嵌 Derby 下命名空间列表接口有 SQL 语法错误（反引号），控制台命名空间页可能报错，不影响客户端读写

- **Nacos 服务端升级到 3.0.3（与捆绑客户端同补丁线）**——不必再靠自定义命名空间绕开 2.x 的坑
  - 起因：SCA 2025.0.0.0 捆绑的 nacos-client 3.0.3 按 Nacos 3.x 语义解析命名空间，服务端停在 2.4.2 时**配置拉取静默失败**（`public` 在 2.x 的真实 ID 是空字符串，gRPC 返回 not-found）。上一轮用独立命名空间 `lunar-dev` 绕过，本轮升级服务端后默认命名空间即可用，**配置与服务发现都回到 `public` + `DEFAULT_GROUP`**
  - 选 3.0.3 的理由：与客户端同补丁线（最适配）、本地镜像已有缓存无需下载、3.0.x 尚未引入 3.3 的"auth 默认开启"变更
  - compose 变更：镜像 `v2.4.2 → v3.0.3`；补上 3.x 启动脚本**强制要求**的 `NACOS_AUTH_TOKEN` / `NACOS_AUTH_IDENTITY_KEY` / `NACOS_AUTH_IDENTITY_VALUE`（开发用值，生产须换强随机值）；控制台映射到宿主机 **8849**（容器内是 8080，与网关端口冲突）；数据卷换成 `nacos-data-v3`（3.x 的 Derby 结构与 2.x 不兼容）
  - 3.x 鉴权模型：刻意不设 `NACOS_AUTH_ENABLE`，沿用镜像默认的 `auth.enabled=false` → **客户端 API 免凭据**（与 2.x 一致）；**控制台另有自己的登录且取消了默认 `nacos/nacos`**，首次访问需初始化管理员密码
  - 3.x 的 API 变化：v1 配置 API 已移除、v3 admin API 需控制台凭据 → 配置发布改走**官方客户端**：新增 `docker/nacos/config/*.yml`（配置内容的 git 真源，回应"配置要跟 git 走"）与 `docker/nacos/publish-configs.sh`（推送脚本，免鉴权、不受 HTTP API 版本差异影响）；v1 命名 API 仍可用，`docs/deployment.md` 的冒烟命令不受影响
  - 实测：8 个服务注册 + 4 个服务加载配置 + 热更新（改 Nacos 不重启即生效，日志 `Receive Nacos config change` → `Refresh keys changed: [auth.login.max-failures]`）+ 142 条端到端断言全绿
  - 过程记录：升级前先用独立容器对 3.0.3 做探测（端口/鉴权/命名空间/发布途径），确认无阻塞后才切生产编排；期间发现并绕开两个 3.x 细节——控制台/管理 API 需登录、v1 配置 API 已移除

- **事件信封正式上线：消费端从此可以按 `eventId` 幂等**（此前消息体只带内层 `payload`，`eventId` 不随消息传输，`docs/api.md` 已记录该差距）
  - `lunar-common` 新增 `EventEnvelope`（生产者：把内层 payload 包成 `eventId`/`eventType`/`aggregateId`/`occurredAt`/`schemaVersion`/`traceId` + `payload` 的完整信封，缺字段在构造点直接拒绝）与 `EventMessages`（消费者：统一解包并**同时兼容旧的裸 payload**，判定依据是三个信封字段同时存在——只看 `payload` 字段会把"内层恰好有 payload 字段"的旧消息剥壳丢数据）；含 4 个单测钉住兼容语义
  - submission / anti-cheat 的 `OutboxPublisher` 改发完整信封；judge 直发的 `lunar-submission-judged` 同样带信封与 `KEYS=eventId` 头
  - judge 的 `lunar-submission-created` 消费去重：新增 `ConsumedEventGuard`（Redis——judge 没有数据库）。**有意"先查、处理成功后再登记"而不是先 SETNX 占坑**：先占坑会在评测中途失败后让重投看到"坑位已占"而直接 ack，判决永久丢失；代价只是极端并发下多评一次。fail-open，Redis 不可用时不去重但仍然评测
  - submission 的 `lunar-submission-judged` 消费去重：新增 `submission_schema.consumed_events` 幂等闸门（V2 迁移）与 `ConsumedEventMapper.tryMarkProcessed`（`on conflict do nothing`，插入与判断合一），与状态回写**同一事务**——事务回滚时登记一起回滚，不会留下"登记了已处理、判决却没落库"的黑洞
- **`lunar-submission-judged` 富化提交身份**：补齐 `contestId`/`problemId`/`problemVersionId`/`studentId`/`language`/`submittedAt`
  - 起因：judge 没有数据库，只能把 created 事件里的身份字段原样带下去；此前 judged 只有 `submissionId` 与 verdict，**除 submission 之外没有任何服务能独立消费这条事件**（分析读模型要靠它建"考试×学生×题目"事实表）
  - 该事件有两个生产者（judge 直发、submission 的内部回写端点），两条路径的内层 payload 形状已对齐，否则其中一路的 `contestId`/`problemId` 会为空
- **contest 新增服务级快照端点** `GET /api/v1/internal/contests/{contestId}/snapshot`（只认 `roles=["SERVICE"]`）：一次给出考试元数据、全员名单（含 `REMOVED`，过滤留给使用方）与锁定题目版本。已有的 `/internal/contests/{id}/session` 是**考生令牌**语义，分析型消费者无法使用
- **anti-cheat 新增审核流转事件** `lunar-risk-event-reviewed`（与审核状态更新同事务）：风险事件此前只在**创建**时进 Outbox，只消费 created 的话分析侧的审核结论会永远停留在 `OPEN`；同时给 created 的 payload 补上 `problemId`（`Map.of` 换成 `LinkedHashMap`——`problemId` 允许为空，而 `Map.of` 遇到 null 值直接抛 NPE）

- **新增 ai-service：AI 能力（首期代码评审），基于 LangChain4j 1.22.0**
  - **为什么用 LangChain4j 而不是手写 HTTP 客户端**：手写版需要自己拼提示词、解析 JSON、校验字段类型、区分"模型不听话"与"网络不通"；这些正是框架的职责。改用 `AiServices` 声明式接口后，**输出契约由返回类型（record）生成的 JSON schema 唯一决定**，解析失败由框架抛 `dev.langchain4j.exception.*`，我们用一层薄适配（`LlmClient.call`）把它翻译成平台已登记的错误码，上层只认平台码、不认框架异常
  - **依赖选择**：只用 `langchain4j-bom` + `langchain4j` + `langchain4j-open-ai`（版本由 BOM 统一，避免模块间漂移）。**不用官方 Spring Boot starter**：它走 `-beta` 版本线，且其静态 Bean 与"Provider 参数经配置中心热切换"冲突——本模块要的是**每轮作业按当前配置取模型**，为此 `AiModelFactory` 按配置指纹缓存模型实例
  - **本地与云共用一个客户端**：Ollama 与云厂商都提供 OpenAI 兼容端点，因此只换 base-url 即可切换 Provider，不再引 `langchain4j-ollama` 形成两条会分叉的路径
  - **数据出境门禁（`AiPrivacyGuard`）**：判据是 **base-url 的地址**而不是 `mode`——"声明 LOCAL 却填了公网地址"是最容易发生的数据出境方式；地址无法解析时 fail closed。云地址 + 未授权 → **启动失败**（与 `ProductionConfigGuard` 同一取舍：宁可起不来，也不静默把学生源码发出去）。`mode`/`egress-approved`/`api-key` 只从环境变量来，不经配置中心
  - **异步作业队列**：`ai_jobs` 即队列，领取用 `for update skip locked` + 租约（多实例下同一作业只被领走一次），失败累计次数 + 指数退避 + 上限转 FAILED；**入队即扣配额**（`insert ... on conflict do update ... where used < 上限` 原子占用）；相同输入指纹命中已完成作业时直接复用，不重复烧配额
  - **产出只作证据**：结论落 `ai_findings`，`score` 是可疑度且**仅用于排序展示**；AI 不写成绩、不流转审核状态、不直接发布题目（出题只落 `problem_drafts` 草稿表，教师确认后走 problem 既有接口）
  - **事件与审计**：`lunar-ai-finding-created` 与 `lunar-audit-recorded` 都经本地 Outbox 异步投递（含指数退避与死信），与 submission/judge/anti-cheat 同一模式；审计动作 `AI_JOB_REQUESTED` / `AI_FINDING_CREATED` / `AI_JOB_FAILED` 带 actor 快照
  - 新增 6 个端点（代码评审入队、作业/结论查询、Provider 运行态）与 1 个 submission 侧的服务间端点（**含源码**的详情，只认服务身份）；`docs/api.md` 登记 9 个 `AI_*` 错误码
  - **语义查重（不需要模型）**：`MinHash` 纯 Java 实现（归一化去注释/字面量/空白 → token 级 k-gram → 128 位签名 → Jaccard 估计）。签名长度固定、系数表用**固定种子**生成——签名必须跨实例/重启/配置变化可比，否则已落库的相似度会不可逆地失去可比性；shingle 大小可配。判定纪律：同一学生的多次提交不互相配对（那是反复提交而非查重目标）、低于阈值不进结论（只作人工复核线索）、提交少于两份不算失败。归一化用手写状态机而非正则，因为注释与字符串会交叉嵌套（`// "` 不是字符串开头）
  - **出题草稿**：产物落 `problem_drafts` 草稿表（`job_id` 唯一索引保证作业重试不产出第二份），教师确认后才由 problem 既有接口落库——"发布后版本不可变"等既有不变量无需为 AI 让路
  - **共享前置**：submission 新增服务间批量取数端点（按考试+题目，含源码），**条数上限由服务端夹取到 ≤500**，调用方无法把它变成全库导出
  - **安全修复（审计发现）**：数据出境授权此前与其他 Provider 参数同属 `@ConfigurationProperties("ai.provider")`，配置中心写一行 `egress-approved: true` 即可静默打开出境。现改为**只认环境变量 `AI_EGRESS_APPROVED`**，并让门禁在**每次构建模型前**复核——只在启动期检查会被"把 base-url 热切成公网地址"绕过
  - **编译修复（审计发现）**：补 `spring-boot-starter-validation`（`@Valid`/`@NotBlank`/`@Size` 需要它，`spring-boot-starter-web` 不传递；缺了直接编译失败，且校验注解运行时也不生效）
  - **风险辅助研判（第 4 项能力）**：证据取自 anti-cheat 新增的服务间风险事件查询（只认服务身份——AI 作业由后台 worker 执行，没有用户上下文，走不了 ADMIN 专用的监考查询）。产出可疑度 + 理由（须引用真实记录，便于逐条回原始事件核对）+ 反方证据 + 人工介入方向。红线在代码里兜底：`recommendedAction` 只允许 `DISMISS`/`REVIEW_MANUALLY`/`ESCALATE`，模型给出约定外取值（例如 PUNISH）时**降级为最保守的 `REVIEW_MANUALLY`**，绝不透传未知动作；不写成绩、不流转审核状态机。无证据时产出 0 分结论而非让作业失败重试
  - **编译与单测验证（首次真正跑通）**：JDK 21 下 `mvn -DskipTests package` 全 12 个模块 BUILD SUCCESS；`EventMessagesTest` / `AiPrivacyGuardTest` / `MinHashTest` / `ModelOutputParserTest` 共 25 个用例全绿
  - **MinHash 两处实测缺陷修复**：① 数值字面量此前**未**折叠成占位符，"改常数"这类规避会奏效（实测：只把 `0` 改成 `42`，相似度掉到 0.81）；② 空签名（无 token 的提交）的 Jaccard 被判为 1.0，两份空提交会互相"高度雷同"。两处分别在 `tokens()` 与 `jaccard()` 层修正。同时把"k-shingle 对**系统性改名**只能给中等分（实测 0.20）"记为已知边界——要覆盖改名需做标识符归一化，而那会把"结构相同、调用的 API 不同"的代码一起拉高，属待产品确认的取舍
  - **网关补 `/api/v1/analytics/**` 路由**：此前 analytics 的 9 个端点只能内网直连
  - **analytics-service（分析读模型）**：新建模块（端口 8088、`analytics_schema`、Flyway 表 `analytics_history`）。**只读投影**：消费 `lunar-submission-judged` / `lunar-risk-event-created` / `lunar-risk-event-reviewed` / `lunar-ai-finding-created` 写入事实，写路径不经过它，因此不影响任何业务不变量；`consumed_events` 幂等闸门与事实写入**同事务**，信封解包复用 `EventMessages`（兼容旧格式裸 payload）。事实表 `submission_facts` **主键取 `submission_id`**——judged 有两个生产者，按提交收敛才能最终一致；`risk_event_facts` 主键取 `event_id`（风险事件不可变）。物化 `student_contest_scores`；参照数据缓存三张表由定时任务按需从 contest 的服务级快照端点刷新（**HTTP 取数在事务外**，考生名单保留 `REMOVED` 状态，过滤留给读模型）。三条读模型：成绩排名（每题取最高分求和；tie-break 可配 `analytics.ranking.tie-break`，缺 `student_id` 会被自动补上以保证分页稳定）、学情与监考风险**按需聚合不物化**（审核状态会反复流转，物化就得跟着失效）；风险可疑度权重外置 `analytics.risk.weight-*`、公式只在 SQL 里出现一处，且 **AI 结论的可疑度不进排序权重**（它可被提示注入影响，不该决定人对学生的怀疑程度）。9 个端点 + 3 个错误码（先登记后用），鉴权三层与 ai-service 同构、**越权与不存在同为 404**；网关 `/api/v1/analytics/**` 路由与 auth 的客户端注册表均已补。已知边界见 `docs/analytics/read-model.md`（重算单飞是进程内的、0 提交学生不入榜、AI 结论为副本不同步）
  - **相似对逐对落库**：`SimilarityPairService.writeFromCompletedJob` 在作业收尾事务内把 `SimilarityScanResult` 的每一对写入 `ai_schema.similarity_pairs`。唯一约束的第三列 `run_id` 取**作业 id**，写入前先 `deleteByRunId` 再逐对插入——重扫/重跑同一作业只留最后一次结果，既不留旧数据也不撞唯一键。结论 JSON 为空/非法/无相似对时 `log.warn` 后返回、**从不向上抛**（结论已在 finding 的 `detail` 里，落库失败不该让作业变 FAILED）。`minhash_similarity` 显式 `setScale(4, HALF_UP)` 对齐 `numeric(5,4)`，避免存值取决于 PG 的隐式取整；`embedding_similarity` 列保留但恒为 null（embedding 通道默认关闭）。单测用**动态代理记录型假 Mapper** 断言"先删后插"的调用顺序（本环境 Mockito 内联 mock 无法自挂载 agent）
  - 未落地：`similarity_pairs` 的按阈值查询端点（数据已落库，当前仍可从 finding 的 `detail` JSON 读到）；analytics 的聚合查询依赖"行 → record"映射（`-parameters` + 两个 MyBatis 开关），**尚无数据库验证**

### 计划中

- `judge_task` 异步评测状态机（重试/超时补偿/指数退避）、死信表与管理员重放
- 竞赛级授权范围（owner/grant）与 `INVIGILATOR` 权限矩阵
- 随机题目分配（发布时生成 + 随机种子 + 分配表）
- 成绩状态机（PROVISIONAL/FINAL）与考生成绩可见范围的完整口径（排名与该题最高有效分已由 analytics-service 落地）
- 考试预检（客户端版本/屏幕/网络/摄像头）与"同一账号仅一个活动会话 + 踢旧设备"
- 媒体链路（监考令牌、WebRTC 信令、截图与 MinIO）与 WebSocket 推送
- 代码查重（JPlag worker）、指定题目公告
- Redis 其余用途（令牌吊销、接口频控、会话在线状态镜像、评测短期锁、截图频控）；登录限流已迁，幂等键与会话状态经评估不迁（见上）
- 题目归属竞赛校验（依赖随机分配表落地）

## [0.5.0] - 2026-10-08

### 变更

- **AI 能力简化（按产品决定裁剪，并把两个新服务纳入版本库）**
  - **保留**：代码评审（`CODE_REVIEW`）、出题草稿（`PROBLEM_DRAFT`）
  - **删除**：风险辅助研判（`RISK_ASSESSMENT`）、语义查重（`SIMILARITY_SCAN`，本就无需模型）——连同其 Handler/Assistant/DTO/测试与 `AntiCheatInternalApi`
  - **去掉隐私守卫**（`AiPrivacyGuard`）：出境门禁与"公网地址必须带 api-key"的规则随之取消，`AiModelFactory` 只保留 base-url 必填的最小校验
  - **findings 不再回写 analytics**：删掉 ai-service 的 Outbox/发布器与 rocketmq 依赖，analytics 删掉 AI findings 读模型（新增 V3 drop 迁移），风险面板不再并入模型结论（`RiskStudentView.findings`、`RiskSummaryView.aiFindingCount` 移除）
  - 连带影响：AI 服务的三个审计动作（`AI_JOB_REQUESTED` / `AI_FINDING_CREATED` / `AI_JOB_FAILED`）不再走 Outbox 入审计账本，改为结构化日志
  - 顺带修复：`analytics-service` 的 `RankingOrder` 漏了 `@Component`，导致该服务**从未成功启动过**（`No qualifying bean of type RankingOrder`）
  - 真机验收（`scripts/ai-test.sh`，16 项 0 失败）：2 个保留端点入队成功；2 个删除端点不再可调用；`/jobs`、`/findings`、`/provider`（不含 embedding 字段）、`/drafts` 正常；考生访问 AI 端点 403；analytics 成绩榜与风险汇总正常
### 新增

- **audit-service 审计账本**
  - 追加式账本 `audit_schema.audit_logs`（仅插入，无更新/删除接口；不记录密码等敏感明文）
  - 服务间写入端点（转发操作者令牌，自动记录 actor/role，系统动作标 SYSTEM）+ 管理端分页查询（动作/操作者/竞赛过滤）
  - 接入样本：anti-cheat 审核动作经 AuditClient 写账本（尽力而为，不阻断主流程）
  - 服务端口 8087；gateway 新增 audit 路由（internal 写入经网关不可达）

## [0.4.0] - 2026-10-07

### 变更

- **AI 能力简化（按产品决定裁剪，并把两个新服务纳入版本库）**
  - **保留**：代码评审（`CODE_REVIEW`）、出题草稿（`PROBLEM_DRAFT`）
  - **删除**：风险辅助研判（`RISK_ASSESSMENT`）、语义查重（`SIMILARITY_SCAN`，本就无需模型）——连同其 Handler/Assistant/DTO/测试与 `AntiCheatInternalApi`
  - **去掉隐私守卫**（`AiPrivacyGuard`）：出境门禁与"公网地址必须带 api-key"的规则随之取消，`AiModelFactory` 只保留 base-url 必填的最小校验
  - **findings 不再回写 analytics**：删掉 ai-service 的 Outbox/发布器与 rocketmq 依赖，analytics 删掉 AI findings 读模型（新增 V3 drop 迁移），风险面板不再并入模型结论（`RiskStudentView.findings`、`RiskSummaryView.aiFindingCount` 移除）
  - 连带影响：AI 服务的三个审计动作（`AI_JOB_REQUESTED` / `AI_FINDING_CREATED` / `AI_JOB_FAILED`）不再走 Outbox 入审计账本，改为结构化日志
  - 顺带修复：`analytics-service` 的 `RankingOrder` 漏了 `@Component`，导致该服务**从未成功启动过**（`No qualifying bean of type RankingOrder`）
  - 真机验收（`scripts/ai-test.sh`，16 项 0 失败）：2 个保留端点入队成功；2 个删除端点不再可调用；`/jobs`、`/findings`、`/provider`（不含 embedding 字段）、`/drafts` 正常；考生访问 AI 端点 403；analytics 成绩榜与风险汇总正常
### 新增

- **anti-cheat-service 风险事件与人工审核**
  - 考生客户端上报（severity 服务端规则表计算，不采信上报值；未知类型保守 MEDIUM）
  - 分页查询（竞赛/学生/等级/审核状态过滤）；审核状态机 OPEN→ACKNOWLEDGED→CONFIRMED/DISMISSED（条件更新防并发、终态保护、动作留痕）
  - 事件入库即写 `lunar-risk-event-created` Outbox（gateway WebSocket 告警的未来消费方）
  - 服务端口固定 8086；gateway 新增 monitor 路由
- **gateway-service 统一入口落地**
  - 技术选型定为 Spring Cloud Gateway（响应式）：需 WebSocket 入口能力与限流/重试生态
  - 路由四前缀：auth / contests / problems / submissions（上游地址 `*_SERVICE_URL` 可覆盖），JWT 与 `X-Request-Id` 透传
  - `/api/v1/internal/**` 刻意不暴露（经网关 404），服务间调用内网直连
  - 真机验证：OpenResty:80 → 网关 → 四服务全链路、internal 隔离、未认证 401

## [0.2.0] - 2026-10-07

### 变更

- **AI 能力简化（按产品决定裁剪，并把两个新服务纳入版本库）**
  - **保留**：代码评审（`CODE_REVIEW`）、出题草稿（`PROBLEM_DRAFT`）
  - **删除**：风险辅助研判（`RISK_ASSESSMENT`）、语义查重（`SIMILARITY_SCAN`，本就无需模型）——连同其 Handler/Assistant/DTO/测试与 `AntiCheatInternalApi`
  - **去掉隐私守卫**（`AiPrivacyGuard`）：出境门禁与"公网地址必须带 api-key"的规则随之取消，`AiModelFactory` 只保留 base-url 必填的最小校验
  - **findings 不再回写 analytics**：删掉 ai-service 的 Outbox/发布器与 rocketmq 依赖，analytics 删掉 AI findings 读模型（新增 V3 drop 迁移），风险面板不再并入模型结论（`RiskStudentView.findings`、`RiskSummaryView.aiFindingCount` 移除）
  - 连带影响：AI 服务的三个审计动作（`AI_JOB_REQUESTED` / `AI_FINDING_CREATED` / `AI_JOB_FAILED`）不再走 Outbox 入审计账本，改为结构化日志
  - 顺带修复：`analytics-service` 的 `RankingOrder` 漏了 `@Component`，导致该服务**从未成功启动过**（`No qualifying bean of type RankingOrder`）
  - 真机验收（`scripts/ai-test.sh`，16 项 0 失败）：2 个保留端点入队成功；2 个删除端点不再可调用；`/jobs`、`/findings`、`/provider`（不含 embedding 字段）、`/drafts` 正常；考生访问 AI 端点 403；analytics 成绩榜与风险汇总正常
### 新增

- **RocketMQ 事件总线接通，评测链路端到端自动化**
  - submission-service：`OutboxPublisher` 定时扫描未发布事件行投递 RocketMQ（成功后标记，失败重试）；`JudgedConsumer` 消费 `lunar-submission-judged` 更新成绩读模型（终态保护幂等吞重复消息）
  - judge-service：`SubmissionCreatedConsumer` 消费 `lunar-submission-created` → JudgeEngine 执行 → 发布 `lunar-submission-judged`（verdict 归一化，ACCEPTED=100 分）
  - Mock 引擎升级为"提交即判对"，无 go-judge 亦可全链路演示；真机验证：提交后无需人工干预，状态自动流转至 ACCEPTED，积压消息在消费组上线后自动补消费
  - rocketmq-spring-boot-starter 2.3.0 接入 submission/judge；judge-service 新增 application.yml（rocketmq 配置）

### 修复

- 无（本切片为纯新增）

## [0.1.0] - 2026-10-06

开发期首个可用切片：认证、考试生命周期、学生管理、考生会话、公告、题库、提交与评测适配
全部为真实实现（落库 + JWT 验签 + 角色控制），81 个单元测试，全部端到端实测通过。

### 变更

- **AI 能力简化（按产品决定裁剪，并把两个新服务纳入版本库）**
  - **保留**：代码评审（`CODE_REVIEW`）、出题草稿（`PROBLEM_DRAFT`）
  - **删除**：风险辅助研判（`RISK_ASSESSMENT`）、语义查重（`SIMILARITY_SCAN`，本就无需模型）——连同其 Handler/Assistant/DTO/测试与 `AntiCheatInternalApi`
  - **去掉隐私守卫**（`AiPrivacyGuard`）：出境门禁与"公网地址必须带 api-key"的规则随之取消，`AiModelFactory` 只保留 base-url 必填的最小校验
  - **findings 不再回写 analytics**：删掉 ai-service 的 Outbox/发布器与 rocketmq 依赖，analytics 删掉 AI findings 读模型（新增 V3 drop 迁移），风险面板不再并入模型结论（`RiskStudentView.findings`、`RiskSummaryView.aiFindingCount` 移除）
  - 连带影响：AI 服务的三个审计动作（`AI_JOB_REQUESTED` / `AI_FINDING_CREATED` / `AI_JOB_FAILED`）不再走 Outbox 入审计账本，改为结构化日志
  - 顺带修复：`analytics-service` 的 `RankingOrder` 漏了 `@Component`，导致该服务**从未成功启动过**（`No qualifying bean of type RankingOrder`）
  - 真机验收（`scripts/ai-test.sh`，16 项 0 失败）：2 个保留端点入队成功；2 个删除端点不再可调用；`/jobs`、`/findings`、`/provider`（不含 embedding 字段）、`/drafts` 正常；考生访问 AI 端点 403；analytics 成绩榜与风险汇总正常
### 新增

- **auth-service（认证与用户）**
  - 真实登录：MyBatis-Plus 加载用户、BCrypt 校验、账号状态检查；失败限流（连续 5 次锁定 5 分钟）
  - RS256 JWT 签发（claims 契约见 `docs/api.md`）与 JWKS 公钥分发（`GET /api/v1/auth/jwks`，标准 RFC 7517 格式）
  - 服务间接口：学生账号创建/查询/密码重置（TEACHER/ADMIN 令牌转发，幂等靠唯一约束，审计落结构化日志）
  - 开发种子管理员（Flyway 占位符守卫，生产 `AUTH_DEV_SEED=false` 关闭）
- **contest-service（考试管理）**
  - 考试生命周期：创建/审核/发布/归档 + 状态时间窗口自动推进（PUBLISHED→RUNNING→FINISHED，条件更新防并发）
  - 学生名单：添加（账号不存在经 auth 创建、存在复用）、分页查询、重置密码、软删除移出、重新加入激活
  - 考生会话：进入/恢复（断线重连保留剩余时间）、心跳、心跳超时过期、管理端终止；名单/窗口/状态三重校验
  - 公告：发布（重要/需确认标记）、考生拉取即送达、确认幂等
- **problem-service（题库）**
  - 题目版本模型：创建即 v1 草稿，发布后不可变，改动建新版本（支持基于已有版本复制）
  - 判题限制（CPU/内存/输出）与语言白名单；**测试点与考生视角严格隔离**（公开 API 不返回测试点）
- **submission-service（提交）**
  - 提交链路：会话有效 + 竞赛进行中（服务间判定）+ 语言白名单校验；判题限制随题目快照
  - 幂等：`Idempotency-Key` 同键同指纹返回首次结果、异指纹 409
  - Outbox（与业务事实同事务）与判决回写（终态保护）
- **judge-service（评测）**
  - `GoJudgeAdapter`：go-judge REST 协议适配（编译/测试点执行/verdict 归一化），C/C++/Java/Python 语言模板
  - `GO_JUDGE_BASE_URL` 设置即启用真实引擎，未设置回落 MockJudgeEngine
- **公共与基础设施**
  - `lunar-common`：统一响应、`RequestIdFilter` 自动注册（响应头与请求体回显 X-Request-Id）、JudgeEngine 抽象
  - Docker Compose 四层编排：core（PostgreSQL/Redis/MinIO）、platform（Nacos/RocketMQ/Dashboard）、edge（OpenResty）、judge（go-judge）
  - OpenResty 边缘反向代理（健康检查、WS 升级头、请求追踪透传）；RocketMQ Dashboard（与 broker 共享网络命名空间）
  - 各服务 Swagger UI（Bearer JWT Authorize 方案声明）；`docs/api.http` 接口手测集

### 修复

- JWKS 端点改为标准顶层 `{"keys":[...]}` 格式（原 ApiResponse 包装导致资源服务无法解析）
- 多服务共库的 Flyway 版本冲突：确立"每服务独立 schema + 独立迁移历史表"约定
- go-judge 在 Docker Desktop 启动即 fatal：补 `cgroup: host`（WSL2 下 private cgroup 命名空间路径为空）
- MyBatis-Plus 3.5.9+ 分页拦截器需独立 `mybatis-plus-jsqlparser` 依赖（否则编译失败/分页 total 恒 0）
- 认证入口点 401 语义、`Instant` 截断秒级、服务间调用 Bearer 前缀重复与 ApiResponse 解包等交互缺陷

### 文档

- 部署实测结论（端口 5433 避让本机服务、启动顺序、冒烟验证）、错误码注册表扩充、接口手测集

### 已知限制

- go-judge 在 Docker Desktop 上嵌套 cgroup 内存限制不可靠（本地沙箱联调受限；生产 Linux 节点无此问题）
- 登录限流与幂等键为数据库/内存实现，Redis 接入后迁移；RocketMQ 未接（Outbox 已落库未发布）
- gateway / anti-cheat / media / audit 为占位模块；「题目属于竞赛」「提交次数上限」待关联表与阈值确认
