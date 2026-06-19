# Runtime Mock 模块与服务边界治理

## 1. 原则

Runtime Mock 不追求单体化，也不以模块数量作为复杂度指标。合理拆分能够提供稳定契约、
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
| Platform API | 平台进程 | 已实现 | HTTP API、认证、RBAC、审计和状态管理 |
| Platform Worker | 平台进程 | 已实现 | 异步任务、Outbox、Rollout、Extraction、Replay |
| Platform Web | 前端进程 | 已实现 | Next.js 中央管理界面、同源 BFF、Demo 验收模式和 Monaco 工作台 |
| Runtime Agent | 嵌入式运行时 | 已实现 | 位于目标 JVM，不是中心微服务 |
| PostgreSQL/Redis/Kafka/MinIO | 基础设施 | 已接入 | 分别承担权威状态、协调、事件和大对象 |

Platform API 与 Worker 使用同一个模块和镜像，但以不同运行角色启动。这是有意义的
故障和扩缩容边界，不要求拆成两个代码仓库。

## 3. 保留的模块边界

| 模块 | 保留原因 |
| --- | --- |
| `runtime-mock-bootstrap-api` | Bootstrap ClassLoader 可见的最小 ABI |
| `runtime-mock-api` | 面向脚本和调用方的稳定公共模型 |
| `runtime-mock-object` | 对象构造、路径访问和 JSON 转换的内聚能力 |
| `runtime-mock-groovy` | Groovy 是可替换脚本后端，隔离重依赖和安全策略 |
| `runtime-mock-core` | 与 Instrumentation 无关的规则执行内核 |
| `runtime-mock-agent-core` | Byte Buddy 与类重转换边界 |
| `runtime-mock-agent-server` | Agent 生命周期、本地 API、控制台和 Platform 通信 |
| `runtime-mock-agent-core-modern` | shaded 发行包，不是微服务 |
| `runtime-mock-agent-bootstrap` | Java 8 thin agent 和隔离加载入口 |
| `runtime-mock-attach-cli` | 需要 JDK Attach 权限的安装工具 |
| `runtime-mock-ops` | 低权限网络应急工具，与 Attach 权限模型不同 |
| `runtime-mock-sidecar` | 录制脱敏、tokenization、加密 WAL 的领域库，不是运行服务 |
| `runtime-mock-storage-spi` | 云无关对象存储契约 |
| `runtime-mock-storage-minio` | MinIO/S3 兼容实现，可被云适配器替换 |
| `runtime-mock-platform-server` | 模块化控制面和 Worker 代码 |
| `runtime-mock-platform-web` | 独立前端技术栈、产品入口、构建、测试和发布边界 |
| `runtime-mock-demo` | 可运行验收目标，不进入生产部署 |
| `runtime-mock-integration-tests` | 跨模块 JVM/Agent 验收边界 |

`runtime-mock-sidecar` 保留是因为录制安全是独立领域能力，但它不是必须部署的微服务。
当前生产路径由 Agent 有界批量上传器、Platform 录制接入服务、脱敏/信封加密和 MinIO
共同完成；Sidecar 模块提供可复用的 WAL 与数据安全能力，不制造额外运行单元。

## 4. 本次淘汰的边界

### `runtime-mock-control-server`

该模块同时承担静态资源代理和一套内存 recording/dataset/replay 控制面。正式 Platform
已经提供 PostgreSQL 权威实现，继续保留会产生双写、状态语义分叉和错误运维入口。
因此删除，不做兼容运行。

### `runtime-mock-web`

静态控制台只有 Agent Server 一个消费者，没有独立发布、版本或权限边界。资源已经并入
`runtime-mock-agent-server`，由 loopback Agent HTTP Server 直接提供。

该结论只适用于 Agent 本地应急控制台。中央 Platform Web 面向多角色、多资源和复杂工作流，
具有独立产品与工程边界，应作为 `runtime-mock-platform-web` 单独建设。

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
