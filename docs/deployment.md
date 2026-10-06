# 部署与配置

## 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | 根 POM `java.version`；本机构建时 `JAVA_HOME` 必须指向 JDK 21 |
| Maven | 3.9+ | 构建验证命令 `mvn -DskipTests package` |
| Docker Compose | v2 | `docker compose` 子命令形式 |

## 服务与端口

服务端口统一通过 `SERVER_PORT` 环境变量覆盖：

| 服务 | 默认端口 | 配置文件 | 说明 |
|---|---|---|---|
| gateway-service | 8080（Spring 默认） | 无 `application.yml` | 启动时必须设置 `SERVER_PORT`，否则与其他无配置服务冲突 |
| auth-service | 8081 | 有 | 依赖 PostgreSQL；`FLYWAY_ENABLED` 控制是否执行迁移 |
| contest-service | 8082 | 有 | |
| problem-service | 8083 | 有 | |
| submission-service | 8084 | 有 | |
| judge-service | 8080（Spring 默认） | 无 | 唯一启用 spring-boot-maven-plugin 的模块，打出可执行 jar |
| anti-cheat-service | 8080（Spring 默认） | 无 | |
| media-service | 8080（Spring 默认） | 无 | |
| audit-service | 8080（Spring 默认） | 无 | |

## 基础设施端口（Docker Compose）

各中间件的用途、功能与接入状态详见 `docs/middleware.md`。

| 组件 | 端口 | 所在文件 | 凭据 |
|---|---|---|---|
| PostgreSQL | 宿主机 5433（容器内 5432） | compose.core.yml | `lunar` / `change-me`，库 `lunar`；宿主 5432 被本机 postgres 服务占用，开发期映射 5433，服务启动需 `POSTGRES_PORT=5433` |
| Redis | 6379 | compose.core.yml | 无密码（仅本地） |
| MinIO | 9000（API）/ 9001（控制台） | compose.core.yml | `lunar` / `change-me-now` |
| Nacos | 8848 / 9848 | compose.platform.yml | standalone 模式 |
| RocketMQ NameServer | 9876 | compose.platform.yml | — |
| RocketMQ Broker | 10909 / 10911 / 10912 | compose.platform.yml | 配置见 `docker/rocketmq/broker.conf` |
| RocketMQ Dashboard | 8090 | compose.platform.yml | 开发专用管理面板；与 broker 共享网络命名空间（无登录），broker 重启后需一起重启 |
| OpenResty（反向代理） | 80 | compose.edge.yml | 回源宿主机 gateway-service :8080 |
| go-judge（评测沙箱） | 5050 | compose.judge.yml | `privileged` + `shm 256m`（官方推荐本地方式），独立网络 |

## 反向代理（OpenResty）

OpenResty 是平台的唯一对外入口（边缘层），Compose 中由 `docker/compose.edge.yml` 提供，配置为 `docker/openresty/nginx.conf`：

- 监听 80 端口，所有请求转发给 gateway-service（upstream `host.docker.internal:8080`，gateway 目前跑在宿主机）；服务容器化后把 upstream 改成容器名/服务名并加入同一网络；
- `GET /healthz` 由 OpenResty 直接返回 200，不回源，用于边缘自检；
- 透传客户端带来的 `X-Request-Id`，缺失时由边缘生成，配合 gateway 的请求追踪；
- 设置 WebSocket 升级头（`Upgrade` / `Connection`）与 `X-Forwarded-*`，读写超时 300s，保证长连接不被边缘提前断开；
- `client_max_body_size 10m`，覆盖代码提交请求体上限，按需调整；
- Windows 本机 80 端口若被 IIS/http.sys 占用，改 Compose 映射端口并同步本表。

## 本地启动

```powershell
# 1. 基础设施
docker compose -f docker/compose.core.yml up -d
docker compose -f docker/compose.platform.yml up -d
# 1.1 边缘反向代理（回源宿主机 gateway-service :8080，启动先后不限）
docker compose -f docker/compose.edge.yml up -d
# 1.2 评测沙箱节点（与业务服务独立网络；judge-service 经 localhost:5050 访问）
docker compose -f docker/compose.judge.yml up -d

# 2. 构建
mvn -DskipTests package

# 3. 启动单个服务（示例：auth-service）
mvn spring-boot:run -pl services/auth-service
# 或运行 judge-service 的可执行 jar
java -jar services/judge-service/target/judge-service-0.1.0-SNAPSHOT.jar
```

所有服务的接口都要求认证。**auth-service 已有 SecurityConfig 基线**（放行 `POST /api/v1/auth/login` 与 `GET /api/v1/auth/jwks`，登录为真实实现：BCrypt 校验 + RS256 JWT + 失败限流），其余服务仍是 Spring Security 默认行为（401）。auth-service 的数据库迁移由 `FLYWAY_ENABLED=true` 开启，默认关闭。

**本地启动注意**（2026-10-05 实测）：

- `mvn spring-boot:run -pl <module>` 前必须先执行 `mvn -DskipTests install`，否则单模块解析不到 `lunar-common`/`lunar-contracts`（依赖解析失败）；
- auth-service 连 Docker postgres 需设置 `POSTGRES_PORT=5433`（见上方端口表）；
- judge-service 没有 `application.yml`，默认 profile 不生效，本地必须以 `SPRING_PROFILES_ACTIVE=mock SERVER_PORT=8085 java -jar ...` 启动，否则容器因找不到 `JudgeEngine` Bean 而启动失败。

**开发种子管理员**：`FLYWAY_ENABLED=true` 且 `AUTH_DEV_SEED=true`（默认）时，V3 迁移插入 `admin` / `admin123`（BCrypt 存储）；生产必须设置 `AUTH_DEV_SEED=false`。

## 冒烟验证

服务起来后按顺序确认（Git Bash / PowerShell 均可）：

```bash
# 0. 边缘代理自检（OpenResty 直接返回，不回源）
curl -s -o /dev/null -w "%{http_code}\n" http://localhost/healthz

# 0.1 边缘 → gateway 回源链路（gateway 尚未实现路由，非 502 即代表代理回源可达）
curl -s -o /dev/null -w "%{http_code}\n" http://localhost/

# 0.2 评测沙箱可达（返回 go-judge 版本号；judge-service 设置 GO_JUDGE_BASE_URL 后即切换真实引擎）
curl -s http://localhost:5050/version

# 1. 真实登录（开发种子管理员 admin/admin123；需 FLYWAY_ENABLED=true + POSTGRES_PORT=5433 启动过一次使迁移生效）
curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# 连续失败 5 次后返回 429 AUTH_RATE_LIMITED；JWKS: GET http://localhost:8081/api/v1/auth/jwks

# 2. 无令牌访问受保护端点 → 401（Security 默认行为，属预期）
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8084/api/v1/submissions

# 3. 请求追踪：响应头应回显 X-Request-Id（缺失时由 RequestIdFilter 自动生成）
curl -s -i -H "X-Request-Id: test-123" http://localhost:8081/api/v1/auth/jwks | grep -i x-request-id

# 4. Swagger（judge-service 默认 8080 端口；与其他服务同起时先 SERVER_PORT 区分）
# 浏览器打开 http://localhost:8080/swagger-ui.html
```

## 环境变量

| 配置项 | 说明 | 默认值 |
|---|---|---|
| `SERVER_PORT` | 服务 HTTP 端口 | 见上方端口表 |
| `SPRING_PROFILES_ACTIVE` | 环境 profile | `mock` |
| `FLYWAY_ENABLED` | 是否执行数据库迁移 | `false` |
| `GO_JUDGE_BASE_URL` | go-judge 内网地址；**设置后 judge-service 自动启用 GoJudgeAdapter（优先于 mock profile），未设置回落 Mock 引擎** | 未设置（Mock 引擎） |
| `GO_JUDGE_CONNECT_TIMEOUT_MS` | go-judge 连接超时 | `2000` |
| `GO_JUDGE_READ_TIMEOUT_MS` | go-judge 读取超时 | `10000` |
| `POSTGRES_HOST` | PostgreSQL 地址 | `localhost` |
| `POSTGRES_PORT` | PostgreSQL 端口（本地 Compose 映射在 5433） | `5432` |
| `POSTGRES_DB` | 数据库名 | `lunar` |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | 数据库凭据 | `lunar` / `change-me` |
| `AUTH_JWT_PRIVATE_KEY` | RS256 私钥（PKCS#8 PEM）；留空则启动期临时生成开发密钥，重启令牌失效 | 未设置（临时生成） |
| `AUTH_JWT_KEY_ID` | JWKS 的 kid | `lunar-dev-1` |
| `AUTH_JWT_TTL_MINUTES` | access token 有效期（分钟） | `30` |
| `AUTH_LOGIN_MAX_FAILURES` | 登录失败锁定阈值 | `5` |
| `AUTH_LOGIN_LOCK_MINUTES` | 登录锁定时长（分钟） | `5` |
| `AUTH_DEV_SEED` | 开发种子管理员（V3 迁移），生产必须为 `false` | `true` |
| `REDIS_HOST` | Redis 地址 | `localhost` |
| `ROCKETMQ_NAMESRV_ADDR` | RocketMQ NameServer | `localhost:9876` |
| `MINIO_ENDPOINT` | MinIO 地址 | `http://localhost:9000` |
| `MEDIA_SIGNALING_URL` | WebRTC 信令服务地址 | 未设置 |

数据库连接变量当前由 auth-service、contest-service 的 `application.yml` 消费，其余服务接入持久化时按同样方式引用。

**多服务共库的 Flyway 约定**：每个服务使用独立的 schema 与独立的迁移历史表（`spring.flyway.schemas` + `spring.flyway.table`），避免版本号/checksum 冲突——auth-service 沿用默认 `public.flyway_schema_history`；contest-service 用 `contest_schema` + `contest_history`；后续服务照此办理。**资源服务验签**：`AUTH_JWK_SET_URI` 指向 auth 的 JWKS（默认 `http://localhost:8081/api/v1/auth/jwks`），本地启动顺序上 auth 需先于其他资源服务可用。

## 生产要求

- 业务服务和 go-judge 必须分节点或至少分隔离运行时；
- go-judge 评测节点使用 Linux，禁止访问数据库、消息队列和宿主机敏感目录；
- 评测网络默认关闭，只允许必要的镜像、日志和健康检查网络；
- 对容器 capabilities、seccomp、cgroup、namespace、临时目录和输出大小做安全审计；
- WebRTC 使用独立 SFU/媒体服务，Spring 服务只做鉴权、信令和审计；
- MinIO 只保存截图，使用短期签名 URL；连续视频不保存；
- 所有密钥从环境变量或密钥管理系统注入，不提交到仓库；
- Compose 中的 `change-me` 类默认凭据只允许出现在本地环境。

完整安全边界（网络隔离、凭据、审计、密码处理）见 `docs/architecture.md` 第 14 节。

## 已知限制

- RocketMQ broker 广播地址固定为 `127.0.0.1`（Spring 服务跑在宿主机的前提）；服务容器化后需注释掉 `broker.conf` 中的 `brokerIP1`；开发期 `autoCreateTopicEnable=true`，生产必须改为 `false` 并显式建 Topic；
- gateway、judge、anti-cheat、media、audit 五个服务没有 `application.yml`，同时本地启动多个时会全部抢占 8080；
- 各服务当前都没有健康检查端点（未引入 actuator），Compose 也没有配置健康探测；
- 登录失败限流目前是 auth-service 的内存实现（重启清零、多实例不共享），Redis Starter 接入后迁移；
- go-judge 在 Docker Desktop（WSL2）上嵌套 cgroup 受限，本地沙箱联调暂缓（配置保留在 compose.judge.yml），生产 Linux 节点不受影响。
