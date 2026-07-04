# Runtime Mock 平台用户使用文档

本文面向平台使用者，覆盖实例注册、Agent 注册、规则开发、规则发布、卸载恢复和 Groovy 脚本编写。阅读后应能完成一次完整的 V1 故障注入演练。

当前版本只聚焦 DEV、SIT、UAT 环境的故障注入，不包含录制、数据集、提取、回放和审批流。

## 1. 基本概念

- 实例：一个正在运行的 Java 进程，例如某台机器上的 `runtime-mock-demo`。
- Agent：加载在实例 JVM 内的 Runtime Mock 探针，负责发现方法、应用规则和回执命令。
- 应用：业务应用名称，例如 `runtime-mock-demo`。
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
runtime-mock-dev-admin-token-change-me
```

Token 只在创建时明文展示。生产或长期环境中不要使用默认开发 Token。

## 3. 实例注册

### 3.1 自动注册方式

正常情况下不需要在页面手工创建实例。Java 应用加载 Agent 后，Agent 会自动调用 Platform 的自注册接口，上报项目名、应用名、环境名、主机、进程、Java 版本和监听端口。

启动参数示例：

```bash
java \
  -javaagent:runtime-mock-agent-bootstrap/target/runtime-mock-agent-bootstrap.jar=coreJar=runtime-mock-agent-core-modern/target/runtime-mock-agent-core-modern.jar,bootstrapJar=runtime-mock-bootstrap-api/target/runtime-mock-bootstrap-api-0.1.0-SNAPSHOT.jar,host=127.0.0.1,port=18080,token=dev,platformUrl=http://127.0.0.1:18280,platformToken=runtime-mock-dev-admin-token-change-me,platformProjectName=runtime-mock,platformApplicationName=runtime-mock-demo,platformEnvironmentName=sit \
  -jar runtime-mock-demo/target/runtime-mock-demo-0.1.0-SNAPSHOT-exec.jar \
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

### 3.2 页面确认

进入“应用实例”页面，确认目标实例出现。重点检查：

- 实例昵称或应用名是否正确。
- Java 版本是否符合预期。
- 状态是否在线。
- Agent 状态是否在线。
- 最近心跳是否持续刷新。
- 加载方式是 `premain`、`agentmain`、`attach` 或本地演示模式。

## 4. Agent 注册与诊断

### 4.1 Agent 自动注册

Agent 成功注册后，平台会分配 `agentId`。后续心跳、命令轮询和命令回执都围绕这个 `agentId` 执行。

Agent 会周期性完成以下动作：

1. 发送心跳。
2. 上报状态和能力。
3. 轮询待执行命令。
4. 执行发布或卸载命令。
5. 回写执行结果。

### 4.2 Agent 诊断页面

进入“Agent 诊断”页面，检查：

- Agent ID。
- 所属实例 ID。
- 监听地址。
- Agent 版本。
- 在线状态。
- 最后心跳时间。

如果 Agent 不在线，规则无法发布到该实例。已经离线的实例也无法立即卸载规则，需要等实例恢复在线后再执行卸载。

### 4.3 attach-executor

本地演示环境中，attach-executor 与 demo 被测程序在一起。它用于操作 demo 的 JVM，例如动态 attach、停用 Agent 或重新加载 Agent。

使用者通常不需要直接操作 attach-executor，只需要在平台页面或 API 上触发对应动作。Platform 会创建命令，attach-executor 拉取命令后对 demo JVM 执行，并把结果回执给 Platform。

## 5. 规则开发流程

### 5.1 选择目标方法

进入“规则中心”，点击“创建规则”。选择应用、环境和目标方法。

目标方法建议遵循：

- 优先选择业务服务层方法，例如 `OrderService#createOrder`。
- 不要选择日志、集合、JSON 序列化、线程池、JDK 内部类等高频公共方法。
- 尽量选择入参和返回值结构清晰的方法。
- 目标方法必须来自在线 Agent 的发现结果，不建议手工猜类名和描述符。

### 5.2 选择执行阶段

规则脚本支持三个阶段：

- `BEFORE`：方法执行前。适合直接抛异常、提前返回、按参数条件注入故障、改写参数后继续执行。
- `RETURN`：方法正常返回后。适合检查原始返回值、修改返回字段、把成功结果改成异常或特殊状态。
- `THROWS`：方法抛出异常后。适合替换异常、把异常降级为正常返回。

常用选择：

- 要模拟下游不可用：选 `BEFORE`。
- 要模拟返回数据异常：选 `RETURN`。
- 要验证异常降级逻辑：选 `THROWS`。

### 5.3 编写脚本

脚本必须返回一个决策：

```groovy
return mock.proceed()
```

合法决策只有三类：

- `mock.proceed()`：继续执行原方法。
- `mock.returnValue(value)`：返回指定值。
- `mock.throwException(...)`：抛出指定异常。

### 5.4 校验和试运行

保存前先点击“校验”。校验通过只能说明脚本语法和安全规则通过，不代表业务类型一定正确。

建议继续使用“试运行”验证：

- 脚本是否返回了正确决策。
- 参数路径是否能读到值。
- 返回 JSON 是否能转换为目标类型。
- 异常类是否能被目标应用加载。

### 5.5 保存规则版本

保存后会生成规则版本。保存不会立刻影响业务流量，只有发布计划成功后才会生效。

## 6. 规则发布步骤

### 6.1 创建发布计划

进入“发布管理”，点击创建发布计划，选择：

- 环境。
- 应用。
- 规则。
- 规则版本。
- 目标范围，当前通常是全部在线实例。
- 失败时是否自动卸载。

创建后发布计划处于草稿状态。

### 6.2 启动发布

在发布计划详情中推进状态。平台调度器会为目标在线 Agent 创建发布命令。Agent 拉取命令并应用规则后，发布计划会根据实例执行结果进入成功或失败状态。

### 6.3 验证效果

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

### 6.4 卸载恢复

发布完成后，如果要恢复业务行为，在发布计划详情中点击卸载。卸载会向对应 Agent 下发恢复命令。

卸载后再次调用业务接口，应恢复到发布前行为。

## 7. Groovy 脚本书写规则

### 7.1 脚本入口变量

脚本中可以直接使用以下变量：

- `args`：当前方法参数数组。
- `target`：当前实例方法的目标对象；静态方法可能为空。
- `result`：原始返回值，仅 `RETURN` 阶段有意义。
- `throwable`：原始异常，仅 `THROWS` 阶段有意义。
- `method`：目标方法元数据。
- `ctx`：调用上下文。
- `mock`：Mock API。
- `log`：脚本日志。

常用上下文访问：

```groovy
log.info("method=" + method.name())
log.info("phase=" + ctx.phase())
log.info("arg count=" + args.length)
```

### 7.2 Mock API

继续执行：

```groovy
return mock.proceed()
```

使用改写后的参数继续执行：

```groovy
return mock.proceed(args)
```

返回指定值：

```groovy
return mock.returnValue("mocked")
```

按目标返回类型从 JSON 构造返回对象：

```groovy
return mock.returnJson('{"status":"MOCKED"}')
```

抛出异常：

```groovy
return mock.throwException("java.lang.IllegalStateException", "injected failure")
```

使用异常对象抛出：

```groovy
return mock.throwException(new IllegalStateException("injected failure"))
```

读取对象属性：

```groovy
def userId = mock.get(args[0], "userId")
```

写入对象属性：

```groovy
mock.set(args[0], "userId", "mock-user")
```

判断类型：

```groovy
if (mock.isType(args[0], "com.example.demo.CreateOrderRequest")) {
    return mock.proceed()
}
```

创建目标返回对象：

```groovy
def value = mock.newReturnObject()
mock.set(value, "status", "MOCKED")
return mock.returnValue(value)
```

从 JSON 构造指定类型：

```groovy
def value = mock.fromJson('{"status":"MOCKED"}', method.returnType())
return mock.returnValue(value)
```

### 7.3 最小脚本：不改变行为

用于验证规则能保存、发布和命中。

阶段：`BEFORE`

```groovy
log.info("Runtime Mock rule hit")
return mock.proceed()
```

效果：方法继续执行，业务行为不变。

### 7.4 简单故障：调用前直接抛异常

用于模拟下游不可用、业务依赖失败。

阶段：`BEFORE`

```groovy
return mock.throwException(
    "java.lang.IllegalStateException",
    "Runtime Mock injected failure"
)
```

注意：异常类型应是目标应用 ClassLoader 能加载的类型。通用场景优先使用 `java.lang.IllegalStateException`、`java.lang.RuntimeException`。

### 7.5 简单返回：跳过原方法

目标方法返回 `String` 时：

阶段：`BEFORE`

```groovy
return mock.returnValue("MOCKED")
```

目标方法返回数字时：

```groovy
return mock.returnValue(503)
```

目标方法返回 `void` 时：

```groovy
log.warn("skip original void method")
return mock.returnValue(null)
```

注意：返回值类型必须能被目标方法接收。`int` 不要返回字符串，对象方法不要返回无关 Map，除非底层转换明确支持。

### 7.6 条件故障：按参数注入

目标：只有指定用户触发异常，其他用户正常。

阶段：`BEFORE`

```groovy
def request = args[0]
def userId = mock.get(request, "userId")

if (userId == "u-001") {
    return mock.throwException(
        "java.lang.IllegalStateException",
        "user u-001 blocked by Runtime Mock"
    )
}

return mock.proceed()
```

写法要点：

- 先取 `args[0]`。
- 用 `mock.get` 读取属性，避免在脚本中写反射。
- 条件不命中必须 `return mock.proceed()`。

### 7.7 多条件故障：金额和渠道组合

目标：只让高金额订单或指定渠道失败。

阶段：`BEFORE`

```groovy
def request = args[0]
def amount = mock.get(request, "amount")
def channel = mock.get(request, "channel")

if (amount != null && amount > 1000) {
    return mock.throwException(
        "java.lang.IllegalStateException",
        "large amount blocked by Runtime Mock"
    )
}

if (channel == "risk-test") {
    return mock.throwException(
        "java.lang.IllegalStateException",
        "risk-test channel blocked by Runtime Mock"
    )
}

return mock.proceed()
```

建议每个条件都写清楚错误信息，便于日志排查。

### 7.8 改写参数后继续执行

目标：把请求中的用户 ID 改成测试用户，然后继续执行原业务方法。

阶段：`BEFORE`

```groovy
def request = args[0]
mock.set(request, "userId", "mock-user")

log.info("rewrite userId to mock-user")
return mock.proceed(args)
```

注意：

- 改写参数有副作用，只建议在测试环境使用。
- 如果参数对象会被上游复用，可能影响调用方看到的对象状态。
- 改写后应使用 `mock.proceed(args)`，让决策明确携带当前参数。

### 7.9 正常返回后修改字段

目标：原方法正常创建订单，但把订单状态改成 `PROCESSING`。

阶段：`RETURN`

```groovy
mock.set(result, "status", "PROCESSING")
mock.set(result, "message", "changed by Runtime Mock")

return mock.returnValue(result)
```

适合验证下游系统收到特殊状态时的处理逻辑。

### 7.10 正常返回后按结果条件处理

目标：只有原始结果金额大于 1000 时改写状态。

阶段：`RETURN`

```groovy
def amount = mock.get(result, "amount")

if (amount != null && amount > 1000) {
    mock.set(result, "status", "REVIEWING")
    mock.set(result, "message", "large order changed by Runtime Mock")
    return mock.returnValue(result)
}

return mock.proceed()
```

在 `RETURN` 阶段，`mock.proceed()` 表示保留原始返回值。

### 7.11 返回 JSON 对象

目标方法返回 DTO 时，可以用 JSON 描述返回对象。

阶段：`BEFORE`

```groovy
return mock.returnJson('''
{
  "id": "mock-order-001",
  "userId": "u-001",
  "amount": 12.34,
  "status": "MOCKED",
  "message": "created by Runtime Mock"
}
''')
```

注意：

- JSON 字段名应与目标返回类型字段匹配。
- 数字、布尔值不要写成字符串，除非目标字段就是字符串。
- 对象层级较深时，优先先试运行再发布。

### 7.12 异常降级为正常返回

目标：原方法抛异常时，返回兜底对象。

阶段：`THROWS`

```groovy
log.warn("original exception: " + throwable.getClass().getName() + ": " + throwable.getMessage())

return mock.returnJson('''
{
  "id": "fallback-order",
  "status": "FALLBACK",
  "message": "fallback by Runtime Mock"
}
''')
```

适合验证上游是否能接受降级结果。不要长期用这种脚本掩盖真实异常。

### 7.13 替换异常

目标：把原始异常替换成业务更容易识别的异常。

阶段：`THROWS`

```groovy
log.warn("replace original exception: " + throwable.getMessage())

return mock.throwException(
    "java.lang.IllegalStateException",
    "replaced by Runtime Mock"
)
```

### 7.14 复杂示例：组合参数、日志、返回对象

目标：

- `userId = u-vip` 时正常执行。
- `amount > 5000` 时返回审核中订单。
- 其他 `risk-test` 渠道抛异常。
- 其余请求继续执行。

阶段：`BEFORE`

```groovy
def request = args[0]
def userId = mock.get(request, "userId")
def amount = mock.get(request, "amount")
def channel = mock.get(request, "channel")

log.info("Runtime Mock check userId=" + userId + ", amount=" + amount + ", channel=" + channel)

if (userId == "u-vip") {
    return mock.proceed()
}

if (amount != null && amount > 5000) {
    return mock.returnJson('''
    {
      "id": "review-order",
      "status": "REVIEWING",
      "message": "large order requires review"
    }
    ''')
}

if (channel == "risk-test") {
    return mock.throwException(
        "java.lang.IllegalStateException",
        "risk-test channel rejected"
    )
}

return mock.proceed()
```

复杂脚本建议控制在 30 行左右。如果场景继续膨胀，应拆成多条规则或缩小测试目标。

## 8. Groovy 安全与规范

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

## 9. 常见故障排查

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

- 确认有 `return mock.proceed()`、`return mock.returnValue(...)` 或 `return mock.throwException(...)`。
- 检查字符串引号是否闭合。
- 检查 JSON 是否合法。
- 移除线程、文件、网络、系统属性等禁用操作。

发布后无效果：

- 确认发布计划状态成功。
- 确认实例执行记录成功。
- 确认请求打到同一个环境和同一个实例。
- 确认目标方法是实际被调用的方法。
- 确认脚本阶段正确。

卸载失败：

- 确认 Agent 在线。
- 确认发布计划曾经成功发布。
- 查看卸载记录的失败原因。
- 实例离线时，恢复在线后重新卸载。
