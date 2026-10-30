# 全量测试汇总（四层，全部通过）

- 时间：2026-10-16
- 代码基线：工作区内容 = `6748852` + **并发写入者的在制改动**（见文末"重要说明"）
- 服务：8 个核心服务全部用当前工作区代码重启（auth / gateway / submission 因有改动重启；其余 5 个代码未变）
- 基础设施：PostgreSQL / Redis / RocketMQ(broker+namesrv+dashboard) / Nacos / OpenResty / go-judge / MinIO 容器均在运行

## 结果

| 层 | 内容 | 命令 | 结果 |
|---|---|---|---|
| 1 | **单元/集成测试** | `mvn -B test` | **232 通过 / 0 失败** |
| 2 | **接口全量测试** | `bash scripts/api-full-test.sh` | **144 通过 / 0 失败** |
| 3 | **流程测试** | `bash scripts/exam-flow-test.sh` | **24 通过 / 0 失败** |
| 4 | **全链路演练** | `bash scripts/full-stack-drill.sh` | **16 通过 / 0 失败**（含第 3 层） |

### 第 1 层模块分布

```
lunar-common 27   auth 32   contest 72   problem 13
submission 17     judge 23  anti-cheat 11  audit 4
新模块 33（并发写入者新增的服务）
```

### 第 2-4 层覆盖

- **接口全量**：8 个服务 + 网关 + 边缘代理，含认证与限流、用户/学生账号、考试生命周期、名单与 CSV、会话与心跳、题目与版本、公告与确认、随机分配、提交与判题、审计、错误码契约、请求追踪
- **流程测试**：一条完整考试线（建题 → 建考试/审核/发布 → 锁版本 → 报名 → 进会话 → 公告送达/确认 → 随机分配 → 提交 → **go-judge 真实判题 ACCEPTED / 100 分** → 会话终止）
- **全链路演练**：`docs/deployment.md` 冒烟清单逐条（边缘 `/healthz`、边缘→网关回源、go-judge 沙箱版本、Nacos 注册 8 服务、8 个健康检查、非 health 端点不可用、真实登录 + JWKS、无令牌 401、`X-Request-Id` 回显与自动生成、Swagger 放行差异）+ 考试场景

## 重要说明（需要你确认）

本次测试时工作区里有**大量不是我做的在制改动**，其中包括：

- **两个全新服务**：`services/ai-service/`、`services/analytics-service/`，配套 `docker/compose.ai.yml`、`docker/nacos/config/ai-service.yml`、`docs/analytics/`
- **`BACKEND_IMPROVEMENT_PLAN.md` 被暂存删除**（`D`）
- 修改：根 `pom.xml`、`README.md`、多份 `docs/*`、`.gitignore`、auth/gateway 的 `application.yml`、`submission/SubmissionService.java`
- 新增：anti-cheat 的 `InternalMonitorController`、submission 的 `InternalSubmissionController` + 2 个 DTO

处理方式与影响：

1. 单元测试**编的就是当前工作区**，所以第 1 层（232 通过）覆盖了这些新代码——说明它们可编译、测试通过。
2. 第 2-4 层打的是**运行中的服务**；我重启了有改动的 auth/gateway/submission，其余 5 个服务代码未变，因此这四层的结果对当前工作区有效。
3. **新服务（ai / analytics）没有任何接口/流程覆盖**——端点总表与三个脚本都是围绕 8 个核心服务写的。它们的 33 个单测通过，但不等于可用性经过验证。
4. 我**只提交了本记录文件**，没有碰并发写入者的任何改动（尤其没有把 `BACKEND_IMPROVEMENT_PLAN.md` 的删除带进提交）。

**建议**：确认这些改动是否是你预期中的（特别是计划文档被删除这件事）；如果需要，我可以把三个脚本扩展到覆盖 ai / analytics 两个新服务，或等它们稳定后再做一次全量。
