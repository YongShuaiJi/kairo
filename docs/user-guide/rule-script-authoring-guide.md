# Runtime Mock 规则脚本编写手册

规则脚本使用 Groovy 编写，但运行在受限沙箱中。每个脚本必须返回一个 `MockDecision`，也就是 `mock.proceed()`、`mock.returnValue(...)` 或 `mock.throwException(...)` 之一。

## 脚本入口变量

- `args`：当前方法参数数组，等价于 `ctx.arguments()`。
- `result`：原始返回值，仅在“正常返回后”阶段有意义。
- `throwable`：原始异常，仅在“抛出异常时”阶段有意义。
- `ctx`：调用上下文，可读取阶段、目标对象、方法元数据和日志。
- `mock`：决策 API，用于继续执行、返回指定值或抛出异常。

## 执行阶段

- 调用前 `BEFORE`：方法执行前进入脚本。适合直接失败、改写参数、短路返回。
- 正常返回后 `RETURN`：原方法正常返回后进入脚本。适合修改返回值或按原结果做条件判断。
- 抛出异常时 `THROWS`：原方法抛出异常后进入脚本。适合替换异常或把异常降级为正常返回。

## 基础模板

继续执行原方法：

```groovy
return mock.proceed()
```

调用前直接抛异常：

```groovy
return mock.throwException(
    "java.lang.IllegalStateException",
    "Runtime Mock injected failure"
)
```

按参数条件注入异常：

```groovy
def request = args[0]
def amount = mock.get(request, "amount")

if (amount != null && amount > 1000) {
    return mock.throwException("java.lang.IllegalStateException", "amount blocked by Runtime Mock")
}

return mock.proceed()
```

改写参数后继续执行：

```groovy
def request = args[0]
mock.set(request, "userId", "mock-user")

return mock.proceed(args)
```

返回固定字符串：

```groovy
return mock.returnValue("mocked-value")
```

返回 JSON 对象：

```groovy
return mock.returnJson('{"status":"MOCKED","message":"injected by Runtime Mock"}')
```

基于原返回值修改字段：

```groovy
mock.set(result, "status", "MOCKED")
mock.set(result, "message", "changed after original method returned")

return mock.returnValue(result)
```

异常降级：

```groovy
ctx.log().warn("original exception: " + throwable.getMessage())

return mock.returnJson('{"status":"FALLBACK","message":"fallback by Runtime Mock"}')
```

## 常用 API

`mock.proceed()`
继续执行原方法。

`mock.proceed(args)`
使用新的参数数组继续执行原方法。

`mock.returnValue(value)`
跳过或替换原方法返回值。返回对象类型必须能被目标方法返回类型接收。

`mock.returnJson(json)`
用 JSON 构造返回值，适合返回对象结构清晰的业务 DTO。

`mock.throwException(className, message)`
抛出指定异常。异常类应是目标应用 ClassLoader 能加载的异常，通用异常可用 `java.lang.IllegalStateException`。

`mock.get(object, "a.b.c")`
读取对象属性，避免在脚本里写反射。

`mock.set(object, "a.b.c", value)`
写入对象属性。

`mock.isType(object, className)`
判断对象运行时类型。

`ctx.log().info/warn/debug/error(...)`
输出脚本日志，便于排查规则是否命中。

## 编写规范

- 每个脚本必须显式 `return` 一个 `mock.*` 决策。
- 默认骨架使用 `return mock.proceed()`，不要先写破坏性逻辑。
- 条件判断尽量基于业务参数，例如订单金额、用户 ID、渠道、租户。
- 返回值类型必须匹配目标方法返回类型；`int` 方法不要返回字符串，DTO 方法不要返回无关结构。
- 故障注入脚本尽量短，优先用 10 到 30 行表达一个场景。
- 发布前必须先校验；保存后再创建发布计划。

## 禁止写法

- 不要写无限循环、线程、睡眠、文件、网络、进程、系统属性等操作。
- 不要选择框架高频方法作为目标，例如日志、序列化、集合、线程池内部方法。
- 不要在脚本中保存全局状态。脚本应只根据当前调用上下文做决策。
- 不要吞掉所有异常后返回假成功，除非这正是要验证的降级场景。

## 场景示例

下单接口失败：

```groovy
return mock.throwException("java.lang.IllegalStateException", "order service unavailable")
```

指定用户失败：

```groovy
def request = args[0]
def userId = mock.get(request, "userId")

if (userId == "u-001") {
    return mock.throwException("java.lang.IllegalStateException", "user blocked")
}

return mock.proceed()
```

订单状态改成处理中：

```groovy
mock.set(result, "status", "PROCESSING")
mock.set(result, "message", "changed by Runtime Mock")

return mock.returnValue(result)
```

分数接口返回固定值：

```groovy
return mock.returnValue(503)
```

通知接口跳过真实发送：

```groovy
ctx.log().info("skip notification by Runtime Mock")
return mock.returnValue(null)
```

## 排查清单

- 没命中：确认应用、环境、目标类、方法名、JVM 描述符和类加载器 ID 都来自在线 Agent。
- 保存失败：先看校验诊断，确认脚本有 `return mock.*`。
- 发布后无效果：确认发布计划已进入成功状态，目标实例在线，调用的是同一个环境的业务接口。
- 卸载后仍有效：确认卸载记录成功；如果实例曾离线，等实例恢复在线后再重新卸载。
