# Lunar Online Judge Backend

面向 Windows 10/11 竞赛客户端的在线评测后端

## 当前技术栈与版本

以下版本以根目录 `pom.xml` 和各模块 POM 为准：

| 分类 | 依赖/组件 | 当前版本 | 当前状态 |
|---|---|---:|---|
| 运行时 | Java | 21 | 根 POM 的编译目标 |
| 应用框架 | Spring Boot | 3.5.16 | 根 POM parent，实际生效 |
| 微服务基础 | Spring Cloud BOM | 2025.0.3 | gateway-service 已实际引入 spring-cloud-starter-gateway（响应式网关） |
| ORM | MyBatis-Plus Spring Boot 3 Starter | 3.5.17 | auth、contest、problem、submission、anti-cheat、audit 六个有库服务均已引入 |
| API 文档 | springdoc OpenAPI WebMVC UI | 2.8.17 | 除 gateway 与 media 外的 7 个服务均已引入 |
| 数据库驱动 | PostgreSQL JDBC | 42.7.13 | 同上 6 个有库服务 |
| 数据库迁移 | Flyway Core | 11.20.3 | 同上 6 个服务；默认由 `FLYWAY_ENABLED` 控制是否执行 |
| 安全 | Spring Security Starter | 随 Spring Boot 3.5.16 管理 | 7 个服务已生效（auth 显式引入，其余经资源服务器 starter 传递）；gateway 与 media 无 |
| 资源服务器 | OAuth2 Resource Server Starter | 随 Spring Boot 3.5.16 管理 | auth、contest、problem、submission、judge、anti-cheat、media（占位）、audit 已引入；**gateway 未引入**；judge 引入依赖但**未配置验签**，端点当前恒 401 |
| 服务发现 | Spring Cloud Alibaba Nacos Discovery | 2025.0.0.0 | 8 个服务均已注册到 Nacos；网关路由与服务间调用按服务名寻址 |
| 配置中心 | Spring Cloud Alibaba Nacos Config | 2025.0.0.0 | 4 个服务托管运行时可变参数（登录限流阈值、心跳超时等），改值后无需重启 |
| 健康检查 | Spring Boot Actuator | 随 Spring Boot 3.5.16 管理 | 8 个服务已引入，仅暴露 `/actuator/health`（匿名、只返回 UP/DOWN） |

### 已实际引入的 Spring Boot Starter

- `spring-boot-starter-web`：除 gateway（响应式网关）与 media（无代码）外的 7 个服务；
- `spring-boot-starter-validation`：显式声明在 auth、contest、problem、submission 四个模块；其余模块（含 anti-cheat、audit）经传递依赖也已带上 `hibernate-validator`，`@NotBlank`/`@Valid` 实际生效；
- `spring-boot-starter-security`：auth-service 显式声明；contest / problem / submission / judge / anti-cheat / audit 经资源服务器 starter 传递生效；
- `spring-boot-starter-oauth2-resource-server`：auth、contest、problem、submission、judge、anti-cheat、media、audit（**gateway 未引入**；judge 引入了依赖但未配置验签）；
- `springdoc-openapi-starter-webmvc-ui:2.8.17`：除 gateway/media 外的 7 个服务；
- `spring-boot-maven-plugin`：judge-service 当前启用可执行 Spring Boot 打包。

### 当前仅纳入编排或设计、尚未接入 Java 依赖的组件

- Elasticsearch：暂不使用；风险事件与审计的检索需求首期直接查询 PostgreSQL，后续有需要时再引入；
- MinIO：截图存储目标，尚未加入 MinIO SDK；
- JPlag：独立查重 worker，尚未接入主 Maven Reactor。

已在用的关键组件（MyBatis-Plus、Nacos 服务发现、Redis、RocketMQ、go-judge）见上表与 `docs/middleware.md`。

### Docker Compose 基础设施版本

| 组件 | 镜像 | 当前状态 |
|---|---|---|
| PostgreSQL | `postgres:16` | core Compose |
| Redis | `redis:7-alpine` | core Compose |
| MinIO | `minio/minio:latest` | core Compose；当前未固定版本，生产部署前必须锁定 digest 或明确版本 |
| Nacos | `nacos/nacos-server:v3.0.3` | platform Compose，standalone 模式；8848 API/9848 gRPC/8849 控制台；3.0.3 与 SCA 捆绑的 nacos-client 3.0.3 同补丁线 |
| RocketMQ NameServer | `apache/rocketmq:5.3.1` | platform Compose |
| RocketMQ Broker | `apache/rocketmq:5.3.1` | platform Compose；配置 `docker/rocketmq/broker.conf`，本地向宿主机广播 127.0.0.1 |
| go-judge | `criyle/go-judge`（latest，未固定） | judge Compose，评测沙箱节点；`GoJudgeAdapter` 已实现，真实容器未实测 |
| OpenResty | `openresty/openresty:1.29.2.4-0-alpine` | edge Compose，反向代理回源宿主机 gateway :8080 |

Compose 镜像版本来自 `docker/compose.core.yml`、`docker/compose.platform.yml`、`docker/compose.edge.yml` 和 `docker/compose.judge.yml`。Docker 镜像版本与 Java 依赖版本独立维护，升级其中一类不代表另一类自动升级。

## 当前架构决策

- 认证鉴权目标：auth-service 签发短期 JWT，其余服务作为 OAuth2 Resource Server 校验；
- go-judge 作为独立 Linux 沙箱执行服务；
- JPlag 作为独立异步代码查重 worker，具体版本和许可证在集成前审查；
- WebRTC 用于指定时间窗口内的屏幕/摄像头实时查看，不保存连续视频；
- 风险截图支持风险触发、定时、手动三种方式，保存到 MinIO；
- PostgreSQL 单实例多 schema；Redis、RocketMQ、Nacos、MinIO 通过 Compose 提供；
- 角色：管理员、考生、监考员、老师；RBAC 按考试/竞赛授权；
- 评测和查重通过平台自有接口抽象，避免业务代码绑定第三方协议。

## 目录

- `CHANGELOG.md`：版本变更记录（开发期里程碑与已知限制）；
- `BACKEND_IMPROVEMENT_PLAN.md`：基于 `fa9a51c` 的后端优化计划（BE-01～BE-13：优先级、证据、验收；其中部分条目已在本仓库后续提交中修复，见该文件开头的基线说明）；
- `docs/development-guide.md`：**起手开发指南**——阅读顺序、代码约定、auth-service 实现路径、测试与错误码约定（新会话从这里开始）；
- `docs/architecture.md`：服务边界、数据流和安全边界；
- `docs/services.md`：每个服务的职责、数据归属和当前实现状态；
- `docs/middleware.md`：中间件清单，各组件的用途、功能与接入状态；
- `docs/api.md`：API 与 WebSocket/媒体信令约定；
- `docs/exam-process.md`：考试生命周期、随机组卷、预检、提交、评分和待确认决策；
- `docs/deployment.md`：Docker Compose、环境变量配置和生产隔离要求；
- `docker/compose.core.yml`：PostgreSQL、Redis、MinIO；
- `docker/compose.platform.yml`：Nacos、RocketMQ；
- `docker/compose.edge.yml`：OpenResty 边缘反向代理，统一对外入口；
- `docker/compose.judge.yml`：go-judge 评测沙箱节点（独立网络）；
- `docker/compose.full.yml`：组合启动入口；
- `services/judge-service`：评测编排服务；设 `GO_JUDGE_BASE_URL` 启用 go-judge 适配器，否则需 `spring.profiles.active=mock` 才有引擎实现。

> 说明：管理后台前端曾以 `admin-web/` 引入（提交 `f3f0afa`），随后在后续提交中被整体删除，当前仓库不含该目录；它引用的 `admin/api-contracts.md` 同样不存在。若恢复该前端，请连同本节目录一并补回。

老师管理学生的流程与数据表设计见 `docs/architecture.md` 第 2.1.1/2.1.2 节。学生密码只保存哈希，任何 API、日志、消息和审计记录都不得回显或保存明文密码。

## 验证

```powershell
mvn -DskipTests package
```

业务服务已实现"实现顺序"的第 1～4 步核心链路（各服务进度见 `docs/services.md`，完整清单见 `docs/architecture.md` §15）；不要在业务服务容器中直接运行用户代码，go-judge 必须部署在独立 Linux 评测节点并进行沙箱权限审计。
