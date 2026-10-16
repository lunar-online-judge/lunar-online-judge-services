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
| gateway-service | 8080 | 有 | Spring Cloud Gateway 路由（`lb://服务名` 经 Nacos 解析，可用 `*_SERVICE_URI` 覆盖）；`/api/v1/internal/**` 不暴露 |
| auth-service | 8081 | 有 | 依赖 PostgreSQL；`FLYWAY_ENABLED` 控制是否执行迁移 |
| contest-service | 8082 | 有 | |
| problem-service | 8083 | 有 | |
| submission-service | 8084 | 有 | |
| judge-service | 8080（Spring 默认；yml 未设 `server.port`） | 有 | 唯一启用 spring-boot-maven-plugin 的模块，打出可执行 jar；**尚未接入令牌验签**（无 `jwk-set-uri`，端点当前恒 401） |
| anti-cheat-service | 8086 | 有 | 风险事件/审核（rocketmq + 分页已配） |
| media-service | 8080（Spring 默认） | 无 | |
| audit-service | 8087 | 有 | 审计账本 |

**健康检查**：8 个服务均引入 Spring Boot Actuator，**只通过 HTTP 暴露 `health`**（`GET /actuator/health`，匿名可访问、只返回 `UP`/`DOWN`，不展示组件细节）。其余 actuator 端点不可用：有 Security 配置的服务上返回 401（安全过滤链先于路由拦截），网关上返回 404。

健康语义按依赖强度区分，不是"能连的都算"：

| 依赖 | 是否计入健康 | 理由 |
|---|---|---|
| PostgreSQL | **计入** | 硬依赖，库不可用则服务真的不可用（judge-service 无库，不涉及） |
| Nacos | **不计入** | `NACOS_FAIL_FAST=false` 的语义就是"Nacos 不可用时服务照常工作"；计入会让编排误杀可用实例 |
| Redis | **不计入** | 限流层是 fail-open 的（`docs/middleware.md`）；同理 |

网关自身无 Security 配置，其 `/actuator/health` 经 OpenResty 直接对外可达（仅状态、无细节）；其余服务的 health 只从服务端口直接可达，网关不路由 `/actuator/**`。

## 基础设施端口（Docker Compose）

各中间件的用途、功能与接入状态详见 `docs/middleware.md`。

| 组件 | 端口 | 所在文件 | 凭据 |
|---|---|---|---|
| PostgreSQL | 宿主机 5433（容器内 5432） | compose.core.yml | `lunar` / `change-me`，库 `lunar`；宿主 5432 被本机 postgres 服务占用，开发期映射 5433，服务启动需 `POSTGRES_PORT=5433` |
| Redis | 6379 | compose.core.yml | 无密码（仅本地） |
| MinIO | 9000（API）/ 9001（控制台） | compose.core.yml | `lunar` / `change-me-now` |
| Nacos | 8848（API/客户端）+ 9848（客户端 gRPC）+ 8849（控制台，容器内 8080） | compose.platform.yml | standalone 模式，`nacos/nacos-server:v3.0.3`；**9848 必须映射**——服务注册与订阅走 gRPC，只开 8848 会报 `Server check fail ... 9848`。控制台首次访问需初始化管理员密码（3.x 取消默认 nacos/nacos） |
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

所有服务的接口都要求认证。**除 gateway（无 Spring Security 依赖）与 media（无代码）外，7 个服务均已配置 `SecurityConfig`**：auth 放行 `POST /api/v1/auth/login` 与 `GET /api/v1/auth/jwks`（登录为真实实现：BCrypt 校验 + RS256 JWT + Redis 失败限流）；contest / problem / submission / anti-cheat / audit 作为 OAuth2 资源服务器经 auth 的 JWKS 本地验签，角色规则在方法级 `@PreAuthorize`；judge 仅放行 `/actuator/health`，**尚未接入验签**，其余端点当前恒 401。数据库迁移由 `FLYWAY_ENABLED=true` 开启，默认关闭。

**本地启动注意**（2026-10-05 实测）：

- `mvn spring-boot:run -pl <module>` 前必须先执行 `mvn -DskipTests install`，否则单模块解析不到 `lunar-common`（依赖解析失败）；
- auth-service 连 Docker postgres 需设置 `POSTGRES_PORT=5433`（见上方端口表）；
- judge-service 有 `application.yml` 但**未设 `server.port`**（默认 8080，与 gateway 冲突），且 `JudgeEngine` Bean 需要明确激活条件：本地以 `SPRING_PROFILES_ACTIVE=mock SERVER_PORT=8085 java -jar ...` 启动（或设 `GO_JUDGE_BASE_URL` 切真实引擎），否则会因找不到 `JudgeEngine` Bean 而启动失败。

**开发种子管理员**：`FLYWAY_ENABLED=true` 且 `AUTH_DEV_SEED=true`（默认）时，V3 迁移插入 `admin` / `admin123`（BCrypt 存储）；生产必须设置 `AUTH_DEV_SEED=false`。

## 冒烟验证

服务起来后按顺序确认（Git Bash / PowerShell 均可）：

```bash
# 0. 边缘代理自检（OpenResty 直接返回，不回源）
curl -s -o /dev/null -w "%{http_code}\n" http://localhost/healthz

# 0.1 边缘 → gateway 回源链路（经 lb:// 路由到各服务，非 502 即代表代理回源可达）
curl -s -o /dev/null -w "%{http_code}\n" http://localhost/api/v1/auth/jwks

# 0.2 评测沙箱可达（返回 go-judge 版本号；judge-service 设置 GO_JUDGE_BASE_URL 后即切换真实引擎）
curl -s http://localhost:5050/version

# 0.3 服务注册：应列出全部已启动的服务（instances 非空即注册成功）
curl -s "http://localhost:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20"
# 单个服务的实例与注册 IP（应为 127.0.0.1:端口，见下方 NACOS_DISCOVERY_IP）
curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=auth-service"
# 控制台：http://localhost:8848/nacos → 服务管理 → 服务列表

# 0.4 健康检查：8 个服务都应返回 {"status":"UP"}（actuator 只暴露 health，匿名可访问）
for p in 8080 8081 8082 8083 8084 8085 8086 8087; do printf "%s " "$p"; curl -s -m 5 "http://localhost:$p/actuator/health"; echo; done
# 其余 actuator 端点不可用：有 Security 的服务上返回 401（过滤链先于路由拦截），网关上返回 404
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/actuator/env   # 401
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/env   # 404

# 1. 真实登录（开发种子管理员 admin/admin123；需 FLYWAY_ENABLED=true + POSTGRES_PORT=5433 启动过一次使迁移生效）
curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# 连续失败 5 次后返回 429 AUTH_RATE_LIMITED；JWKS: GET http://localhost:8081/api/v1/auth/jwks

# 2. 无令牌访问受保护端点 → 401（资源服务器基线，属预期）
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8084/api/v1/submissions

# 3. 请求追踪：响应头应回显 X-Request-Id（缺失时由 RequestIdFilter 自动生成）
curl -s -i -H "X-Request-Id: test-123" http://localhost:8081/api/v1/auth/jwks | grep -i x-request-id

# 4. Swagger（除 judge 外的 6 个服务已放行 /swagger-ui/**；judge 未放行，访问返回 401）
# 浏览器打开 http://localhost:8081/swagger-ui.html（端口换成被调试的服务）
```

## 环境变量

| 配置项 | 说明 | 默认值 |
|---|---|---|
| `SERVER_PORT` | 服务 HTTP 端口 | 见上方端口表 |
| `SPRING_PROFILES_ACTIVE` | 环境 profile；judge-service 需显式设为 `mock` 才会装配 `MockJudgeEngine`（未设置且无 `GO_JUDGE_BASE_URL` 时容器内没有 `JudgeEngine` Bean） | 未设置 |
| `FLYWAY_ENABLED` | 是否执行数据库迁移 | `false` |
| `GO_JUDGE_BASE_URL` | go-judge 内网地址；**设置后 judge-service 自动启用 GoJudgeAdapter（优先于 mock profile）** | 未设置（此时需 `SPRING_PROFILES_ACTIVE=mock`，否则容器内没有 `JudgeEngine` Bean） |
| `GO_JUDGE_CONNECT_TIMEOUT_MS` | go-judge 连接超时 | `2000` |
| `GO_JUDGE_READ_TIMEOUT_MS` | go-judge 读取超时 | `10000` |
| `POSTGRES_HOST` | PostgreSQL 地址 | `localhost` |
| `POSTGRES_PORT` | PostgreSQL 端口（本地 Compose 映射在 5433） | `5432`，**anti-cheat / audit 的默认值却是 `5433`**（两处不一致，见"已知限制"） |
| `POSTGRES_DB` | 数据库名 | `lunar` |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | 数据库凭据 | `lunar` / `change-me` |
| `AUTH_JWT_PRIVATE_KEY` | RS256 私钥（PKCS#8 PEM）；留空则启动期临时生成开发密钥，重启令牌失效 | 未设置（临时生成） |
| `AUTH_JWT_KEY_ID` | JWKS 的 kid | `lunar-dev-1` |
| `AUTH_JWT_TTL_MINUTES` | access token 有效期（分钟） | `30` |
| `AUTH_LOGIN_MAX_FAILURES` | 登录失败锁定阈值 | `5` |
| `AUTH_LOGIN_LOCK_MINUTES` | 登录锁定时长（分钟） | `5` |
| `AUTH_DEV_SEED` | 开发种子管理员（V3 迁移），生产必须为 `false` | `true` |
| `REDIS_HOST` | Redis 地址 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_TIMEOUT_MS` | Redis 命令超时（毫秒）；设短是为了 Redis 异常时快速失败并按 fail-open 放行 | `1000` |
| `REDIS_CONNECT_TIMEOUT_MS` | Redis 连接超时（毫秒） | `500` |
| `NACOS_SERVER_ADDR` | Nacos 服务端地址（`host:port`，端口为主端口 8848） | `localhost:8848` |
| `NACOS_DISCOVERY_IP` | 注册到 Nacos 的实例 IP；**本机多网卡（VMware VMnet / WSL）会被自动探测误选，故固定为 `127.0.0.1`**；服务容器化后须改为容器可达地址 | `127.0.0.1` |
| `NACOS_FAIL_FAST` | Nacos 不可达时是否让服务启动失败；`false` 表示照常启动（仅不注册） | `false` |
| `NACOS_CONFIG_NAMESPACE` | 配置中心命名空间；默认空 = `public`（与服务发现一致，3.x 下 public 正常可用） | 空（public） |
| `ROCKETMQ_NAMESRV_ADDR` | RocketMQ NameServer | `localhost:9876` |
| `MINIO_ENDPOINT` | MinIO 地址 | `http://localhost:9000` |
| `MEDIA_SIGNALING_URL` | WebRTC 信令服务地址 | 未设置 |

数据库连接变量当前由 6 个有库的服务消费：auth、contest、problem、submission、anti-cheat、audit（judge 与 media 无 DataSource）。

**多服务共库的 Flyway 约定**：每个服务使用独立的 schema 与独立的迁移历史表（`spring.flyway.schemas` + `spring.flyway.table`），避免版本号/checksum 冲突——auth-service 沿用默认 `public.flyway_schema_history`；contest / problem / submission / anti-cheat / audit 分别用 `contest_schema`+`contest_history`、`problem_schema`+`problem_history`、`submission_schema`+`submission_history`、`anti_cheat_schema`+`anti_cheat_history`、`audit_schema`+`audit_history`。**资源服务验签**：`AUTH_JWK_SET_URI` 指向 auth 的 JWKS（默认 `http://localhost:8081/api/v1/auth/jwks`），本地启动顺序上 auth 需先于其他资源服务可用。

**服务发现（Nacos）**：8 个服务启动时注册到 Nacos 的 `DEFAULT_GROUP`，网关路由用 `lb://<服务名>`、服务间客户端默认按服务名寻址（均可用 `*_SERVICE_URI` / `*_BASE_URL` 覆盖为固定地址排障）。因此**启动顺序上 auth 需先完成注册**，其他服务的 `lb://auth-service` 才能解析到实例；Nacos 本身未启动时服务仍可启动（`NACOS_FAIL_FAST=false`），只是发现链路不可用。JWKS 验签链路**不**走服务发现（`AUTH_JWK_SET_URI` 仍是固定地址，见 `docs/middleware.md`"Nacos"节）。

**配置中心（Nacos）**：托管"运行时可变"的服务私有配置（dataId = `<服务名>.yml`，`public` + `DEFAULT_GROUP`），导入方式为 `spring.config.import=optional:nacos:…`——Nacos 不可达时用代码默认值启动。改值后 **auth 的登录限流阈值、contest 的心跳超时与 submission 的提交频率阈值无需重启即生效**，其余托管项（调度间隔）需重启；配置内容的 git 真源是 `docker/nacos/config/*.yml`，用 `bash docker/nacos/publish-configs.sh` 推送。具体清单、热更新边界与踩过的坑见 `docs/middleware.md`"配置中心"节。

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
- judge、media 两个服务没有完整端口配置：judge 有 `application.yml` 但未设 `server.port`（需 `SERVER_PORT`），media 无 `application.yml`；两者默认都是 8080、与 gateway 相同，本地同时启动必须显式区分；
- **judge-service 的 HTTP 端点当前不可用**：`SecurityConfig` 要求 `authenticated()`，但既未配置 `oauth2ResourceServer`、`application.yml` 也没有 `jwk-set-uri`，任何请求都是 401；判题实际走 RocketMQ 消费链路，`POST /api/v1/judge/submissions` 需先补齐验签配置才能调用；
- **`POSTGRES_PORT` 默认值不一致**：auth / contest / problem / submission 默认 `5432`，anti-cheat / audit 默认 `5433`；本地 Compose 映射在 5433，因此前 4 个服务启动时必须显式设 `POSTGRES_PORT=5433`；
- gateway 经 OpenResty 暴露后，`/api/v1/internal/**` 无路由不可达——服务间调用必须内网直连，不得绕行网关（按服务名寻址同样由调用方直连目标实例，不经过网关）；
- 服务实例注册为临时实例（ephemeral，靠 gRPC 心跳续约）：进程正常退出或被强杀时实例会立即从注册表摘除，网络分区等 gRPC 连接不可判定的场景才依赖心跳超时；服务已提供 `/actuator/health`，但**负载均衡仍只按注册表轮询、不做主动探活**（默认 LoadBalancer 不查健康端点），Compose 也只为基础设施容器配置探测；
- **开发期重启 auth-service 会让令牌在约 5 分钟内全部失效**（含重启后新签发的）：未注入 `AUTH_JWT_PRIVATE_KEY` 时启动会临时生成签名密钥，而 kid 固定为 `lunar-dev-1`，资源服务的 JWKS 缓存按 kid 命中旧公钥、不会触发重新拉取，于是新旧令牌都验签失败。开发期建议注入固定私钥（`AUTH_JWT_PRIVATE_KEY`）或重启 auth 后一并重启调用方；
- 登录失败限流已迁 Redis（auth-service）：计数多实例共享、重启不清零、TTL 自动清理；Redis 不可用时 fail-open 放行登录（仅打 warn），因此本地开发不启动 Redis 也能登录，只是限流失效；
- go-judge 在 Docker Desktop（WSL2）上嵌套 cgroup 的**内存限制**不可靠（生产 Linux 节点无此问题）；但**编译与运行本身可用**：2026-10 实测（本仓库 Dockerfile 构建的镜像 + 适配器为编译命令显式设置 `procLimit`）C++ 的编译/运行/五类 verdict 全部正常；
- **评测镜像里的工具链必须在 go-judge 启动前就装好**：官方 `mount.yaml` 的绑定源若在启动时不存在会被**静默跳过**（实测：在运行中的容器里补装 JDK 后 Java 仍报 `Error loading java.security file`——沙箱内 `/etc` 只有 `alternatives` 与 `ld.so.cache`；重启容器即恢复）。改完 `docker/go-judge/Dockerfile` 后按 `docker compose -f docker/compose.judge.yml up -d --build` 重建并重启；
- **评测镜像必须自带工具链**：`criyle/go-judge` 官方镜像内没有 gcc/g++/python3/javac，直接用会让所有真实评测在编译阶段失败（`execve: g++: executable file not found in $PATH`）；`docker/compose.judge.yml` 已改为构建 `docker/go-judge/Dockerfile`，首次 `up` 会多花几分钟构建镜像；
