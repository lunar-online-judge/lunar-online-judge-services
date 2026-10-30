# 全链路演练记录

- 时间：2026-10-16
- 基线：`main` = `c19e203`
- 环境：8 个服务全部运行在修复后的 lunar-common 上；基础设施（PostgreSQL / Redis / RocketMQ / Nacos / OpenResty / go-judge）容器在跑
- 脚本：`scripts/full-stack-drill.sh`（按 `docs/deployment.md`「冒烟验证」逐条执行，末尾复用流程测试）
- 命令：`"D:\Program Files\Git\bin\bash.exe" scripts/full-stack-drill.sh`

## 结果

```
全链路演练：通过 16   失败 0
  其中第 5 步「完整考试场景」= scripts/exam-flow-test.sh：通过 24   失败 0
```

## 逐项证据

| 清单项 | 检查 | 结果 |
|---|---|---|
| 0 | 边缘代理自检 `GET /healthz`（OpenResty 直接返回） | 200 ✓ |
| 0.1 | 边缘 → gateway 回源 `GET /api/v1/auth/jwks` | 200 ✓ |
| 0.2 | 评测沙箱 `GET :5050/version` | `v1.13.0` ✓ |
| 0.3 | Nacos 注册服务数 | **8**（contest / problem / audit / auth / gateway / submission / judge / anti-cheat）✓ |
| 0.4 | 8 个服务 `/actuator/health` 全 UP；auth 非 health 端点 401、gateway 404 | ✓ |
| 1 | 真实登录拿到令牌；JWKS 含 `kid-current` | ✓ |
| 2 | 无令牌访问受保护端点（直连 8084 / 经边缘） | 401 ✓ |
| 3 | `X-Request-Id` 回显与缺失时自动生成 | ✓ |
| 4 | Swagger：auth 可访问 200；judge 未放行 401 | ✓ |
| 5 | 完整考试场景（建题→建考试→报名→会话→公告→随机分配→提交→**真实判题 ACCEPTED/100**） | 24/24 ✓ |

## 结论

1. **链路是自洽的**：边缘代理 → 网关 lb:// → 各服务 → 数据库/消息队列/评测沙箱，一路到真实判题拿满分，没有断点。
2. **安全基线在位**：无令牌 401、非 health 端点不暴露、judge 的 Swagger 不放行、`X-Request-Id` 全链路回显。
3. **一处自我更正**：演练脚本第一次跑时 0.3 报了三行 NOTE（"Nacos v1 已下线"），那是我脚本的判断写错了——Nacos v1 其实正常返回了 8 个服务，字段名是 `doms` 而不是 `name`。已修正为断言"注册服务数 = 8"，避免记录里留下错误信息。

## 三段验证汇总

| 阶段 | 脚本 | 结果 |
|---|---|---|
| 接口全量测试 | `scripts/api-full-test.sh` | **144 通过 / 0 失败** |
| 流程测试 | `scripts/exam-flow-test.sh` | **24 通过 / 0 失败** |
| 全链路演练 | `scripts/full-stack-drill.sh` | **16 通过 / 0 失败** |

三个脚本均已入库，可复跑。
