# 全量 API 测试记录（第一轮）

- 时间：2026-10-16
- 基线：`main` = `dee8112`（三项功能已落地：随机题目分配 / judge 判决 Outbox / BE-09）
- 环境：8 个服务全部用新 lunar-common 重启（8/8 健康）；Git Bash 运行 `logs/api-full-test.sh`
- 命令：`"D:\Program Files\Git\bin\bash.exe" logs/api-full-test.sh`

## 结果

```
通过 51   失败 9（提前终止）
```

提前终止原因：**「添加学生（auth 建号 + 报名）」返回 500**，后续用例依赖学生 id，无法继续。
因此端点总表后半部分（提交/判题/公告/分配/审计等）本轮**未被覆盖**。

## 失败清单（全部为真实问题，非脚本噪音）

| # | 用例 | 期望 | 实际 | 分析 |
|---|---|---|---|---|
| 1 | 错误口令登录 | 401 + `AUTH_INVALID_CREDENTIALS` | 401 + **空响应体** | 响应头含 `WWW-Authenticate: Bearer`、`Content-Length: 0` → 是 **Spring Security 的默认入口点**，不是本仓库的 `ApiResponse` 错误体。说明 `AuthenticationManager.authenticate` 抛出的 `BadCredentialsException` 没有被控制器的异常处理接住，而是在安全链里被渲染成 401 |
| 2 | 与密码错误同码 | `AUTH_INVALID_CREDENTIALS` | 空 | 同上 |
| 3 | 空参数登录 | 400 + `VALIDATION_FAILED` | **401 + 空** | 同上：请求在校验前就被安全链拒绝 |
| 4 | 连续失败 5 次 | 429 + `AUTH_RATE_LIMITED` | **401** | 限流**从未触发**——失败路径没走到计数逻辑，与 1-3 同源 |
| 5 | 内部接口查不存在学生 | 404 | **500** | 需要看 auth 日志定位（本轮未取到栈） |
| 6 | **添加学生（auth 建号 + 报名）** | 200 | **500** | **最高优先级**：它阻断了整个考试流程（报名/名单/会话/提交都依赖学生 id） |

## 结论与下一步

- 本轮价值：把"接口是否真的按文档行为工作"从假设变成了证据——**51 个用例通过，但认证错误语义与限流这两块与文档不符**，且**报名链路直接 500**。
- 下一轮顺序：
  1. 先修 **添加学生 500**（取 auth 栈定位；这是 P0，阻断后续所有流程用例）
  2. 再修**认证错误语义**：让错误口令/空参数走仓库统一的 `ApiResponse` 错误体（`AUTH_INVALID_CREDENTIALS` / `VALIDATION_FAILED`），并确认限流在失败路径上真的计数（429 + `AUTH_RATE_LIMITED`）
  3. 重跑全量 API 测试直到覆盖端点总表全部 49 个端点，再进入流程测试与全链路演练
