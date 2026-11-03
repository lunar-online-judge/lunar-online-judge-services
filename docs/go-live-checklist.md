# 上线与本地启动清单

> 这份文档记录**实际验证过的启动参数、验证命令与踩过的坑**。
> 2026-11 全链路回归：单元测试 216 项、`api-full-test` 137 项、`exam-flow-test` 24 项、`full-stack-drill` 16 项，全部通过。

## 1. 形态选择：什么进容器，什么不进

| 组件 | 是否进容器 | 原因 |
|---|---|---|
| PostgreSQL / Redis / Nacos / RocketMQ / OpenResty | ✅ 进 | 有状态、要隔离、版本要锁死 |
| go-judge 评测沙箱 | ✅ 单独一份 compose | 需要 `privileged` + `cgroup: host`，生命周期与基础设施无关 |
| 业务服务（auth/contest/problem/submission/judge/anti-cheat/analytics/gateway） | ❌ 不进 | 单机部署下容器化只增加构建与调试成本；用 jar + 进程管理即可 |

服务总数 **8 个**：gateway + 7 个业务服务。

## 2. 端口分配（务必按这张表）

| 服务 | 端口 | 说明 |
|---|---|---|
| gateway-service | 8080 | 唯一业务入口 |
| auth-service | 8081 | |
| contest-service | 8082 | |
| problem-service | 8083 | |
| submission-service | 8084 | |
| judge-service | 8085 | 无 HTTP 端点，只消费 MQ |
| anti-cheat-service | 8086 | |
| analytics-service | **8098** | 历史上是 8088，因与其他项目冲突改为 8098 |
| 边缘 OpenResty | 80 | |
| PostgreSQL | **5433**（宿主）→ 5432（容器） | 宿主 5432 常被本机 postgres 占用 |
| Redis | **6380**（宿主）→ 6379（容器） | 见下面「坑 1」 |
| Nacos | 8848（客户端 API）/ 8849（控制台） | |
| go-judge | 5050 | |

## 3. 启动步骤

### 3.1 基础设施

```bash
# 复制并修改密钥（生产必须全部替换）
cp docker/.env.example docker/.env

# 基础设施（postgres / redis / nacos / namesrv / broker / openresty）
docker compose -f docker/compose.yml up -d

# 评测沙箱（独立 compose）
docker compose -f docker/compose.judge.yml up -d
```

### 3.2 业务服务（宿主机）

必须同时设置两个端口变量，否则会连错实例（见「坑 1」「坑 2」）：

```powershell
$env:POSTGRES_PORT = '5433'
$env:REDIS_PORT    = '6380'
$env:REDIS_PASSWORD = 'change-me-redis'   # 与 docker/.env 保持一致
$env:JAVA_HOME     = '<jdk-21>'

mvn -pl services/auth-service spring-boot:run        # 另起窗口，其余同理
```

启动顺序建议：`auth` → `contest` → `problem` → `submission` → `anti-cheat` → `analytics` → `judge` → `gateway`。
原因是下游服务启动时要拉 JWKS；**改过 auth 的密钥或重启过 auth 之后，必须重启依赖它的服务**，否则会一直用旧公钥验签（表现为 401）。

### 3.3 长期运行（不要用 `spring-boot:run`）

```bash
mvn -DskipTests package
java -jar services/<name>/target/<name>-*.jar
```
用 systemd / nssm 托管，配 `Restart=always`。

## 4. 验证（按顺序执行，全绿才算通）

```bash
bash scripts/api-full-test.sh      # 137 项：接口契约、权限、限流、版本流程
bash scripts/exam-flow-test.sh     #  24 项：完整考试场景（含真实判题）
bash scripts/full-stack-drill.sh   #  16 项：边缘 → 网关 → 服务 → MQ → 读模型回路
mvn test                          # 216 项：单元与集成
```

回路是否真的闭合，可以再查一次数据库（比日志可靠）：

```sql
-- Outbox 不应有积压，也不应有死信
select count(*) filter (where published_at is null) as pending,
       count(*)                                     as total
from submission_schema.outbox_events;

select count(*) from submission_schema.outbox_events where attempts >= 10;  -- 应为 0

-- 消费者确实处理过事件（幂等表有记录）
select consumer, count(*) from analytics_schema.consumed_events group by 1;

-- 读模型有最新判题结果
select verdict, score, judged_at
from analytics_schema.submission_facts
order by judged_at desc nulls last limit 3;
```

## 5. 上线前必须完成（代码之外）

1. **替换全部 `change-me*`**：`POSTGRES_PASSWORD`、`REDIS_PASSWORD`、`NACOS_AUTH_TOKEN`、`SERVICE_CLIENT_SECRET_*`。
   `prod` profile 下 `ProductionConfigGuard` 会主动拒绝以开发默认值启动。
2. **生成真实 RS256 密钥**：`AUTH_JWT_PRIVATE_KEY`（PKCS#8 Base64）+ `AUTH_JWT_KEY_ID`，不要用开发密钥。
3. **TLS 证书**：边缘目前只有 HTTP 80，生产必须在 OpenResty 上加 443 与证书。
4. **启用 Nacos 鉴权**：`NACOS_AUTH_ENABLE=true` 并给全部服务配凭据。默认关闭时，任何能连 8848 的人都能改配置与服务地址。

## 6. 踩过的坑（都是真实发生过的）

**坑 1：Redis 端口冲突会导致"静默连错库"**
本机若有别的项目占着 6379，我们的 Redis 会映射到 6380。此时业务服务若不设 `REDIS_PORT=6380`，**会连上别人的 Redis 且不报错**——登录限流和判题去重写进别人的库，健康检查照样 UP（两条路径都是 fail-open）。判断方法：看 Redis 里有没有 `auth:login:lock:*` 键。

**坑 2：`POSTGRES_PORT` 默认值不一致**
服务默认连 5432，而容器映射在 5433。用 `spring-boot:run` 起服务时一定显式设置。

**坑 3：Nacos 探针不能用控制台端口**
Nacos 3.0.3 的控制台 8080 对所有路径都返 500，用它做 healthcheck 会永远 unhealthy。探针要查**客户端 API 端口 8848**（根路径返 404 即算存活）。业务服务只用 8848，控制台坏不影响使用。

**坑 4：PowerShell 会把 `localhost` 解析成 IPv6**
go-judge 监听 `[::]:5050`，但容器映射只通 IPv4。用 `Invoke-WebRequest http://localhost:5050` 会超时，改用 `http://127.0.0.1:5050`。诊断这类"服务明明起了却连不上"时，先换 IPv4 试。

**坑 5：改了配置必须重启**
改了 `application.yml` 或 Nacos 配置后必须重启对应服务。曾出现"配置明明改了却不生效"，原因是只重启了其中一个服务，另一个还在跑旧配置。

**坑 6：`lunar-common` 改完要重新 install**
`mvn -pl services/xxx` 不会带上 `lunar-common`。改了公共模块后必须：

```bash
mvn -N install -DskipTests
mvn install -DskipTests -pl lunar-common
```
否则服务跑的是旧 jar，表现为"代码改了没效果"。

**坑 7：孤儿容器**
从 compose 里删掉的服务（如 MinIO），容器不会自动消失。用 `docker compose up -d --remove-orphans` 清理。
