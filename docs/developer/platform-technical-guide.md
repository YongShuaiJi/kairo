# Kairo 平台技术使用文档

本文面向需要接入、二次开发或本地排查 Kairo 的开发者。目标是让开发者能快速搭建环境、理解平台功能、看懂主要代码边界，并完成一个从实例注册到规则发布的最小闭环。

当前迭代是 V1 故障注入闭环，不包含录制、数据集、提取、回放、审批流、Kafka Outbox、MinIO 对象存储或独立 worker。

## 1. 快速启动

### 1.1 环境要求

- JDK 21，用于构建 Platform Server 和运行示例容器。
- Maven 3.9+，用于构建 Java 多模块工程。
- Node.js 20+，用于本地构建 `kairo-platform-web`。
- Docker 和 Docker Compose，用于启动 PostgreSQL、Redis、Platform、Web、Demo 和 attach-executor。

### 1.2 构建与测试

```bash
mvn test
mvn -DskipTests package

cd kairo-platform-web
npm install
npm run typecheck
npm run lint
npm run build
```

### 1.3 启动本地平台

```bash
./scripts/platform-up.sh
./scripts/platform-smoke.sh
```

默认访问地址：

- Platform API: `http://127.0.0.1:18280/api/v1`
- Platform Web: `http://127.0.0.1:18380/`
- Demo 应用: `http://127.0.0.1:18082/`
- Demo 内 Agent HTTP API: `http://127.0.0.1:18080/`

本地 Compose 管理 Token：

```text
kairo-dev-admin-token-change-me
```

本地 Demo 的 attach-executor 与被测程序 demo 放在一起运行。attach-executor 的职责是贴近 demo JVM，执行 attach、deactivate、reload 等 JVM 操作；Platform 只负责生成命令、记录状态和接收回执。

### 1.4 停止环境

```bash
./scripts/platform-down.sh
```

## 2. 平台功能总览

### 2.1 Platform Server

`kairo-platform-server` 是 V1 权威控制面，主要负责：

- 认证：校验 Bearer Token，维护本地 Token 元数据。
- 资源管理：应用实例、环境、Agent、sidecar、attach-executor、规则和规则版本。
- 脚本工作台：提供 Groovy 脚本校验和试运行 API。
- 发布管理：创建发布计划、推进发布状态、调度 Agent 命令、记录实例执行结果。
- 卸载恢复：对已发布规则下发卸载命令，恢复目标类字节码。
- 查询聚合：为 Web 提供 dashboard、详情页、统一分页查询和目标方法搜索。

常用 API：

```text
GET  /api/v1/control/health
POST /api/v1/control/schedulers/run-once
GET  /api/v1/auth/me
GET  /api/v1/dashboard/overview
GET  /api/v1/query/{resource}
GET  /api/v1/details/{resource}/{id}
GET  /api/v1/targets/search
POST /api/v1/scripts/validate
POST /api/v1/scripts/test
POST /api/v1/agent-registrations/self
POST /api/v1/attach-sidecars/self
POST /api/v1/attach-executors/{id}/heartbeat
POST /api/v1/attach-executors/{id}/commands/next
POST /api/v1/attach-executor-commands/{id}/ack
GET  /api/v1/instances
GET  /api/v1/agents
POST /api/v1/agents/{id}/heartbeat
POST /api/v1/agents/{id}/commands/next
POST /api/v1/agent-commands/{id}/ack
GET  /api/v1/rules
POST /api/v1/rules
POST /api/v1/rules/{id}/versions
GET  /api/v1/rules/{id}/detail
GET  /api/v1/operation-plans
POST /api/v1/operation-plans
POST /api/v1/operation-plans/{id}/transition
POST /api/v1/operation-plans/{id}/unload
GET  /api/v1/rollout-executions
```

### 2.2 Platform Web

`kairo-platform-web` 是独立 Next.js 控制台。它通过同源 BFF 访问 Platform API，不保存权威业务数据。

主要页面：

- 登录页：接收 Platform Token，换取服务端会话。
- 运行总览：展示控制面状态、核心资源数量和近期发布状态。
- 应用实例：查看 Java 实例、环境、心跳、Java 版本、加载方式和 Agent 状态。
- Agent 诊断：查看 Agent ID、实例归属、监听地址、版本、在线状态和最后心跳。
- 规则中心：创建规则、查看规则版本、启停版本、进入脚本工作台。
- 规则工作台：选择目标方法、填写元数据、编辑 Groovy、服务端校验、试运行和保存版本。
- 发布管理：创建规则发布计划、查看实例执行、执行卸载恢复。
- 用户管理：超级管理员创建用户、续期用户 Token、强制更换用户 Token、删除用户。
- 账户与设置：所有用户从右上角用户菜单修改自己的用户名，并更换自己的 Token。

前端关键代码：

```text
kairo-platform-web/app/(platform)        页面路由
kairo-platform-web/components/layout     应用框架和导航
kairo-platform-web/components/resource   通用资源列表/表单
kairo-platform-web/components/editor     规则脚本工作台
kairo-platform-web/components/overview   总览看板
kairo-platform-web/lib/api               BFF API client 和类型
kairo-platform-web/lib/resource-config.ts 资源页面配置
```

### 2.3 Agent Runtime

Agent 运行在被测 Java 进程内，负责：

- 通过 `premain` 或 `agentmain` 加载。
- 启动本地 HTTP API 和本地诊断控制台。
- 上报 JVM、实例、Agent 版本、监听端口和能力。
- 发现目标类和方法。
- 轮询 Platform 命令。
- 编译并应用规则，触发 Byte Buddy transformer。
- 回执发布、卸载和执行结果。
- 在紧急情况下支持 disable-all、reset-class、reset-all、shutdown。

### 2.4 Demo 与 attach-executor

本地 Compose 包含一个 demo 应用和一个 attach-executor。二者处于同一侧运行边界：

- demo 是被测 Java 业务进程。
- attach-executor 贴近 demo 进程，用于执行对 demo JVM 的 attach 操作。
- Platform 通过命令表驱动 attach-executor，不直接进入目标 JVM。
- attach-executor 通过心跳和命令回执让 Platform 能看到执行状态。

这种设计把“控制面决策”和“目标机器上的 JVM 操作”分开，避免 Platform Server 需要直接持有目标宿主机权限。

## 3. 代码模块说明

### 3.1 Java 核心模块

- `kairo-bootstrap-api`：被增强业务方法可访问的 bootstrap-safe 桥接 API。
- `kairo-api`：规则脚本公共 API，包括 `MockApi`、`MockDecision`、`InvocationContext`。
- `kairo-object`：JSON 转对象、属性路径读写、返回对象和异常对象构造。
- `kairo-groovy`：Groovy 编译、脚本缓存、脚本安全策略和脚本基类。
- `kairo-core`：规则注册表、规则调度、采样、命中限制、fail-open、重入保护。
- `kairo-agent-core`：Byte Buddy transformer 和方法 Advice。
- `kairo-agent-server`：Agent 本地 HTTP API、嵌入式本地控制台、Platform 命令轮询。
- `kairo-agent-core-modern`：面向 JDK 17/21 的 shaded Agent Core 发行包。
- `kairo-agent-bootstrap`：轻量 `premain` / `agentmain` 入口，隔离加载 core jar。
- `kairo-attach-cli`：基于 JDK Attach API 的动态 attach 命令。
- `kairo-ops`：本地应急运维 CLI。
- `kairo-sidecar`：attach executor 与运行时辅助边界。
- `kairo-demo`：本地 Spring Boot demo。
- `kairo-integration-tests`：JVM 动态 attach 集成测试。

### 3.2 平台模块

- `kairo-platform-server`：Spring Boot 3 / Java 21 控制面，依赖 PostgreSQL 和 Redis。
- `kairo-platform-web`：Next.js / React 19 前端控制台。

### 3.3 数据库迁移

迁移脚本位于：

```text
kairo-platform-server/src/main/resources/db/migration
```

开发时新增表结构必须通过 Flyway 迁移提交，不能只改本地数据库。当前 V1 使用中的核心表覆盖应用、环境、实例、Agent、规则、规则版本、发布计划、执行记录、命令、Token 和审计。

## 4. 产品设计

### 4.1 V1 产品目标

V1 只解决一个核心问题：让开发、测试和稳定性工程师可以在非生产环境把一段 Groovy 故障注入规则发布到在线 Java 进程，并能快速卸载恢复。

V1 成功闭环：

1. Java 应用启动或被 attach。
2. Agent 自动注册应用、环境、实例和自身信息。
3. 控制台能看到实例和 Agent 在线。
4. 用户创建规则并保存版本。
5. 用户创建发布计划。
6. Platform 调度器生成 Agent 命令。
7. Agent 拉取命令、应用规则并 ACK。
8. 业务调用命中规则。
9. 用户卸载规则，目标类恢复。

### 4.2 功能边界

当前版本支持：

- DEV/SIT/UAT 故障注入。
- 目标方法选择。
- Groovy 脚本校验与试运行。
- 规则版本管理。
- 发布计划和实例执行状态。
- 已发布规则卸载。
- Agent 在线诊断。
- 本地 Token 鉴权。

当前版本不支持：

- 生产流量录制、数据集提取和回放。
- 人工审批流。
- 多区域容灾。
- OIDC/SSO。
- Kubernetes 原生发布编排。
- 云 KMS、Vault、Kafka、MinIO 作为强依赖。

## 5. 代码设计

### 5.1 控制面分层

Platform Server 使用典型 Spring Boot 分层：

- `api`：Controller，负责 HTTP 路由、请求上下文提取和响应。
- `service`：核心业务服务，负责实例、规则、环境、Token、幂等和审计。
- `command`：Agent 命令创建、租约、轮询和回执。
- `rollout`：发布计划、实例执行和卸载。
- `attach`：attach executor 注册、心跳、命令和 JVM 操作生命周期。
- `query`：Web 需要的聚合查询、详情和目标方法搜索。
- `mapper`：MyBatis SQL 映射。

### 5.2 前端分层

Platform Web 的设计目标是“独立部署、轻状态、强交互”：

- `app/api/platform/[...path]/route.ts` 是同源 BFF，负责带会话调用 Platform API。
- `lib/api/client.ts` 封装前端请求。
- `lib/resource-config.ts` 描述资源列表、表单字段和发布管理 tab。
- `components/resource/resource-page.tsx` 渲染通用资源页。
- `components/editor/rule-workbench.tsx` 承担规则编辑、校验、试运行和保存。
- `components/layout/app-shell.tsx` 管理导航、会话、健康检查和命令面板。

### 5.3 Agent 分层

Agent 由 bootstrap、core、server 三个主要边界组成：

- Bootstrap 只负责从 JVM 启动参数解析 core jar、bootstrap jar、监听地址、Token 和 Platform 参数，然后隔离加载现代 core。
- Core 持有 Byte Buddy transformer、规则注册表、规则调度器和脚本执行入口。
- Server 暴露本地 HTTP API，处理类/方法发现、规则 CRUD、本地控制台和 Platform 命令轮询。

### 5.4 用户权限和 Token 设计

当前 V1 保留两类用户：

- 超级管理员：拥有 `ADMIN`、`USER_MANAGE`、`INSTANCE_MANAGE`、`AGENT_MANAGE`、`RULE_MANAGE`、`ROLLOUT_MANAGE`。
- 业务用户：拥有 `INSTANCE_MANAGE`、`AGENT_MANAGE`、`RULE_MANAGE`、`ROLLOUT_MANAGE`，不能管理用户。

用户身份使用稳定 `user_account.id` 作为 Token subject 和权限判断键。用户名只是展示名和输入名，允许用户修改，不能作为权限判断键。

Token 规则：

- 一个用户有且只能有一个有效 Token。
- 创建用户时同步签发首个 Token。
- 用户可以更换自己的 Token，更换后旧 Token 立即失效。
- 用户不能给自己续期 Token。
- 超级管理员可以为其他用户续期当前有效 Token，也可以强制更换其他用户 Token。
- 超级管理员可以删除业务用户，删除时清理该用户 Token、外部身份和角色绑定。

### 5.5 规则执行链路

规则执行时，目标方法被 Advice 包裹：

1. 方法进入时调用规则调度器。
2. 调度器找到匹配规则集。
3. 调度器构造 `InvocationContext`，注入 `args`、`target`、`method`、`mock` 等变量。
4. Groovy 脚本返回 `MockDecision`。
5. 如果决策是 `PROCEED`，继续原方法，可携带改写后的参数。
6. 如果决策是 `RETURN`，短路或替换返回值。
7. 如果决策是 `THROW`，抛出指定异常。
8. 方法正常返回或抛出异常时，根据阶段继续执行 RETURN 或 THROWS 规则。

## 6. 实例注册设计

### 6.1 业务标识

Agent 注册时必须上报真实业务标识：

- `projectName`：项目名，例如 `kairo`。
- `applicationName`：应用名，例如 `kairo-demo`。
- `environmentName`：环境名，例如 `dev`、`sit`、`uat`。
- `hostname`：运行主机名。
- `processId`：进程 ID。
- `processStartId`：进程启动唯一标识，建议包含主机、pid、启动时间。

Platform 根据项目名、应用名和环境名复用或创建资源，并生成内部 ID。不要把 `app-default` 或 `env-dev` 作为新接入系统的固定标识。

### 6.2 注册请求

最小注册请求示例：

```json
{
  "projectName": "kairo",
  "applicationName": "kairo-demo",
  "environmentName": "sit",
  "hostname": "demo-host",
  "processId": "12345",
  "processStartId": "demo-host:12345:1783098000000",
  "jvmStartedAtEpochMillis": 1783098000000,
  "runtime": "java-21",
  "javaVersion": "21.0.11",
  "loadMode": "premain",
  "agentVersion": "0.1.0",
  "bootstrapVersion": "embedded",
  "listenHost": "127.0.0.1",
  "listenPort": 18080,
  "capabilities": ["DISCOVER_TARGETS", "APPLY_RULE", "RESET_CLASS"],
  "labels": {
    "zone": "local"
  },
  "reason": "register runtime agent"
}
```

响应会包含 `applicationId`、`environmentId`、`instanceId` 和 `agentId`。后续心跳和命令轮询使用 `agentId`。

### 6.3 心跳

Agent 定期调用：

```text
POST /api/v1/agents/{agentId}/heartbeat
```

心跳用于刷新在线状态、记录指标和保持发布目标可见。离线实例不会立即接收新发布命令。

## 7. Agent 设计

### 7.1 premain 模式

premain 在业务 JVM 启动时加载，适合可改启动参数的应用：

```bash
java \
  -javaagent:kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar=coreJar=kairo-agent-core-modern/target/kairo-agent-core-modern.jar,bootstrapJar=kairo-bootstrap-api/target/kairo-bootstrap-api-0.1.0-SNAPSHOT.jar,host=127.0.0.1,port=18080,token=dev,platformUrl=http://127.0.0.1:18280,platformToken=kairo-dev-admin-token-change-me,platformProjectName=kairo,platformApplicationName=kairo-demo,platformEnvironmentName=sit \
  -jar kairo-demo/target/kairo-demo-0.1.0-SNAPSHOT-exec.jar \
  --server.port=18090
```

### 7.2 agentmain / attach 模式

attach 模式适合已经运行的 JVM。可以通过 attach CLI 或 attach-executor 触发。

```bash
java -jar kairo-attach-cli/target/kairo-attach.jar \
  --pid <pid> \
  --agent kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar \
  --core-jar kairo-agent-core-modern/target/kairo-agent-core-modern.jar \
  --bootstrap-jar kairo-bootstrap-api/target/kairo-bootstrap-api-0.1.0-SNAPSHOT.jar \
  --port 18080 \
  --token dev
```

本地平台演示中，attach-executor 与 demo 在一起，Platform 下发 attach 命令给 attach-executor，由它对 demo JVM 执行 attach。

### 7.3 Agent 本地 API

Agent 本地 API 默认需要 `X-Agent-Token` 或 `Authorization: Bearer`：

```text
GET  /v1/status
GET  /jvm
GET  /v1/classes?keyword=OrderService
GET  /v1/classes/{classId}/methods
POST /v1/scripts/compile
GET  /v1/rules
POST /v1/rules
PUT  /v1/rules/{ruleId}
POST /v1/rules/{ruleId}/enable
POST /v1/rules/{ruleId}/disable
DELETE /v1/rules/{ruleId}
POST /v1/agent/reset-class
POST /v1/agent/reset-all
POST /v1/agent/shutdown
```

Platform 发布链路优先通过 Agent 轮询命令执行，不建议人工直接操作 Agent 本地规则 API，除非是在本地调试或应急。

## 8. 规则发布设计

### 8.1 规则对象

规则绑定到应用、环境和一个或多个 Java 方法目标。核心字段包括：

- `applicationId`：应用 ID。
- `environmentId`：环境 ID。
- `name`：规则名。
- `riskLevel`：风险等级。
- `script.phase`：执行阶段，支持 `BEFORE`、`RETURN`、`THROWS`。
- `script.script`：Groovy 脚本文本。
- `targets`：目标类、方法、描述符或目标选择信息。
- `capabilities`：规则能力，例如 early return。

### 8.2 版本

每次保存脚本会形成规则版本。发布计划必须指定规则版本。这样可以保证发布时使用的是确定脚本，而不是被后续编辑覆盖的草稿。

### 8.3 发布计划

发布计划描述“把哪个规则版本发布到哪个应用环境的哪些实例”：

```json
{
  "applicationId": "app-xxx",
  "environmentId": "env-xxx",
  "planType": "RULE_ROLLOUT",
  "resourceType": "rule",
  "resourceId": "rule-xxx",
  "resourceVersion": 1,
  "strategy": {
    "targetMode": "ALL_ACTIVE_INSTANCES",
    "automaticUnload": true
  },
  "reason": "publish fault rule"
}
```

计划创建后通常处于 `DRAFT`。需要通过 fencing token 推进到 `RUNNING`，调度器才会生成命令。

### 8.4 调度与命令

发布调度器运行后：

1. 查询 `RUNNING` 发布计划。
2. 找到目标应用环境下的在线 Agent。
3. 为每个目标 Agent 创建 `APPLY_RULE` 命令。
4. Agent 轮询 `/agents/{id}/commands/next` 获得命令。
5. Agent 应用规则后调用 `/agent-commands/{id}/ack`。
6. Platform 汇总实例执行状态，推进发布计划为成功或失败。

### 8.5 卸载

卸载不是删除规则，而是对已发布计划下发恢复命令。卸载成功后，Agent 清理规则注册并 reset 目标类，使业务行为回到原始实现。

## 9. 本地开发排查

### 9.1 发布门禁

提交前建议运行：

```bash
mvn test

cd kairo-platform-web
npm run typecheck
npm run lint
npm run build

cd ..
./scripts/platform-smoke.sh
```

### 9.2 常见问题

实例不出现：

- 检查 Agent 启动参数是否包含 `platformUrl` 和 `platformToken`。
- 检查 `platformProjectName`、`platformApplicationName`、`platformEnvironmentName` 是否是真实值。
- 检查 Platform API `/api/v1/control/health` 是否为 `UP`。

发布后无效果：

- 确认发布计划已经进入成功状态。
- 确认业务流量打到同一个实例和同一个环境。
- 确认目标方法签名来自 Agent 发现结果，而不是手写猜测。
- 确认脚本阶段和返回类型匹配。

卸载后仍生效：

- 确认卸载记录成功。
- 确认目标 Agent 在线。
- 如果实例曾离线，等待恢复在线后重新卸载。

脚本保存失败：

- 先用脚本校验查看诊断。
- 确保脚本显式 `return mock.*`。
- 不要使用线程、文件、网络、进程、系统属性和无限循环。
