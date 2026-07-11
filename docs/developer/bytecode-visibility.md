# Kairo 字节码可视化底座（V1.1 第一阶段）

本文面向在目标 JVM 内二次开发或排查 Kairo 增强行为的开发者，描述 V1.1 第一阶段落地的
“字节码事实来源”底座：公共契约 DTO、快照仓库、转换日志、转换计划、只读预览/捕获/Diff 服务
以及反编译 SPI。对应路线图见 [v1.1-bytecode-visibility](../roadmap/v1.x-technical/v1.1-bytecode-visibility.md)。

本阶段只完成 Agent 内底座，不含 Platform、Web、数据库与公开 HTTP API，它们属于下一阶段。

## 1. 设计要点

- **三类字节码**：每次转换明确区分 `INPUT`（Kairo 本次收到的输入）、`PLANNED`（只读预览的预计输出）、
  `APPLIED`（转换后从 JVM 重新读取的实际字节码）。不使用“原始字节码”这一歧义概念，因为其他
  Agent 的 Transformer 顺序会让它产生歧义。
- **单调修订号**：每个目标类在 Agent 内有自己的单调 `TransformationRevision`，由
  `TransformationJournal` 分配；只读预览和捕获不推进修订号。
- **有界、可清理、不强引用**：快照仓库按条数和字节数有界，支持 TTL 与 LRU 清理，键和值只持有
  值类型，从不强引用 `Class` 或 `ClassLoader`。
- **正式应用与只读预览复用同一计划**：`TransformationPlan` 封装规则解析、方法匹配与 Advice 选择，
  正式转换与离线预览都调用同一个 `plan.apply(builder)`。
- **业务线程不执行重活**：预览、捕获、Diff、反编译均由控制/诊断路径调用，不进入 Advice 调用链。

## 2. 冻结契约 DTO

全部位于 `kairo-api` 的 `com.example.kairo.api.bytecode` 包，纯数据类型，不依赖 Byte Buddy、ASM
或反射 `Class`，因此 Platform 可直接引用而不会引入字节码实现依赖。

| 类型 | 说明 |
| --- | --- |
| `ClassIdentity` | `binaryClassName + classLoaderId`，相等性只看这两个字段；`classLoaderId` 由 `kairo-core` 的 `ClassLoaderIdentity` 生成，本类不复算，全局只有一套类加载器身份算法。 |
| `BytecodeSnapshotKind` | `INPUT` / `PLANNED` / `APPLIED`。 |
| `BytecodeSnapshotMetadata` | 快照元数据：身份、修订号、kind、SHA-256 哈希、字节数、采集时间、来源、描述。原始 bytes 留在 Agent，默认只交换元数据与哈希。 |
| `TransformationRevision` | 单调修订号（`long`），`INITIAL` 为 0，`next()` 递增。 |
| `TransformationStatus` | `STARTED` / `SUCCEEDED` / `FAILED` / `VERIFIED` / `RECOVERED` / `SKIPPED`。 |
| `TransformationDiagnostic` | 结构化诊断：`Severity`、稳定 `code`、`message`、可空 `exceptionClassName` 与 `detail`。 |
| `TransformationResult` | 单类一次转换的结构化结果：身份、修订号、状态、输入/输出哈希、诊断列表、起始时间、耗时。 |
| `BytecodeDiffResult` | 两份快照的结构化差异：`from/to` 修订号与 kind、哈希、`identical`、`normalized`、逐方法 `MethodDiff`、结构差异列表、摘要。 |

`ClassIdentity` 的 `classLoaderId` 始终通过 `kairo-agent-core` 的 `ClassIdentities.of(Class)` 构造，
该方法委托 `ClassLoaderIdentity.idOf`，禁止在别处重新发明类加载器身份算法。

## 3. Agent 内组件（`kairo-agent-core`）

### 3.1 快照仓库 `BytecodeSnapshotRepository`

- 按 `BytecodeSnapshotKey(identity, revision, kind)` 索引；键只含值类型。
- 同时按条数（`maxEntries`）和字节总量（`maxBytes`）有界，超容量时按 LRU 访问时间淘汰，平手按
  `BytecodeSnapshotKey` 顺序打破；TTL 到期条目在访问时惰性清理，也可由 `evictExpired()` 主动清理。
- 存入与读取都做防御性拷贝；单个条目超过 `maxBytes` 直接拒绝。
- `AgentRuntime` 默认配置 256 条、8 MB、30 分钟 TTL，并在清理线程上每 5 秒调用一次 `evictExpired()`。

### 3.2 转换日志 `TransformationJournal`

- 为每个 `ClassIdentity` 维护一个 `AtomicLong` 修订号计数器，`nextRevision` 首次返回 `r1`，
  线程安全且不重复；清理历史不重置计数器。
- 追加式记录 `TransformationResult`：`recordStart/recordSuccess/recordFailure/recordVerification/
  recordRecovery/recordSkip`，分别对应开始、成功、失败、验证、恢复、跳过。
- 每类与全局各有界 FIFO 历史（默认每类 64、全局 4096），返回不可变防御性拷贝。

### 3.3 转换计划 `TransformationPlan`

- 从 `KairoTransformer` 抽出：规则解析（`InstrumentationRegistry.methodsOf`）、方法匹配
  （`MethodMatchers`）、Advice 选择（`VoidMethodAdvice` / `ValueMethodAdvice`）。
- `apply(DynamicType.Builder)` 同时被正式转换（AgentBuilder 提供的 builder）与只读预览
  （离线 `ByteBuddy.redefine` builder）调用，保证两边织入逻辑一致。
- 暴露 `targetMethodCount()`、`adviceTypes()`、`methods()` 等元数据供结果与诊断使用。
- `KairoTransformer` 现在只是薄适配器，V1.0 织入行为不变。

### 3.4 `ByteBuddyTransformerManager` 改造

在保留 V1.0 织入行为（同样的 `KairoTransformer`、忽略规则、Retransformation 策略与 reset 语义）的前提下：

- 安装一个位于 Kairo 之前的 pass-through `InputCaptureTransformer`，记录 Kairo 本次收到的 `INPUT`
  字节并开启日志条目；返回 `null` 不改变字节。
- 通过 `AgentBuilder.Listener` 记录输出哈希、关闭日志条目，并产出逐类 `TransformationResult`。
- 每次真实转换经 `TransformationJournal.nextRevision` 推进单调修订号；只读预览与捕获不推进。
- `retransform(Class<?>...)` 由 `void` 改为返回 `List<TransformationResult>`（对忽略返回值的调用方
  源兼容），不再只返回 void。
- `captureApplied(Class)` 通过短生命周期捕获 Transformer 重新读取 JVM 中实际运行的字节码，不覆盖
  其他 Transformer 的输出，也不把任何字节码当作“原始字节码”。
- 记录受 `Mode` 三态门控：`IDLE`（初始加载静默织入，不记录）、`RETRANSFORM`（记录 INPUT/OUTPUT）、
  `CAPTURE`（织入但不记录，由捕获 Transformer 记录）。
- `close()` 移除 Transformer、重置受影响类并在日志中记录 `RECOVERED`。

### 3.5 只读服务

- `TransformationPreviewService.preview(identity, inputBytes)`：用同一 `TransformationPlan` 对输入
  字节做离线 `ByteBuddy.redefine`，产出 `PLANNED` 字节，从不调用 `retransformClasses`、从不推进
  修订号。可写入 `PLANNED` 快照供后续 Diff。
- `BytecodeCaptureService.capture(clazz)`：调用 `manager.captureApplied` 重新读取 JVM 实际字节码，
  写入 `APPLIED` 快照，并与最近一次 `SUCCEEDED` 的输出哈希比对验证（不一致时给出
  `APPLIED_DIFFERS_FROM_OUTPUT` 诊断，提示 Kairo 之后可能有其他 Transformer 运行）。
- `BytecodeDiffService`（`bytecode.diff` 子包）：用 Byte Buddy 自带的 shaded ASM 核心 visitor API
  将两份字节码归一化为 `NormalizedClass`，再做结构差异与逐方法指令级 LCS Diff。归一化忽略常量池
  下标、栈映射帧与调试信息，保留指令、描述符、异常表、签名与注解，因此语义相等的两份字节码即使
  原始 bytes 不同也会比较为 `identical`。这是权威比较，真实可用，不是占位。
- `DecompilerService` + `BytecodeDecompiler` SPI：反编译器运行在独立有界诊断线程池，带超时与大小
  限制。默认实现 `UnavailableBytecodeDecompiler` 明确不可用并返回清晰诊断；结构化字节码 Diff 不依赖
  反编译器，始终可用。

`InstrumentationRegistry` 新增按 `classLoaderId` 查询的 `methodsOf` / `containsType` 重载，使只读预览
无需 `ClassLoader` 对象即可复用同一计划。

## 4. 使用方式

```java
AgentRuntime runtime = new AgentRuntime(instrumentation);
runtime.start();

ClassIdentity identity = ClassIdentities.of(OrderService.class);
byte[] baseline = runtime.captureService().capture(OrderService.class).appliedBytes();

// 只读预览：不触碰 JVM
var preview = runtime.previewService().preview(identity, baseline);
if (preview.changed()) {
    runtime.snapshotRepository().metadataFor(identity); // 含 PLANNED 快照
}

// 正式增强后捕获实际字节码
runtime.publish(method, rule, actor);
var applied = runtime.captureService().capture(OrderService.class);

// 权威结构化 Diff
BytecodeDiffResult diff = runtime.diffService().diff(identity,
        baseline, preview.revision(), BytecodeSnapshotKind.INPUT,
        applied.appliedBytes(), applied.revision(), BytecodeSnapshotKind.APPLIED);

// 转换历史
List<TransformationResult> history = runtime.transformationJournal().history(identity);
```

## 5. 约束

- 预览、捕获、Diff、反编译不得在业务线程执行；它们面向控制/诊断路径。
- 快照仓库有界且可清理；导出或持久化 class bytes 须显式调用并受大小/数量/保留时间上限约束。
- 快照不强引用 `Class` 或 `ClassLoader`，仅通过稳定 `ClassIdentity` 定位。
- 不引入许可不兼容依赖；字节码归一化优先复用 Byte Buddy 自带的 shaded ASM 能力（`net.bytebuddy.jar.asm`）。

## 6. 本阶段不做

Platform 代理服务、转换元数据表、blob 持久化、Web 增强对比视图与数据库迁移均属于后续阶段，本阶段不实现。

> Agent 本地 HTTP API（`/v1/classes/...` 的 transformations/bytecode/preview/capture/diff 五类路由）已在 V1.1
> 第三阶段第一小块落地，见下文第 7 节。浏览器直连 Agent 的限制由 Platform 代理服务在后续阶段实现，本阶段
> Agent 端沿用现有 `X-Agent-Token` 认证。

## 7. Agent 本地 HTTP API（第三阶段第一小块）

`kairo-agent-server` 的 `AgentHttpServer` 在现有 token 认证之上新增五类只读 / 诊断路由，路径与
[路线图 3.3](../roadmap/v1.x-technical/v1.1-bytecode-visibility.md) 一致。路由实现集中在 `BytecodeRoutes`，
慢速 preview/capture/diff 派发到有界 `BytecodeDiagnosticExecutor`（守护线程、最低优先级、带超时），不占用业务线程
或 HTTP 线程池的并发槽位之外的计算资源。

### 7.1 通用约定

- **认证**：除 `/health` 等公开路径外，所有路由复用现有 `X-Agent-Token`（或 `Authorization: Bearer ...`）。
  未带 token 或 token 失效返回 `401`。浏览器不得直连的限制由 Platform 代理在后续阶段实现。
- **classId**：沿用 `LoadedClassRepository.classId` 的 base64url(`classLoaderId|binaryClassName`) 形式，
  无歧义定位 `binaryClassName + classLoaderId`，**不接受裸类名**。格式非法返回 `400`；capture 需要的类未加载
  返回 `404`；bytecode/diff 需要的快照缺失返回 `404`。`classLoaderId` 一律由 `ClassLoaderIdentity.idOf` 生成。
- **媒体类型**：字节码二进制使用 `application/octet-stream`（并附 `X-Content-Type-Options: nosniff`）；
  元数据、Diff、预览/捕获结果使用现有 Jackson 序列化的 JSON（`application/json; charset=utf-8`）。
- **大小 / 超时上限**（`BytecodeApiLimits.STANDARD`，可构造时覆盖用于测试）：
  请求体上限 1 MiB；字节码响应上限 8 MiB；诊断超时 10 s；诊断并发 2。超限返回 `413`，超时返回 `503`。
- **错误体**：结构化 JSON `{"error": "<code>", "message": "<可读信息>"}`，内部错误不泄露堆栈或异常类名。
  错误码：`bad_request`(400)、`not_found`(404)、`payload_too_large`(413)、`diagnostic_timeout`(503)、
  `diagnostic_failed`/`internal_error`(500)。

### 7.2 路由

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/v1/classes/{classId}/transformations` | 当前 revision + 有界 journal 历史（JSON） |
| `GET` | `/v1/classes/{classId}/bytecode?kind=&revision=` | 快照原始 bytes（octet-stream，支持 `HEAD`） |
| `POST` | `/v1/classes/{classId}/preview` | 对请求体中的 input bytes 做离线预览（JSON） |
| `POST` | `/v1/classes/{classId}/capture` | 重新读取 JVM 实际运行字节码（JSON） |
| `GET` | `/v1/classes/{classId}/diff?from=&to=&format=` | 结构化归一化字节码 Diff（JSON / text） |

- `kind` ∈ `INPUT|PLANNED|APPLIED`（大小写不敏感）；`revision` 为非负整数。
- `from`/`to` 为快照选择器 `KIND@revision`，例如 `INPUT@1`、`PLANNED@1`。
- `format` ∈ `json`（默认）| `text`。

### 7.3 示例

```bash
# 0. 取 classId（先通过类发现接口，或由发布规则回执获得）。假设 classId=<CID>。
TOKEN=...

# 1. 转换历史
curl -s -H "X-Agent-Token: $TOKEN" \
  "http://127.0.0.1:18080/v1/classes/<CID>/transformations"
# {"classIdentity":{"binaryClassName":"com.example.bytecode.SampleService","classLoaderId":"..."},
#  "currentRevision":{"value":1},"count":2,
#  "history":[{"classIdentity":{...},"revision":{"value":1},"status":"SUCCEEDED",
#             "inputHash":"...","outputHash":"...",...}]}

# 2. 取 INPUT 字节码（二进制）
curl -s -H "X-Agent-Token: $TOKEN" -o /tmp/in.class \
  "http://127.0.0.1:18080/v1/classes/<CID>/bytecode?kind=INPUT&revision=1"
# 响应头: Content-Type: application/octet-stream
#         X-Kairo-Kind: INPUT  X-Kairo-Revision: 1  X-Kairo-Hash: <sha256>  X-Kairo-Size: <n>

# 3. 预览：把上一步取到的 input bytes 喂给 preview，得到 PLANNED 结果（不触碰 JVM）
curl -s -H "X-Agent-Token: $TOKEN" -H "Content-Type: application/octet-stream" \
  --data-binary @/tmp/in.class \
  "http://127.0.0.1:18080/v1/classes/<CID>/preview"
# {"classIdentity":{...},"revision":{"value":1},"inputHash":"...","plannedHash":"...",
#  "plannedSizeBytes":1234,"targetMethodCount":1,"adviceTypes":["VALUE"],
#  "diagnostics":[],"changed":true}
# 预览生成的 PLANNED 快照随后可用 GET .../bytecode?kind=PLANNED&revision=1 取回。

# 4. 捕获 JVM 实际运行字节码
curl -s -X POST -H "X-Agent-Token: $TOKEN" \
  "http://127.0.0.1:18080/v1/classes/<CID>/capture"
# {"classIdentity":{...},"revision":{"value":1},"appliedHash":"...","sizeBytes":1234,
#  "diagnostics":[],"capturedAtMillis":...,"captured":true}

# 5. 结构化 Diff（INPUT -> PLANNED），文本格式
curl -s -H "X-Agent-Token: $TOKEN" \
  "http://127.0.0.1:18080/v1/classes/<CID>/diff?from=INPUT@1&to=PLANNED@1&format=text"
# # bytecode diff for com.example.bytecode.SampleService
# from: INPUT@1 (...)   to: PLANNED@1 (...)
# identical: false (normalized: true)
# ## methods
# ### compute(I)I [MODIFIED]
#   - ILOAD 0
#   + INVOKESTATIC com/example/kairo/bridge/KairoBridge ...
```

### 7.4 跨 ClassLoader 隔离

`classId` 包含 `classLoaderId`，因此两个 ClassLoader 加载的同名类拥有不同 `classId`，其 revision、journal 历史
与快照互不串数据。`GET .../transformations` 与 `GET .../bytecode` 仅按 `{classLoaderId, binaryClassName}` 命中，
绝不会把 A 类加载器的快照暴露给 B 类加载器的 classId。

### 7.5 本小片不做

Platform 代理服务（浏览器不直连 Agent）、blob 持久化和 Web 增强对比视图属于后续小片。
反编译器接入（`DecompilerService` 已就绪但未挂 HTTP 路由）同样留给后续小片。

## 8. Platform 元数据持久化

`V35__bytecode_transformation_metadata.sql` 保存有界的转换元数据，唯一键由 runtime、Agent、二进制类名、
ClassLoader identity、revision 和 snapshot kind 组成，重复观测采用幂等 upsert。该表刻意不存在 byte array、
BLOB、BYTEA 或 payload 列；INPUT、PLANNED、APPLIED 字节仍留在 Agent 的有界快照仓库，只能按需获取。
诊断 JSON 由服务限制长度，且不得包含 class bytes 或 Agent token。
