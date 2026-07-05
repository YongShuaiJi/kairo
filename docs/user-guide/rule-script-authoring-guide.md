# Runtime Mock Groovy 规则脚本编写手册

本文面向规则开发者，说明 Runtime Mock Groovy 脚本的执行模型、可用变量、Mock API、类型约束、安全限制和典型脚本写法。脚本运行在被测 JVM 内，因此规则必须短小、明确、可回滚，只做当前方法调用的一次性决策。

## 1. 脚本必须返回决策

每个脚本必须显式 `return` 一个 `MockDecision`。合法决策只有三类：

| 决策 | 含义 | 常用阶段 |
| --- | --- | --- |
| `mock.proceed()` | 继续原方法或保留原结果 | `BEFORE`、`RETURN`、`THROWS` |
| `mock.proceed(args)` | 使用当前参数数组继续执行原方法 | `BEFORE` |
| `mock.returnValue(value)` | 跳过原方法或替换返回值 | `BEFORE`、`RETURN`、`THROWS` |
| `mock.returnJson(json)` | 按目标方法返回类型把 JSON 转成对象并返回 | `BEFORE`、`RETURN`、`THROWS` |
| `mock.throwException(...)` | 抛出指定异常 | `BEFORE`、`RETURN`、`THROWS` |

最小脚本：

```groovy
return mock.proceed()
```

不要只写表达式，也不要遗漏默认分支。条件不命中时也要明确返回：

```groovy
if (someCondition) {
    return mock.throwException("java.lang.IllegalStateException", "injected failure")
}

return mock.proceed()
```

## 2. 执行阶段

| 阶段 | 进入时机 | 可用数据 | 典型用途 |
| --- | --- | --- | --- |
| `BEFORE` | 目标方法执行前 | `args`、`target`、`method`、`ctx`、`mock`、`log` | 直接抛异常、短路返回、按参数条件注入、改写参数后继续 |
| `RETURN` | 目标方法正常返回后 | `args`、`target`、`result`、`method`、`ctx`、`mock`、`log` | 修改返回字段、按原返回值判断、把成功改成异常 |
| `THROWS` | 目标方法抛异常后 | `args`、`target`、`throwable`、`method`、`ctx`、`mock`、`log` | 替换异常、异常降级为正常返回 |

阶段选择建议：

- 模拟依赖不可用：选 `BEFORE`。
- 模拟返回数据异常：选 `RETURN`。
- 验证异常降级：选 `THROWS`。
- 需要跳过真实副作用，例如发短信、扣库存：选 `BEFORE` 并返回目标方法可接受的值。

## 3. 入口变量

脚本中可以直接使用以下变量：

| 变量 | 类型或含义 | 说明 |
| --- | --- | --- |
| `args` | `Object[]` | 当前方法参数数组，等价于 `ctx.arguments()` |
| `target` | `Object` | 实例方法的目标对象，静态方法可能为空 |
| `result` | `Object` | 原始返回值，仅 `RETURN` 阶段有意义 |
| `throwable` | `Throwable` | 原始异常，仅 `THROWS` 阶段有意义 |
| `method` | `MethodMetadata` | 目标方法元数据 |
| `ctx` | `InvocationContext` | 调用上下文 |
| `mock` | `MockApi` | 决策和对象操作 API |
| `log` | `ScriptLog` | 脚本日志 |

常用上下文：

```groovy
log.info("phase=" + ctx.phase())
log.info("method=" + method.name())
log.info("descriptor=" + method.descriptor())
log.info("arg count=" + args.length)
```

`method` 可读信息：

- `method.declaringClass()`：目标类。
- `method.name()`：方法名。
- `method.descriptor()`：JVM 方法描述符。
- `method.returnType()`：返回类型。
- `method.parameterTypes()`：参数类型数组。
- `method.exceptionTypes()`：声明异常类型数组。

## 4. Mock API

### 4.1 继续执行

```groovy
return mock.proceed()
```

在 `BEFORE` 阶段表示继续执行原方法。在 `RETURN` 阶段表示保留原始返回值。在 `THROWS` 阶段表示保留原始异常。

改写参数后继续：

```groovy
mock.set(args[0], "userId", "mock-user")
return mock.proceed(args)
```

`mock.proceed(args)` 要求参数数量和目标方法参数数量一致，且每个参数类型能被目标方法接收。

### 4.2 返回指定值

```groovy
return mock.returnValue("MOCKED")
```

返回值必须能赋给目标方法返回类型：

- `int`、`long`、`boolean` 等 primitive 返回值不能返回 `null`。
- `void` 方法只能 `return mock.returnValue(null)` 或 `return mock.proceed()`。
- DTO 返回值不要直接返回无关 `Map`，优先用 `mock.returnJson(...)` 或 `mock.newReturnObject()`。

### 4.3 返回 JSON

```groovy
return mock.returnJson('''
{
  "id": "mock-order-001",
  "status": "MOCKED",
  "message": "created by Runtime Mock"
}
''')
```

`mock.returnJson(json)` 会按目标方法返回类型转换 JSON。字段名应和目标 DTO 字段匹配，数字和布尔值应保持 JSON 原生类型。

### 4.4 抛异常

用异常类名和消息：

```groovy
return mock.throwException(
    "java.lang.IllegalStateException",
    "Runtime Mock injected failure"
)
```

用异常对象：

```groovy
return mock.throwException(new IllegalStateException("Runtime Mock injected failure"))
```

异常类型规则：

- `RuntimeException` 和 `Error` 可以抛。
- checked exception 必须是目标方法 `throws` 声明中的类型或其子类。
- 异常类必须能被目标应用 ClassLoader 加载。通用故障优先用 `java.lang.IllegalStateException` 或 `java.lang.RuntimeException`。

### 4.5 读取和写入对象属性

读取：

```groovy
def userId = mock.get(args[0], "userId")
def city = mock.get(args[0], "address.city")
```

写入：

```groovy
mock.set(args[0], "userId", "mock-user")
mock.set(result, "status", "PROCESSING")
```

建议使用 `mock.get` 和 `mock.set`，不要在脚本中写反射。

### 4.6 类型判断

```groovy
if (mock.isType(args[0], "com.example.demo.CreateOrderRequest")) {
    return mock.proceed()
}

return mock.throwException("java.lang.IllegalStateException", "unexpected request type")
```

### 4.7 创建返回对象

目标返回类型有无参构造时：

```groovy
def value = mock.newReturnObject()
mock.set(value, "id", "mock-order-001")
mock.set(value, "status", "MOCKED")
return mock.returnValue(value)
```

从 JSON 构造指定类型：

```groovy
def value = mock.fromJson('{"status":"MOCKED"}', method.returnType())
return mock.returnValue(value)
```

### 4.8 日志

```groovy
log.debug("debug message")
log.info("rule hit")
log.warn("risk condition matched")
log.error("script branch failed", throwable)
```

`log.error` 需要传入 `String` 和 `Throwable`。如果没有异常对象，使用 `log.warn(...)` 或 `log.info(...)`。

## 5. 安全限制和写法约束

脚本运行在被测 JVM 内，不是独立沙箱进程。以下限制用于防止脚本卡死业务线程、泄漏数据、破坏宿主环境或绕过平台卸载。

当前限制包括：

- 脚本最大 16 KB。
- 脚本最多 400 行。
- 代码块嵌套深度最多 5 层。
- 字面量集合最多 100 个元素。
- 字符串字面量最大 8 KB。
- 禁止声明 `package`。
- 禁止方法定义。
- 禁止 `for`、`while`、`do while`、`synchronized`。
- 禁止危险 import 和星号 import，例如 `java.io.*`、`java.net.*`、`java.lang.reflect.*`、`java.nio.*`、`java.util.concurrent.*`、`groovy.lang.*`。
- 禁止危险类或接收者，例如 `File`、`Socket`、`Runtime`、`System`、`Thread`、`ClassLoader`、反射类、进程类。
- 禁止危险方法，例如 `exec`、`exit`、`sleep`、`start`、`wait`、`notify`、`forName`、`getClassLoader`、`setAccessible`、`invoke`。
- 禁止访问危险属性，例如 `class`、`classLoader`、`metaClass`、`module`、`protectionDomain`。

不建议写法：

- 不要保存全局状态。
- 不要依赖随机数作为核心判断。
- 不要选择日志、序列化、集合、线程池、JDK 内部类等高频公共方法。
- 不要在脚本中吞掉所有异常后返回假成功，除非这正是要验证的降级场景。
- 不要把复杂业务逻辑搬进脚本。脚本超过 30 行时优先拆场景。

## 6. 经典场景 Demo

### 6.1 验证规则命中但不改变行为

阶段：`BEFORE`

```groovy
log.info("Runtime Mock rule hit: " + method.name())
return mock.proceed()
```

用途：验证目标方法、发布计划和 Agent 生效链路。

### 6.2 模拟下游不可用

阶段：`BEFORE`

```groovy
return mock.throwException(
    "java.lang.IllegalStateException",
    "order dependency unavailable"
)
```

用途：验证调用方是否正确处理运行时异常。

### 6.3 指定用户失败

阶段：`BEFORE`

```groovy
def request = args[0]
def userId = mock.get(request, "userId")

if (userId == "u-001") {
    log.warn("inject failure for userId=" + userId)
    return mock.throwException(
        "java.lang.IllegalStateException",
        "user u-001 blocked by Runtime Mock"
    )
}

return mock.proceed()
```

用途：只影响一小部分测试请求，避免误伤所有流量。

### 6.4 金额超过阈值失败

阶段：`BEFORE`

```groovy
def amount = mock.get(args[0], "amount")

if (amount != null && amount > 1000) {
    return mock.throwException(
        "java.lang.IllegalStateException",
        "large amount blocked by Runtime Mock"
    )
}

return mock.proceed()
```

用途：验证金额、库存、额度等边界逻辑。

### 6.5 跳过真实发送短信或通知

阶段：`BEFORE`

```groovy
log.warn("skip notification method by Runtime Mock")
return mock.returnValue(null)
```

目标方法必须是 `void`，或返回类型可以接收 `null`。

### 6.6 返回固定字符串

阶段：`BEFORE`

```groovy
return mock.returnValue("MOCKED")
```

适用于目标方法返回 `String`。

### 6.7 返回固定数字

阶段：`BEFORE`

```groovy
return mock.returnValue(503)
```

适用于目标方法返回 `int`、`Integer`、`long`、`Long` 等可转换数字类型。

### 6.8 返回 DTO JSON

阶段：`BEFORE`

```groovy
return mock.returnJson('''
{
  "id": "mock-order-001",
  "userId": "u-001",
  "amount": 12.34,
  "status": "MOCKED",
  "message": "returned by Runtime Mock"
}
''')
```

用途：模拟下游返回特殊对象。

### 6.9 修改原返回值字段

阶段：`RETURN`

```groovy
mock.set(result, "status", "PROCESSING")
mock.set(result, "message", "changed after original method returned")

return mock.returnValue(result)
```

用途：原业务逻辑正常执行，但篡改返回状态验证上游处理。

### 6.10 按原返回值条件改写

阶段：`RETURN`

```groovy
def status = mock.get(result, "status")
def amount = mock.get(result, "amount")

if (status == "SUCCESS" && amount != null && amount > 1000) {
    mock.set(result, "status", "REVIEWING")
    mock.set(result, "message", "large order moved to review")
    return mock.returnValue(result)
}

return mock.proceed()
```

### 6.11 把成功返回改成异常

阶段：`RETURN`

```groovy
def status = mock.get(result, "status")

if (status == "SUCCESS") {
    return mock.throwException(
        "java.lang.IllegalStateException",
        "success result converted to failure"
    )
}

return mock.proceed()
```

用途：验证调用方对“后置处理失败”的容错。

### 6.12 异常降级为正常返回

阶段：`THROWS`

```groovy
log.warn("original exception: " + throwable.getMessage())

return mock.returnJson('''
{
  "id": "fallback-order",
  "status": "FALLBACK",
  "message": "fallback by Runtime Mock"
}
''')
```

用途：验证异常降级链路。

### 6.13 替换原始异常

阶段：`THROWS`

```groovy
log.warn("replace exception: " + throwable.getMessage())

return mock.throwException(
    "java.lang.IllegalStateException",
    "exception replaced by Runtime Mock"
)
```

用途：隐藏底层异常类型，验证调用方对统一异常的处理。

## 7. 复杂场景 Demo

### 7.1 多条件组合：VIP 放行，高金额审核，风险渠道失败

阶段：`BEFORE`

```groovy
def request = args[0]
def userId = mock.get(request, "userId")
def amount = mock.get(request, "amount")
def channel = mock.get(request, "channel")

log.info("check order userId=" + userId + ", amount=" + amount + ", channel=" + channel)

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

### 7.2 租户灰度：只影响指定租户和环境标记

阶段：`BEFORE`

```groovy
def request = args[0]
def tenantId = mock.get(request, "tenantId")
def experiment = mock.get(request, "tags.experiment")

if (tenantId == "tenant-a" && experiment == "fault-drill") {
    log.warn("tenant-a fault drill matched")
    return mock.throwException(
        "java.lang.IllegalStateException",
        "tenant-a drill failure"
    )
}

return mock.proceed()
```

适合内部演练。条件必须足够窄，避免影响普通测试流量。

### 7.3 改写嵌套入参后继续执行

阶段：`BEFORE`

```groovy
def request = args[0]
def originalCity = mock.get(request, "address.city")

if (originalCity == "Shanghai") {
    mock.set(request, "address.city", "Hangzhou")
    mock.set(request, "audit.remark", "city rewritten by Runtime Mock")
    log.info("rewrite city from Shanghai to Hangzhou")
    return mock.proceed(args)
}

return mock.proceed()
```

注意：改写入参有副作用，如果调用方后续还持有同一个对象，也会看到被改过的字段。

### 7.4 使用返回类型创建对象

阶段：`BEFORE`

```groovy
def value = mock.newReturnObject()

mock.set(value, "id", "mock-order-002")
mock.set(value, "status", "CREATED")
mock.set(value, "message", "created from return type")

return mock.returnValue(value)
```

目标返回类型需要有可用的无参构造和可写字段或 setter。

### 7.5 从 JSON 构造指定嵌套类型

阶段：`BEFORE`

```groovy
def response = mock.fromJson('''
{
  "id": "mock-payment-001",
  "status": "TIMEOUT",
  "detail": {
    "provider": "mock-pay",
    "reason": "simulated timeout"
  }
}
''', method.returnType())

return mock.returnValue(response)
```

适合返回对象结构较深的场景。

### 7.6 返回后把成功对象改成部分失败

阶段：`RETURN`

```groovy
def status = mock.get(result, "status")
def provider = mock.get(result, "provider")

if (status == "SUCCESS" && provider == "inventory") {
    mock.set(result, "status", "PARTIAL_FAILED")
    mock.set(result, "message", "inventory branch failed by Runtime Mock")
    return mock.returnValue(result)
}

return mock.proceed()
```

### 7.7 异常按类型降级

阶段：`THROWS`

```groovy
log.warn("caught exception: " + throwable.getMessage())

if (mock.isType(throwable, "java.lang.IllegalStateException")) {
    return mock.returnJson('''
    {
      "status": "FALLBACK",
      "message": "illegal state fallback"
    }
    ''')
}

return mock.proceed()
```

`mock.proceed()` 在 `THROWS` 阶段表示保留原始异常。

### 7.8 checked exception 注入

阶段：`BEFORE`

```groovy
return mock.throwException(
    "java.io.IOException",
    "declared checked exception injected"
)
```

只有当目标方法声明了 `throws IOException` 或其父类型时才允许。否则校验或运行时会拒绝该决策。通常建议优先使用运行时异常。

## 8. 类型和阶段检查清单

保存和发布前逐项检查：

- 脚本是否所有分支都 `return mock.*`。
- 所选阶段是否正确。
- `BEFORE` 阶段不要读取 `result` 或 `throwable`。
- `RETURN` 阶段读取 `result` 前先确认目标方法不是 `void`。
- `THROWS` 阶段读取 `throwable` 前确认要处理原始异常。
- `mock.proceed(args)` 的参数数量是否和目标方法一致。
- `mock.returnValue(...)` 的值是否能赋给目标返回类型。
- `mock.returnJson(...)` 的 JSON 字段和类型是否匹配目标 DTO。
- checked exception 是否在目标方法声明中。
- 条件不命中时是否保持原行为。
- 日志和异常消息是否能帮助排查。

## 9. 常见错误

### 9.1 忘记返回决策

错误：

```groovy
mock.throwException("java.lang.IllegalStateException", "failed")
```

正确：

```groovy
return mock.throwException("java.lang.IllegalStateException", "failed")
```

### 9.2 返回类型不匹配

错误：

```groovy
return mock.returnValue("503")
```

如果目标方法返回 `int`，应写：

```groovy
return mock.returnValue(503)
```

### 9.3 void 方法返回非空值

错误：

```groovy
return mock.returnValue("OK")
```

如果目标方法返回 `void`，应写：

```groovy
return mock.returnValue(null)
```

### 9.4 `log.error` 参数错误

错误：

```groovy
log.error("failed")
```

正确：

```groovy
log.error("failed", throwable)
```

没有异常对象时：

```groovy
log.warn("failed")
```

### 9.5 使用禁用能力

错误：

```groovy
while (true) {
}
```

错误：

```groovy
new File("/tmp/a.txt").text
```

错误：

```groovy
Runtime.getRuntime().exec("date")
```

这些写法会被安全校验拒绝。

## 10. 排查清单

没命中：

- 确认应用、环境、目标类、方法名、JVM 描述符和类加载器 ID 都来自在线 Agent。
- 确认调用的是同一个环境和同一个实例。
- 确认目标方法实际被业务请求调用。

保存失败：

- 看校验诊断。
- 确认脚本有 `return mock.*`。
- 检查 JSON 是否合法。
- 检查是否使用了禁用 import、循环、反射、线程、文件、网络或进程操作。

发布后无效果：

- 确认发布计划成功。
- 确认实例执行成功。
- 确认目标实例在线。
- 确认脚本阶段正确。

运行时报类型错误：

- 检查返回值和目标方法返回类型。
- 检查 `mock.proceed(args)` 参数数量和类型。
- 检查 checked exception 是否被目标方法声明。

卸载后仍有效：

- 确认卸载记录成功。
- 如果实例离线，等待实例恢复在线后重新卸载。
- 确认没有另一个启用规则仍绑定同一方法。
