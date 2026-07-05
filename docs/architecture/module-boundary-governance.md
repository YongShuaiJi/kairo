# Kairo 模块与服务边界治理

## 1. 原则

Kairo 不追求单体化，也不以模块数量作为复杂度指标。合理拆分能够提供稳定契约、
隔离依赖、控制权限、支持替换实现并降低变更影响。需要消除的是没有真实边界的模块：
无消费者、无独立运行或发行价值、与权威实现重复、只做机械转发的模块。

判断顺序：

1. 先判断是否是运行进程；
2. 再判断是否是发行或安全边界；
3. 最后判断是否是代码组织边界。

Maven module、JAR、运行进程和微服务不是同一个概念。

## 2. 当前与目标运行单元

| 运行单元 | 类型 | 状态 | 说明 |
| --- | --- | --- | --- |
| Platform API | 平台进程 | 已实现 | HTTP API、认证、RBAC、审计、状态管理和 V1 调度器 |
| Platform Web | 前端进程 | 已实现 | Next.js 中央管理界面、同源 BFF、Demo 验收模式和 Monaco 工作台 |
| Runtime Agent | 嵌入式运行时 | 已实现 | 位于目标 JVM，不是中心微服务 |
| PostgreSQL/Redis | 基础设施 | 已接入 | 分别承担权威状态和协调 |

Platform API 与 V1 调度器使用同一个模块和镜像。Kafka、MinIO、独立 Worker 扩缩容属于后续阶段。

## 3. 保留的模块边界

| 模块 | 保留原因 |
| --- | --- |
| `kairo-bootstrap-api` | Bootstrap ClassLoader 可见的最小 ABI |
| `kairo-api` | 面向脚本和调用方的稳定公共模型 |
| `kairo-object` | 对象构造、路径访问和 JSON 转换的内聚能力 |
| `kairo-groovy` | Groovy 是可替换脚本后端，隔离重依赖和安全策略 |
| `kairo-core` | 与 Instrumentation 无关的规则执行内核 |
| `kairo-agent-core` | Byte Buddy 与类重转换边界 |
| `kairo-agent-server` | Agent 生命周期、本地 API、控制台和 Platform 通信 |
| `kairo-agent-core-modern` | shaded 发行包，不是微服务 |
| `kairo-agent-bootstrap` | Java 8 thin agent 和隔离加载入口 |
| `kairo-attach-cli` | 需要 JDK Attach 权限的安装工具 |
| `kairo-ops` | 低权限网络应急工具，与 Attach 权限模型不同 |
| `kairo-sidecar` | attach executor 与运行时辅助边界，用于本地 demo attach 流程 |
| `kairo-storage-spi` | 后续对象存储扩展契约，V1 不作为运行依赖 |
| `kairo-storage-minio` | 后续 MinIO/S3 兼容实现，V1 不作为运行依赖 |
| `kairo-platform-server` | 模块化控制面和 V1 调度器代码 |
| `kairo-platform-web` | 独立前端技术栈、产品入口、构建、测试和发布边界 |
| `kairo-demo` | 可运行验收目标，不进入生产部署 |
| `kairo-integration-tests` | 跨模块 JVM/Agent 验收边界 |

`kairo-sidecar` 保留是因为当前 attach demo 需要一个与被测 JVM 共享 PID 命名空间的
executor，用于对目标 JVM 执行 attach 操作。它不是 V1 的独立生产存储、录制或回放服务。

## 4. 本次淘汰的边界

### `kairo-control-server`

该模块同时承担静态资源代理和一套内存 recording/dataset/replay 控制面。正式 Platform
已经提供 PostgreSQL 权威实现，继续保留会产生双写、状态语义分叉和错误运维入口。
因此删除，不做兼容运行。

### `kairo-web`

静态控制台只有 Agent Server 一个消费者，没有独立发布、版本或权限边界。资源已经并入
`kairo-agent-server`，由 loopback Agent HTTP Server 直接提供。

该结论只适用于 Agent 本地应急控制台。中央 Platform Web 面向多角色、多资源和复杂工作流，
具有独立产品与工程边界，应作为 `kairo-platform-web` 单独建设。

## 5. 新模块准入规则

新增模块必须至少满足一项：

- 对外稳定契约；
- SPI 与具体实现隔离；
- JVM/ClassLoader/JDK 字节码版本隔离；
- 独立发行、签名或供应链要求；
- 独立部署、扩缩容、故障域或数据所有权；
- 独立权限和操作主体；
- 明确领域能力，并有消费者和独立测试。

以下理由不足以单独建模块：

- 只有一两个类；
- “以后可能会用”但没有消费者；
- 只转发另一个模块接口；
- 复制权威服务的简化内存版本；
- 只为了让目录看起来整齐。

每个模块必须在 README 或本文件中说明边界理由。失去理由后，应合并或删除。
