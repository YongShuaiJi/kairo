# Kairo 平台用户使用文档

本文面向平台使用者，覆盖登录、账户设置、实例注册、Agent 注册、规则开发、规则发布、卸载恢复和 Groovy 脚本入门。阅读后应能完成一次完整的 V1 故障注入演练。

当前版本只聚焦 DEV、SIT、UAT 环境的故障注入，不包含录制、数据集、提取、回放和审批流。

## 1. 基本概念

- 实例：一个正在运行的 Java 进程，例如某台机器上的 `kairo-demo`。
- Agent：加载在实例 JVM 内的 Kairo 探针，负责发现方法、应用规则和回执命令。
- 应用：业务应用名称，例如 `kairo-demo`。
- 环境：实例所属环境，当前使用 `dev`、`sit`、`uat`。
- 规则：一段 Groovy 脚本，绑定到目标 Java 方法。
- 规则版本：规则每次保存后的确定版本。
- 发布计划：把某个规则版本发布到目标环境在线实例的操作单。
- 卸载：从目标实例移除已发布规则并恢复原始字节码。

## 2. 登录平台

1. 打开 Web 控制台：`http://127.0.0.1:18380/`。
2. 输入 Platform Token。
3. 登录后进入“运行总览”。

本地 Compose 默认 Token：

```text
kairo-dev-admin-token-change-me
```

Token 只在创建或更换时明文展示一次。生产或长期环境中不要使用默认开发 Token。

## 3. 账户、用户和 Token

### 3.1 账户与设置

所有用户都从右上角用户菜单进入“账户与设置”。这里可以做两件事：

- 修改自己的用户名。
- 更换自己的 Token。

更换自己的 Token 会生成新的明文 Token，同时旧 Token 立即失效。新 Token 只展示一次，复制后再关闭弹窗。

用户不能给自己的 Token 续期。续期只能由超级管理员在“用户管理”中对其他用户操作。

### 3.2 用户管理

只有超级管理员可以看到“用户管理”菜单。超级管理员可以：

- 创建用户并签发首个 Token。
- 为其他用户续期当前有效 Token。
- 强制更换其他用户 Token，更换后旧 Token 失效。
- 删除业务用户，删除后该用户无法继续登录。

业务用户不能看到用户管理菜单，也不能调用用户管理 API。

### 3.3 Token 有效性

一个用户有且只能有一个有效 Token。以下场景会导致原 Token 不再有效：

- 用户自己更换 Token。
- 超级管理员强制更换该用户 Token。
- 超级管理员删除该用户。
- Token 到达过期时间。

续期只更新当前有效 Token 的过期时间，不会生成新的明文 Token。

## 4. 实例注册

### 4.1 自动注册方式

正常情况下不需要在页面手工创建实例。Java 应用加载 Agent 后，Agent 会自动调用 Platform 的自注册接口，上报项目名、应用名、环境名、主机、进程、Java 版本和监听端口。

启动参数示例：

```bash
java \
  -javaagent:kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar=coreJar=kairo-agent-core-modern/target/kairo-agent-core-modern.jar,bootstrapJar=kairo-bootstrap-api/target/kairo-bootstrap-api-1.7.0-SNAPSHOT.jar,host=127.0.0.1,port=18080,token=dev,platformUrl=http://127.0.0.1:18280,platformToken=kairo-dev-admin-token-change-me,platformProjectName=kairo,platformApplicationName=kairo-demo,platformEnvironmentName=sit \
  -jar kairo-demo/target/kairo-demo-1.7.0-SNAPSHOT-exec.jar \
  --server.port=18090
```

关键参数：

- `platformUrl`：Platform API 地址。
- `platformToken`：Agent 用来注册和轮询命令的 Platform Token。
- `platformProjectName`：项目名。
- `platformApplicationName`：应用名。
- `platformEnvironmentName`：环境名，建议使用 `dev`、`sit`、`uat`。
- `host`、`port`、`token`：Agent 本地 HTTP 服务监听地址和本地访问 Token。

不要把新应用写成 `app-default`，也不要依赖固定的默认拓扑。平台会根据真实项目名、应用名和环境名创建或复用资源。

### 4.2 页面确认

进入“应用实例”页面，确认目标实例出现。重点检查：

- 实例昵称或应用名是否正确。
- Java 版本是否符合预期。
- 状态是否在线。
- Agent 状态是否在线。
- 最近心跳是否持续刷新。
- 加载方式是 `premain`、`agentmain`、`attach` 或本地演示模式。

## 5. Agent 注册与诊断

### 5.1 Agent 自动注册

Agent 成功注册后，平台会分配 `agentId`。后续心跳、命令轮询和命令回执都围绕这个 `agentId` 执行。

Agent 会周期性完成以下动作：

1. 发送心跳。
2. 上报状态和能力。
3. 轮询待执行命令。
4. 执行发布或卸载命令。
5. 回写执行结果。

### 5.2 Agent 诊断页面

进入“Agent 诊断”页面，检查：

- Agent ID。
- 所属实例 ID。
- 监听地址。
- Agent 版本。
- 在线状态。
- 最后心跳时间。

如果 Agent 不在线，规则无法发布到该实例。已经离线的实例也无法立即卸载规则，需要等实例恢复在线后再执行卸载。

### 5.3 attach-executor

本地演示环境中，attach-executor 与 demo 被测程序在一起。它用于操作 demo 的 JVM，例如动态 attach、停用 Agent 或重新加载 Agent。

使用者通常不需要直接操作 attach-executor，只需要在平台页面或 API 上触发对应动作。Platform 会创建命令，attach-executor 拉取命令后对 demo JVM 执行，并把结果回执给 Platform。

## 6. 规则开发流程

### 6.1 选择目标方法

进入“规则中心”，点击“创建规则”。选择应用、环境和目标方法。

目标方法建议遵循：

- 优先选择业务服务层方法，例如 `OrderService#createOrder`。
- 不要选择日志、集合、JSON 序列化、线程池、JDK 内部类等高频公共方法。
- 尽量选择入参和返回值结构清晰的方法。
- 目标方法必须来自在线 Agent 的发现结果，不建议手工猜类名和描述符。

### 6.2 选择执行阶段

规则脚本支持三个阶段：

- `BEFORE`：方法执行前。适合直接抛异常、提前返回、按参数条件注入故障、改写参数后继续执行。
- `RETURN`：方法正常返回后。适合检查原始返回值、修改返回字段、把成功结果改成异常或特殊状态。
- `THROWS`：方法抛出异常后。适合替换异常、把异常降级为正常返回。

常用选择：

- 要模拟下游不可用：选 `BEFORE`。
- 要模拟返回数据异常：选 `RETURN`。
- 要验证异常降级逻辑：选 `THROWS`。

### 6.3 编写脚本

脚本必须返回一个决策：

```groovy
return mock.proceed()
```

合法决策只有三类：

- `mock.proceed()`：继续执行原方法。
- `mock.returnValue(value)` 或 `mock.returnJson(json)`：返回指定值。
- `mock.throwException(...)`：抛出指定异常。

### 6.4 校验和试运行

保存前先点击“校验”。校验通过只能说明脚本语法和安全规则通过，不代表业务类型一定正确。

建议继续使用“试运行”验证：

- 脚本是否返回了正确决策。
- 参数路径是否能读到值。
- 返回 JSON 是否能转换为目标类型。
- 异常类是否能被目标应用加载。

### 6.5 保存规则版本

保存后会生成规则版本。保存不会立刻影响业务流量，只有发布计划成功后才会生效。

## 7. 规则发布步骤

### 7.1 创建发布计划

进入“发布管理”，点击创建发布计划，选择：

- 环境。
- 应用。
- 规则。
- 规则版本。
- 目标范围，当前通常是全部在线实例。
- 失败时是否自动卸载。

创建后发布计划处于草稿状态。

### 7.2 启动发布

在发布计划详情中推进状态。平台调度器会为目标在线 Agent 创建发布命令。Agent 拉取命令并应用规则后，发布计划会根据实例执行结果进入成功或失败状态。

### 7.3 验证效果

以本地 demo 下单接口为例：

```bash
curl -X POST http://127.0.0.1:18082/demo/orders \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u-001","amount":12.34}'
```

验证顺序：

1. 发布前调用一次，确认基线正常。
2. 发布后调用一次，确认出现脚本定义的返回值或异常。
3. 查看发布管理里的实例执行状态。
4. 查看应用日志或 Agent 事件，确认规则命中。

### 7.4 卸载恢复

发布完成后，如果要恢复业务行为，在发布计划详情中点击卸载。卸载会向对应 Agent 下发恢复命令。

卸载后再次调用业务接口，应恢复到发布前行为。

## 8. Groovy 脚本入门

更完整的 API、限制、经典场景和复杂 Demo 见 [Kairo Groovy 规则脚本编写手册](./rule-script-authoring-guide.md)。

### 8.1 不改变行为

阶段：`BEFORE`

```groovy
log.info("Kairo rule hit")
return mock.proceed()
```

### 8.2 调用前直接抛异常

阶段：`BEFORE`

```groovy
return mock.throwException(
    "java.lang.IllegalStateException",
    "Kairo injected failure"
)
```

### 8.3 按参数条件注入

阶段：`BEFORE`

```groovy
def request = args[0]
def userId = mock.get(request, "userId")

if (userId == "u-001") {
    return mock.throwException(
        "java.lang.IllegalStateException",
        "user u-001 blocked by Kairo"
    )
}

return mock.proceed()
```

### 8.4 返回 DTO JSON

阶段：`BEFORE`

```groovy
return mock.returnJson('''
{
  "id": "mock-order-001",
  "userId": "u-001",
  "amount": 12.34,
  "status": "MOCKED",
  "message": "returned by Kairo"
}
''')
```

### 8.5 正常返回后修改字段

阶段：`RETURN`

```groovy
mock.set(result, "status", "PROCESSING")
mock.set(result, "message", "changed by Kairo")

return mock.returnValue(result)
```

### 8.6 异常降级为正常返回

阶段：`THROWS`

```groovy
log.warn("original exception: " + throwable.getMessage())

return mock.returnJson('''
{
  "id": "fallback-order",
  "status": "FALLBACK",
  "message": "fallback by Kairo"
}
''')
```

## 9. Groovy 安全与规范

必须遵守：

- 每个脚本必须显式 `return mock.*`。
- 默认分支必须明确写出 `return mock.proceed()`。
- 返回值类型必须匹配目标方法。
- 异常类型必须能被目标应用加载。
- 条件判断优先使用业务字段，例如用户、租户、渠道、金额、订单号。
- 发布前必须校验和试运行。

禁止写法：

- 不要写无限循环。
- 不要创建线程。
- 不要调用网络。
- 不要读写文件。
- 不要启动进程。
- 不要修改系统属性。
- 不要依赖脚本全局状态。
- 不要选择 JDK、框架、日志、集合、序列化等高频公共方法。

推荐写法：

- 脚本短小，一个脚本只表达一个故障场景。
- 每个命中分支写清楚日志或异常消息。
- 条件不命中时保持原行为。
- 先在 DEV 发布，确认后再到 SIT/UAT。
- 发布完成后及时卸载。

## 10. 常见故障排查

实例看不到：

- 检查 Java 启动参数是否有 `platformUrl`。
- 检查 `platformToken` 是否正确。
- 检查应用名和环境名是否真实填写。
- 检查 Platform API 是否健康。

Agent 不在线：

- 检查 Agent 本地端口是否被占用。
- 检查 Agent 日志是否注册失败。
- 检查 Token 是否有 Agent 注册和命令轮询权限。
- 检查网络是否能访问 Platform API。

目标方法搜不到：

- 确认业务代码已经加载对应类。
- 先调用一次业务接口，让 JVM 加载目标类。
- 用更短的类名关键词搜索，例如 `OrderService`。
- 避免搜索接口名但实际增强实现类。

脚本校验失败：

- 确认所有分支都有 `return mock.*`。
- 确认没有使用禁用的 import、循环、反射、线程、文件、网络或进程操作。
- 确认 JSON 字符串合法。
- 确认 checked exception 是目标方法声明过的异常。

发布后无效果：

- 确认发布计划已经进入成功状态。
- 确认业务流量打到同一个实例和同一个环境。
- 确认目标方法签名来自 Agent 发现结果，而不是手写猜测。
- 确认脚本阶段和返回类型匹配。

卸载后仍生效：

- 确认卸载记录成功。
- 确认目标 Agent 在线。
- 如果实例曾离线，等待恢复在线后重新卸载。
- 确认没有另一条规则仍绑定同一目标方法。
