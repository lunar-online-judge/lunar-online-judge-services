# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 结构；版本号语义化。
当前阶段为开发期，服务端能力按实现顺序逐步落地（见 `docs/development-guide.md`）。

## [Unreleased]

### 计划中

- RocketMQ Outbox 发布器与消费（接通 submission → judge 事件链路，替代判决回写过渡端点）
- gateway-service 路由选型与实现；anti-cheat / media / audit 服务落地
- WebSocket 推送（考试状态、评测结果、风险告警）
- Redis 接入（登录限流迁移、令牌吊销、幂等键、会话心跳）
- 题目归属竞赛校验（依赖随机分配表）与提交频率阈值（待 exam-process §10 确认）

## [0.5.0] - 2026-10-08

### 新增

- **audit-service 审计账本**
  - 追加式账本 `audit_schema.audit_logs`（仅插入，无更新/删除接口；不记录密码等敏感明文）
  - 服务间写入端点（转发操作者令牌，自动记录 actor/role，系统动作标 SYSTEM）+ 管理端分页查询（动作/操作者/竞赛过滤）
  - 接入样本：anti-cheat 审核动作经 AuditClient 写账本（尽力而为，不阻断主流程）
  - 服务端口 8087；gateway 新增 audit 路由（internal 写入经网关不可达）

## [0.4.0] - 2026-10-07

### 新增

- **admin-web 管理后台前端（新项目）**
  - 技术栈：React 19 + React Router 7 + HeroUI 2.7 + Zustand 5 + TanStack Query 5 / Table 8 + Tailwind CSS 3.4（Vite 6 + TS 5.8）
  - 功能：JWT 登录与角色守卫、概览、考试管理（生命周期流转+学生名单+公告）、题目管理（创建/版本/测试点）、风险事件（轮询+人工审核）
  - 后端配套列表接口：考试分页（状态过滤）、题目分页（keyword）、监考查询 contestId 可选

## [0.3.0] - 2026-10-07

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
