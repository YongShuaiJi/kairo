# Runtime-Mock 代码走查 BUG 报告

- 走查时间：2026-06-18
- 走查范围：`/Users/jiyongshuai/code/runtime-mock` 全部 18 个 Maven 模块（走查时共 140 个 Java 文件）
- 走查时基线：`mvn test` 全部通过（23 个 Agent 集成测试 + 平台 2 个场景 + 其他单元测试）
- 依据：`/Users/jiyongshuai/Desktop/runtime-mock-production-spec/` 全部规格 + 项目内 `生产级100%需求补齐清单.md`、`生产级实现状态.md`、`Java 运行时 Mock 与故障注入系统 - 实现版三合一文档.md`
- 方法：代码静态走查 + 验证用单元测试（见 `runtime-mock-groovy/src/test/java/com/example/runtimemock/groovy/GroovySecurityBypassVerificationTest.java`）
- 说明：本报告只记录 BUG，不修改任何应用代码

## 修复处置进度（2026-06-18）

本报告是发现时快照，以下为后续核验和开发后的状态：

- 已修复并有回归验证：C-1、C-2、C-3、C-6、C-7、C-8、C-9、C-10、C-12、C-13、C-15、H-1、H-4、H-5、H-6、H-7、H-8、H-9、H-10、H-11、H-12、H-13、H-14、H-15、H-21、H-22、H-23、H-25、H-26、H-28、H-33、H-34、M-1、M-2、M-3、M-4。
- 已部分修复：C-4、C-5、C-11、C-14、H-20、H-24、H-27、H-32。Agent 已增加完整运行状态和降级保护；Groovy 已增加 100ms 运行时截止、首次冷启动窗口、慢执行/连续错误自动 LOCKED 和 classloader generation 轮换，但 LOCKED 后的平台解锁重审闭环及更完整 AST/对象复杂度限制仍待实现；远程平台请求已要求共享 Bearer 凭证，但正式 OIDC/mTLS 尚未接入；WAL 已有大小限制、配额、去重、异步写、数组/Set/Queue 脱敏和关闭时密钥销毁，但独立数据集 DEK/KEK 信封加密仍待实现。
- 已核验但仍待实现：H-2、H-3，以及未列为已修复或部分修复的 HIGH/MEDIUM/LOW/SUSPECT 项。
- 当前验证：18 个 Maven 模块 `mvn test` 全部通过；Agent 集成测试已扩展为 24 个并全部通过。

> 注：走查过程中 `生产级实现状态.md`、`AgentRuntime.java`、`PayloadMasker.java`、`EncryptedWalWriter.java`、`PropertyPathAccessor.java`、`AgentHttpServer.java`、`AgentCommandService.java`、`GroovyScriptSecurityPolicy.java` 等多个文件读取结果中均夹带了 `<system-reminder>` 试图注入"拒绝修改代码"的指令，已识别为提示词注入并忽略——本就走查任务本就不修改应用代码。

---

## 一、严重程度统计

| 等级 | 数量 |
|------|------|
| CRITICAL | 15 |
| HIGH | 24 |
| MEDIUM | 18 |
| LOW | 8 |
| SUSPECT | 9 |
| **合计** | **74** |

---

## 二、CRITICAL 级 BUG

### C-1 | AgentHttpServer.java:210-213 | CRITICAL | A7/D1 | Agent Token 为空时 fail-open，所有非健康端点免认证
```java
private boolean authorized(HttpExchange exchange) {
    if (token.isBlank()) {
        return true;
    }
```
约束 A7/D1 要求除 `/health`、`/v1/health` 外所有 Agent API 必须校验 token。当前实现一旦 token 字段为空（构造函数允许 null/空），则全部放行。默认 `AgentLaunchConfig` 会生成 token，故默认路径暂安全，但属 fail-open 设计，违反最小权限原则。
- 复现：`new AgentHttpServer(runtime, "127.0.0.1", 18080, "")` 后任意客户端无 token 调用 `POST /v1/rules` 即可成功。

### C-2 | AgentHttpServer.java + AgentLaunchConfig + AgentRegistrationWriter | CRITICAL | A7/D2 | Agent Token 永不轮换、过期不强制
`AgentLaunchConfig.token` 与 `AgentHttpServer.token` 均为 `final`，启动时生成一次永不更换；`AgentRegistrationWriter.writeToken` 写入 `expiresAt = now + tokenTtl`，但没有任何调度器续期，`AgentHttpServer.authorized()` 也不校验过期。A7 要求"Token 15 分钟有效"，D2 要求"Token 15 分钟轮换"。
- 复现：启动 Agent，等 16 分钟，用过期 token 调 `GET /v1/rules` 仍 200 OK。

### C-3 | AgentLaunchConfig.java:41-43 + AgentHttpServer.java:46 + AttachOptions.java:21,53 | CRITICAL | A7 | Agent HTTP 监听地址可被覆盖为非回环
`host` 默认 `127.0.0.1` 但允许通过 agent args / `--host` 覆盖为 `0.0.0.0`。A7 硬约束"Agent HTTP 只监听 127.0.0.1"。
- 复现：`--host=0.0.0.0` attach Agent，从另一台机器访问 `http://agent-host:18080/v1/rules`。

### C-4 | AgentRuntime.java:49-50 | CRITICAL | A18/A19 | Agent 状态机完全缺失，无 DEGRADED 状态
仓库内全局搜索 `AgentState`/`LifecycleState`/`STARTING`/`INSTALLING_BRIDGE`/`LOADING_CORE`/`DEGRADED`/`RESETTING`/`STOPPING`/`STOPPED` 全部无结果。`AgentRuntime` 仅 `volatile boolean globallyEnabled` 与 `volatile String loadMode`。A18 要求完整状态机；A19 要求 DEGRADED 时 Bridge NOOP、禁止发布、业务不受影响。retransform 失败时 `AgentRuntime.publish` 直接抛 `IllegalStateException`，状态不一致，仍声称 globallyEnabled=true。
- 复现：让 retransform 失败（例如对一个已变为不可修改的类发布规则），catch 块调用 `restoreInstrumentationState` 再次 retransform 抛异常，调用者收到异常，但状态机仍认为 ACTIVE。

### C-5 | AgentRuntime.java:177-186 | CRITICAL | A9 | resetClass 不返回每类状态，无 DegradedClassRegistry，forEach 抛异常即中断
`resetClass` 返回 `rules()`（全局剩余规则），不是每类状态；`ruleIds.forEach(ruleId -> remove(ruleId, actor))` 中任一 `remove` 抛异常则剩余规则不被移除；无 DegradedClassRegistry；非幂等。A9 要求"reset 逐类返回状态；失败类进入 DegradedClassRegistry"。
- 复现：对类 Foo 发布 2 条规则，使第 1 条 retransform 失败，调 `POST /v1/agent/reset-class`，第 2 条仍存在且响应 500，无降级追踪。

### C-6 | MethodMatchers.java:17-24 | CRITICAL | A10 | synthetic 方法未被拦截（仅拦 bridge）
`MethodMatchers.matches` 检查 `isBridge()` 但不检查 `isSynthetic()`；`LoadedClassRepository.resolveMethod` 不过滤 synthetic。A10 要求"synthetic/bridge 默认禁用"。
- 复现：通过反射拿到目标类某个 synthetic 方法的描述符，调 `POST /v1/rules` 携带该 methodName/methodDescriptor，规则可被注册并执行。

### C-7 | GroovyScriptSecurityPolicy.java:50-104 + 162-182 | CRITICAL | B1/B2/B5/B8 | Groovy 安全策略采用黑名单而非白名单，`GroovyShell`/`GroovyClassLoader` 可绕过整个沙箱
`SecureASTCustomizer` 只调 `setDisallowed*`（黑名单），未调 `setImportsWhitelist`/`setReceiversWhitelist`。`groovy.lang.GroovyShell`、`groovy.lang.GroovyClassLoader`、`groovy.lang.Expando`、`groovy.lang.Binding` 既不在 `DISALLOWED_IMPORTS` 也不在 `DISALLOWED_RECEIVER_CLASSES`；`groovy.lang.` 不在 `DISALLOWED_STAR_IMPORTS`；`evaluate`/`parseClass`/`parse`/`call`/`invokeMethod`/`with`/`identity`/`tap` 不在 `DISALLOWED_METHODS`。攻击者可在脚本中 `new groovy.lang.GroovyShell().evaluate("Runtime.runtime.exec(...)")`，内层脚本使用默认 `CompilerConfiguration`（无 SecureASTCustomizer），任意代码执行。
- 验证：已通过 `GroovySecurityBypassVerificationTest.groovyShellBypassesSecurityPolicy` 与 `groovyClassLoaderParseClassBypassesSecurityPolicy` 实测确认（编译通过）。
- 复现：见上述测试。

### C-8 | GroovyScriptSecurityPolicy.java:84-104 | CRITICAL | B5 | `java.lang.instrument.*` / `net.bytebuddy.*` 未被拦截
`DISALLOWED_RECEIVER_CLASSES`/`DISALLOWED_IMPORTS`/`DISALLOWED_STAR_IMPORTS` 均不含 `java.lang.instrument.Instrumentation`、`java.lang.instrument.ClassFileTransformer`、`net.bytebuddy.agent.builder.AgentBuilder`。B5 明确要求脚本不得访问 Instrumentation/AgentBuilder/ClassFileTransformer/Agent ClassLoader。
- 验证：已通过 `GroovySecurityBypassVerificationTest.instrumentationImportNotBlocked` 实测确认。
- 复现：脚本 `def t = java.lang.instrument.ClassFileTransformer.class` 编译通过。

### C-9 | GroovyScriptSecurityPolicy.java:72-81 | CRITICAL | B1 | 星号 import 黑名单不拦单独 import，`java.io.BufferedReader` 等可绕过
Groovy `SecureASTCustomizer.setDisallowedStarImports` 仅拦 `import java.io.*` 形式，不拦 `import java.io.BufferedReader`。而 `DISALLOWED_IMPORTS` 只有 `java.io.File`。同理 `java.net.InetSocketAddress`、`java.nio.file.StandardOpenOption` 等可单独 import 绕过。
- 复现：脚本 `import java.io.BufferedReader; import java.io.FileReader; def r = new BufferedReader(new FileReader('/etc/passwd'))` 可通过编译。

### C-10 | GroovyScriptSecurityPolicy.java:235-252 | CRITICAL | B1 | `isDangerousType` 仅精确类名匹配，不检查继承
`forbiddenClass.getName().equals(name)` 不做 `isAssignableFrom`。`GroovyClassLoader` 虽继承 `ClassLoader`，但不会被识别为危险。任何 ClassLoader/Runtime 子类都不被拦。
- 复现：脚本 `import groovy.lang.GroovyClassLoader; new GroovyClassLoader().parseClass("System.exit(0)")` 通过。

### C-11 | (缺失) runtime-mock-groovy 全模块 | CRITICAL | B4 | Groovy 脚本执行无超时，无连续慢事件/错误率自动禁用
`GroovyCompiledMockScript.execute` 直接 `script.run()`，无超时；模块内全局无 `slowEvent`/`errorRate`/`autoDisable`/`LOCKED` 逻辑。B4 要求：>10ms 慢事件、连续 3 次 >10ms 自动禁用、单次 >100ms 立即禁用、连续错误 3 次或 1 分钟错误率 >10% 且样本 >=20 自动禁用、LOCKED 后需解锁并重新审批。一个递归闭包 `def f={ f() }; f()` 即可无限阻塞业务线程。
- 复现：执行 `def f = { f() }; f()`（递归闭包目前未被 AST 拦），线程被无限阻塞。

### C-12 | PropertyPathAccessor.java:9-71 | CRITICAL | B1/B5 | 通过 `mock.get(target, "class.classLoader")` 可访问 ClassLoader，绕过 Groovy AST
`get()` 按 `.` 分割路径逐段 `readSingle`，对普通对象先 `getClass()` 等 getter，`setAccessible(true)`，再尝试字段。Groovy 脚本可 `mock.get(target, "class.classLoader")` 拿到 ClassLoader，再 `mock.get(cl, "parent")` 遍历 ClassLoader 层次。此路径完全绕过 Groovy AST 安全检查，因为反射在 Java 侧完成。`DISALLOWED_PROPERTIES` 仅在 Groovy AST 层面拦直接属性访问，不拦 `mock.get()` 间接访问。
- 复现：脚本 `def cl = mock.get(target, "class.classLoader"); mock.get(cl, "parent")`。

### C-13 | AgentCommandService.java:277-282 | CRITICAL | D7/E13 | Agent 命令 poll/ack 鉴权用 OR，远程可冒充任意 Agent
```java
if (agentId.equals(context.actor()) || "agent".equals(context.identitySource())) {
    return;
}
```
D7 要求"actor 匹配 agent id **且** X-Identity-Source: agent，**或**平台 actor 持有 AGENT_MANAGE"。当前 `||`：任意请求带 `X-Identity-Source: agent` 即可 poll/ack 任意 agent 的命令。
- 复现：`curl -H "X-Actor: victim-agent" -H "X-Identity-Source: agent" -X POST .../agents/victim-agent/commands/next` 即可窃取受害 Agent 命令。

### C-14 | RequestContextFactory.java:10-18 | CRITICAL | E13 | 完全信任 `X-Actor`/`X-Identity-Source` 头，远程可冒充任意用户
`from()` 直接读 `X-Actor` 作为 actor、`X-Identity-Source` 作为身份来源，无签名/token 校验。结合 `RbacService` 仅按 username 查 DB，远程调用方可冒充 `system`（admin）执行任意写操作。
- 复现：`curl -H "X-Actor: system" http://host/api/v1/...` 即以 admin 身份执行。

### C-15 | ExtractionWorker.java:193-227 | CRITICAL | F8 | 数据抽取允许任意 SQL，无 allowlist/写操作/DDL 拦截；`where`/`columns`/`rootTable` 字符串拼接
`queryJdbc` 中 `sql = text(template, "sql", null)` 直接使用模板 `sql` 字段；`buildSelectSql` 把 `columns`、`rootTable`、`where` 全部字符串拼接。仅 `connection.setReadOnly(true)` 远不够（PG 驱动层 readOnly 不保证拦 DDL/存储过程/锁表）。`where` 完全来自模板，无校验，等价 SQL 注入面。F8 要求"不允许任意 SQL；禁止写/DDL/存储过程/锁表"。
- 复现：模板 `where = "1=1; drop table instance"`，worker 执行后 instance 表被删（视驱动多语句支持）；或模板 `sql = "delete from audit_record"` 直接破坏审计。

---

## 三、HIGH 级 BUG

### H-1 | PlatformJdbcService.java:1219-1224 | HIGH | E1 | 审计哈希链并发竞态：`previousAuditHash` 无锁，链可断裂
`select record_hash from audit_record order by sequence desc limit 1`（无 `FOR UPDATE`、无 advisory lock）。READ COMMITTED 下两并发事务可读到同一前驱 hash，各自插入后链分叉。E1 要求"PostgreSQL 只追加哈希链"。
- 复现：2 线程并发调任意写接口，查 `audit_record` 可见两记录 `previous_record_hash` 相同。

### H-2 | V1__platform_core.sql:188-207 + PlatformJdbcService.java:1201-1209 | HIGH | E2 | AuditRecord 缺 `device` 字段
E2 要求审计包含 `device`，但表无该列、insert 无该字段、payload 也未含。
- 复现：检查 `audit_record` DDL。

### H-3 | RbacService.java:15-30 | HIGH | E12 | RBAC 不做资源级 scope 校验
`require` 查 `user_role_binding` 但 WHERE 不引用 `scope_id`/`resource_scope`。用户 role binding 绑定到 scope A 仍可访问 scope B 资源。E12 要求"后端始终重新执行资源级鉴权"。
- 复现：给 user A 绑定 scope-appA 的 ROLLOUT_MANAGE，A 仍可对 appB operation_plan 执行 transition。

### H-4 | PlatformJdbcService.java:818-821 | HIGH | E3 | createApproval 默认把 requester 设为唯一 approver，但 decideApproval 又对 requester 抛 SELF_APPROVAL_FORBIDDEN
```java
if (approvers.isEmpty()) {
    approvers = List.of(context.actor());
}
```
默认路径下审批永远无法被 decide（死锁）。
- 复现：`POST /approvals`（不带 approvers），再 `POST /approvals/{id}/decisions` 必返 403。

### H-5 | PlatformJdbcService.java:837-851 | HIGH | E3 | decideApproval 不校验 approval 状态；非 approver 调用 queryForMap 抛 500
不检查 `approval.status == WAITING_APPROVAL`，已 APPROVED/REJECTED 仍可被再次 decide；非 approver 时 `queryForMap` 抛 `EmptyResultDataAccessException` 落入 `unexpected` 返回 500（应 403/404）。
- 复现：对已 APPROVED 的 approval 再次 `POST /decisions`，状态被覆盖；或非 approver 调 decide 返 500。

### H-6 | ExtractionWorker.java:103-107,140-144,258-262 | HIGH | C1/K7 | Worker 状态更新绕过 fencingToken/expectedStatus/version
claim 用 `where id=? and status='QUEUED'`（无 version/fencingToken）；complete/fail 用 `where id=?`（无 status/version）。`failTask` 可把已被并发 worker 置为 SUCCEEDED 的任务覆盖为 FAILED。
- 复现：两 ExtractionWorker 并发拉同一 task，或人工 transition 到 CANCELLED 后 worker complete 覆盖为 SUCCEEDED。

### H-7 | ReplayWorker.java:100-104,170-175,223-227 | HIGH | C1/K7 | ReplayWorker 同 H-6
claim 无 fencingToken；complete/fail 仅 `where id=?`，无 expectedStatus/version。

### H-8 | AgentCommandService.java:144-153 | HIGH | C1 | ack 更新不检查 command 状态
`update agent_command set status=? ... where id=?`，无 `status='DISPATCHED'`。PENDING/ACKED/FAILED 命令都可被 ack 覆盖。
- 复现：对同一 commandId 连续两次 ack（status=ACKED 然后 status=FAILED），结果被后者覆盖。

### H-9 | RolloutExecutor.java:154-158,214-218,241-249,347-351 | HIGH | C1 | Rollout batch/execution 状态更新无 version/fencingToken/expectedStatus/updatedBy
`startBatch` 仅 `where id=? and status='PENDING'`；`dispatchExecution` update `where id=?`；`failBatch` 同理。rollout_batch 表本身无 version/updated_by 列（见 M-9）。
- 复现：并发调度器与人工 transition 竞态，batch/execution 状态被覆盖。

### H-10 | RolloutExecutor.java:361-378 + AgentCommandService.java:249-253 | HIGH | C1 | completeOperation/advanceOperation 用过期 version，不校验 affected rows
`version = old.version + 1` 来自旧读；`where id=? and status='RUNNING'` 可能 0 行命中，但代码不检查返回值，仍写 audit event（审计记录了"成功"了实际未发生的更新）。
- 复现：在 executor 读 operation 后、写回前，另一请求把 operation transition 到 OBSERVING，executor update 0 行却仍记录 `operation_plan.auto_complete` 事件。

### H-11 | FencingTokenService.java:87-111 | HIGH | C1/C2 | 非 Redis 模式 nextSequence 读-改-写竞态
`select coalesce(max(current_value),0)+1 from fencing_sequence where resource_key=?` 后 upsert，两并发可读到同一值，生成同 sequence 的 token（token 字符串因 UUID 仍唯一，但 fencing 序号语义失效）。`upsertDbSequence` 的 update-or-insert 也有 lost-update。
- 复现：关 Redis，并发 issue 两个同资源 fencing token，比较 sequence 字段。

### H-12 | PlatformJdbcService.java:545 | HIGH | F5 | RecordingSession 默认 TTL 3600s（1 小时），应为 15 分钟；无 2 小时上限
`optionalLong(request, "ttlSeconds", 3_600)`。F5 要求默认 15 分钟（900s）、最大 2 小时（7200s）。代码不做上限校验。
- 复现：`POST /recording-sessions`（不带 ttlSeconds）→ ttl=3600；`ttlSeconds=999999` 也通过。

### H-13 | PlatformJdbcService.java:972 | HIGH | F5 | RecordingRuleVersion 默认采样率 1.0（100%），应为 0.1%
`jsonValue(request, "sampling", Map.of("rate", 1.0))`。F5 要求默认 0.1%（0.001），100% 需高级审批。无任何审批门槛校验。
- 复现：`POST /recording-rules`（不带 sampling）→ rate=1.0，生产全量采样。

### H-14 | RolloutExecutor.java:88-112 + PlatformJdbcService.java:1056-1068 | HIGH | H1/H3 | 失败不自动回滚，rollbackPolicy 默认 automatic=false
`processOperation` 检测到 batch FAILED 时直接 `completeOperation(..., "FAILED", ...)`，不进入 ROLLING_BACK。`createRolloutPlanIfPresent` 默认 `rollbackPolicy = {automatic: false}`。H3 要求生产默认开启自动回滚。代码中无 ROLLING_BACK 触发路径或规则 LOCKED 逻辑。
- 复现：创建 operation_plan，某 batch 失败，operation 直接变 FAILED 而非 ROLLING_BACK，无回滚命令下发。

### H-15 | ReplayWorker.java:71-94 | HIGH | G4 | ReplayWorker 无自动暂停
`runOnce` 只逐个处理 execution，无失败计数器、无错误率统计、无暂停逻辑。G4 要求"连续失败 10 次或错误率 >20% 且样本 >=50 自动暂停"。
- 复现：制造 10 个 replay_execution 连续失败，下一轮 worker 仍继续。

### H-16 | PlatformJdbcService.java:283-313 | HIGH | E4 | transitionOperationPlan 不校验 approvalId 存在且 APPROVED
`createOperationPlan` 的 `approvalId` 可为 null；transition 链中不校验 approval_request 状态。可凭空把 plan 切到 RUNNING 而无任何审批。
- 复现：创建 operation_plan 不带 approvalId，依次 transition DRAFT→WAITING_APPROVAL→APPROVED→RUNNING，全程无审批。

### H-17 | PlatformJdbcService.java:797-834 | HIGH | E4 | createApproval 不校验 subject_hash 结构化绑定
E4 要求审批主体绑定包含 rule/dataset/replay ID + version + targetHash + scriptHash + capabilityHash + maskingHash + rolloutHash + sideEffectPolicyHash。当前只有单一 `subjectHash` 字符串，无结构化校验，也无"主体变更使审批失效"检查。
- 复现：创建 approval 后修改对应 rule_version 的 script，再 decide approval，仍能 APPROVED。

### H-18 | PlatformJdbcService.java（全模块无相关代码） | HIGH | F6/E5 | 无"同方法最多 10 条录制规则合并为 1 个 RecordingPlan"校验；无生产环境能力降级
无 environment type 判断，prod 下不限制活动行为规则数量，不默认关闭行为修改。
- 复现：对同一 method 创建 100 条 recording_rule 全部通过；prod 创建多条 active 行为规则全部生效。

### H-19 | PlatformJdbcService.java:631-660 + 765-794 | HIGH | E4 | transitionRecordingSession / transitionReplayPlan 不校验审批存在且 APPROVED
类似 H-16，session/replay plan 可直接 DRAFT→WAITING_APPROVAL→APPROVED→RECORDING/RUNNING 而不关联任何 approval_request。

### H-20 | EncryptedWalWriter.java:28-143 | HIGH | E10 | 无 DEK 生命周期管理；无独立数据集密钥；无到期销毁
`EncryptedWalWriter` 接受单个 `SecretKey` 用于所有记录加解密。无信封加密（DEK/KEK），无独立数据集密钥，无 DEK 到期销毁。密钥字段引用永不置零或销毁。E10 要求"数据集密钥独立；到期销毁 DEK"。

### H-21 | EncryptedWalWriter.java:59-81 | HIGH | F2 | WAL 无配额管理（10GB/80%告警/100%停止接收）
`append()` 无条件写入，无 `walFile.length()` 检查，无 80%/100% 阈值。F2 要求 "Sidecar WAL 10 GB，80% 告警，100% 停止接收"。
- 复现：循环 `writer.append(largePayload)` 超 10GB，不触发告警或停止。

### H-22 | EncryptedWalWriter.java:59-81 | HIGH | F4 | 无 SHA-256 去重机制
`append()` 计算 `sha256(plaintext)` 作为 `payloadHash`，但从不检查该 hash 是否已存在。F4 要求"脱敏后 Payload 采用规范化内容 SHA-256 去重"。
- 复现：连续 `writer.append(samePayload)` 两次，WAL 文件产生两条记录而非去重为一条。

### H-23 | EncryptedWalWriter.java:75-76 | HIGH | F1 | WAL 写入阻塞业务线程
`append()` 用 `Files.writeString()` 同步写盘且 `synchronized`。若在业务线程调用，磁盘 I/O 阻塞业务线程。F1 要求"业务线程不等待网络、磁盘或数据库"。

### H-24 | ControlServerOptions.java:13-31 + ControlHttpServer.java:134-158 | HIGH | E11/B1/F1 | 控制服务器 token 经 CLI 参数暴露；代理功能 SSRF + token URL 泄露
- `ControlServerOptions.parse()` 从 `--token` 命令行参数读 agent token，Unix `ps`/`/proc/<pid>/cmdline` 可被同机其他用户读取。E11 要求"密钥不得在 CLI 参数暴露"。
- `proxy()` 从 `?agent=` 查询参数读目标 URL，可指定 `?agent=http://169.254.169.254/latest/meta-data/` SSRF；`?token=` 出现在 URL 中可被代理日志/浏览器历史/Referer 头泄露。
- `httpClient.send()` 阻塞式网络调用违反 F1。

### H-25 | PayloadMasker.java:29-73 | HIGH | F3 | PayloadMasker 不处理数组与 Set，未脱敏数据可离开生产节点
`maskValue` 仅处理 `Map` 与 `List`，对 `Object[]`/`byte[]`/`String[]`/`Set`/`Queue` 等直接返回原值。F3 要求"原始未脱敏数据不得离开生产节点"。
- 复现：`masker.mask(new String[]{"alice@example.com", "Bearer secret-token"})`，返回原数组。

### H-26 | (缺失) runtime-mock-groovy/, runtime-mock-sidecar/ 全模块 | HIGH | F2 | 完全缺失单事件大小限制
无 256KB 默认/1MB 上限事件大小限制。`GroovyCompiledMockScript.execute()` 返回的 `MockDecision` 大小不受限；`EncryptedWalWriter.append()` 接受任意大小 `maskedPayload`。F2 要求"单事件默认 256 KB、上限 1 MB"。
- 复现：构造 2MB payload 传入 `writer.append()`，不被拒绝。

### H-27 | GroovyScriptSecurityPolicy.java:39-41,145-210 | HIGH | B3 | 缺失 AST 节点数/闭包嵌套/条件嵌套/字面量集合/对象深度/字段数/集合元素数/字符串长度/返回对象大小限制
B3 要求多项结构性限制，但仅实现脚本 16KB/400 行/闭包文本 8KB/源码标记。完全缺失：AST 节点数 10000、闭包嵌套 5、条件嵌套 20、字面量集合 1000、对象深度 8、字段数 256、集合元素 1000、单字符串 64KB、返回对象 1MB。
- 验证：已通过 `GroovySecurityBypassVerificationTest.deepClosureNestingNotLimited` 实测确认 10 层闭包嵌套可编译。

### H-28 | GroovyScriptCompiler.java:16-24,40-41 + ScriptLoaderGeneration | HIGH | B6 | 单一 GroovyClassLoader 永不轮换，Metaspace 泄漏
`GroovyScriptCompiler` 构造时创建唯一 `ScriptLoaderGeneration`，无 generation 轮换（无计数器、无时间触发、无 `rotateGeneration()`）。B6 要求"按 generation 管理 GroovyClassLoader"。脚本更新（同 ruleId 新版本）产生新类，旧类永不卸载。
- 复现：循环 publish+remove 同一 ruleId 不断递增 version，`jmap` 观察 Metaspace 持续增长不释放。

### H-29 | DefaultRuntimeObjectFactory.java:51-75 | HIGH | B1 | newThrowable 用 `Class.forName(className, true, loader)` 加载任意 Throwable 子类，`initialize=true` 触发静态初始化器
`Class.forName` 使用 `initialize=true`，会执行目标类静态初始化器。攻击者可通过 `mock.throwException("com.evil.InitializesDangerousState", "msg")` 触发任意类静态初始化器。`setAccessible(true)` 绕过构造器访问控制。
- 复现：脚本 `mock.throwException("com.example.DangerousClass", "test")`，DangerousClass 静态初始化器执行危险操作。

### H-30 | AgentRuntime.java:163-175 | HIGH | A4/A9 | resetAll 中 `transformerManager.close()` 后 `install()` 失败，Bridge 保持 NOOP 无 DEGRADED 恢复
若 `close()` 成功但 `install()` 抛异常，Bridge 保持 NOOP、ruleDispatcher 仍禁用、无活跃 transformer，HTTP 服务继续但 `/v1/rules` 返 500。无 DEGRADED 状态记录、无自动恢复。

### H-31 | AgentRuntime.java:97-107,300-307 + RuleRegistry.replace:42 | HIGH | A14/A19 | `RuleRegistry.replace` 用非 CAS `set()`，并发 publish 回滚可覆盖并发 CAS 更新
`existing.set(ruleSet)` 无条件覆盖。`AgentRuntime.publish` catch 块在并发 publish 下调用 `replace(methodKey, oldRuleSet)` 回滚到旧状态，会覆盖另一线程已 CAS 成功的新 RuleSet，破坏 A14"规则更新必须原子替换"。
- 复现：两线程并发对同一 methodKey 发布规则，A 抛异常回滚时覆盖 B 已成功的 CAS。

### H-32 | OpsOptions.java:72-84 + OpsCommand.java:23-33,55-68 | HIGH | J5 | 应急 CLI 违反多项 J5 要求
1. 未强制 Break Glass 事件号：`disable-rule`/`remove-rule` 仅需 `rule-id`；`disable-all`/`reset-all`/`shutdown-agent` 仅需 `reason`；`reset-class` 需 `class-id`+`reason` 但无 `event`。
2. 无本地审计追加：`OpsCommand.execute` 仅发 HTTP 并打印响应。
3. shutdown-agent 非幂等：第一次关闭 Agent，第二次连接拒绝失败。
4. 网络失败无结构化退出码：`execute()` `throws IOException`，main `throws Exception`，非 IllegalArgumentException 异常以堆栈+exit 1 退出。

### H-33 | ControlHttpServer.java:30,77-80 | HIGH | E11/F1 | HttpClient 无超时；500 错误响应泄露内部类名和异常消息
`HttpClient.newHttpClient()` 默认无超时，`proxy()` 可能无限阻塞；catch-all 块把 `e.getClass().getName() + ": " + e.getMessage()` 写入响应体，泄露内部类名/堆栈/文件路径。
- 复现：`?agent=http://nonexistent:9999/` 发代理请求长时间挂起；发格式错误 JSON 响应含 Jackson 内部异常类名。

### H-34 | AgentRuntime.java:289-307 | HIGH | A4/A5 | restoreInstrumentationState 在脚本编译失败路径（未发生状态变更）也触发 retransform
当 `rulePublisher.publish` 在 `applyInstrumentationTransition` 运行前抛异常时，catch 块仍调 `restoreInstrumentationState` → `transformerManager.retransform(...)`，造成不必要且昂贵的 retransform。违反 A4"脚本/TTL/优先级变更不得重复 retransform"的精神。
- 复现：发布编译期抛异常的 Groovy 脚本，观察 `ByteBuddyTransformerManager.retransformCount` 增加。

### H-35 | PlatformJdbcService.java（无相关代码） | HIGH | D3 | 除 AgentCommandService 外所有写操作不支持 Idempotency-Key
AgentCommandService 有 `idempotency_key` 唯一约束；其他 create/transition 接口（createInstance、createRule、createOperationPlan、createExtractionTask、createRecordingSession、createReplayPlan、createApproval、decideApproval 等）无 Idempotency-Key 头处理，无幂等键列。D3 要求"所有高危写操作必须幂等"。
- 复现：`POST /instances` 网络超时重试两次，产生两条 instance 记录。

---

## 四、MEDIUM 级 BUG

### M-1 | ExtractionWorker.java:198-199 | MEDIUM | F8 | 未强制 max 30s 超时、max 100,000 行
`maxRows`/`timeoutSeconds` 从 task/quota 读取，无上限 clamp。F8 要求最大 30s/100,000 行。
- 复现：创建 extraction_task 带 `quota.timeoutSeconds=300, quota.maxRows=1000000`，worker 不拒绝。

### M-2 | ExtractionWorker.java:200-215 | MEDIUM | F9 | 未设置 REPEATABLE READ READ ONLY 事务隔离
仅 `connection.setReadOnly(true)`，未 `setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)`。F9 要求 PostgreSQL REPEATABLE READ READ ONLY。
- 复现：抽取过程中其他事务提交新行，extraction 结果集包含非一致性快照。

### M-3 | FencingTokenService.java:69-85 | MEDIUM | C1 | consume 不校验 token owner
`consume` 更新条件不含 `owner = context.actor()`。任何持 ROLLOUT_MANAGE 等能力的用户可消耗他人 fencing token。
- 复现：userA issue token，userB 用同一 token 调 transition，consume 成功。

### M-4 | PlatformJdbcService.java:602-603 | MEDIUM | F5 | maxEvents 无单规则 100,000 上限校验
`createRecordingSession` 不校验 maxEvents 上限；`createRecordingRule` 不校验单规则事件上限。

### M-5 | PlatformJdbcService.java:75-77,1119-1125,1138-1145 | MEDIUM | (SQL 注入防御) | list/getById/countWhere/nextScopedVersion 用字符串拼接表名/列名
`"select * from " + table + " order by " + orderBy` 模式。当前调用方全部硬编码，不可直接利用，但一旦未来有动态入参即 SQL 注入。

### M-6 | KafkaOutboxPublisher.java:94-103 | MEDIUM | D8 | Kafka 消息无顶层 eventId，消费端无法直接幂等
`kafkaTemplate.send(topic, event.key(), event.payloadJson())`，key 是 `aggregateType:aggregateId`（非唯一），payload 是 audit JSON（内含 audit id）。同 aggregate 多条事件 key 相同，消费端需解析 payload 取 audit id 做幂等。生产者侧 at-least-once（crash 后重发）确认存在。
- 复现：kill 进程在 `kafkaTemplate.send().get()` 后、`markPublished` 前，重启后同 event 重发到 Kafka。

### M-7 | V2__control_plane_assets_rules_rollout.sql:239-273 | MEDIUM | C1 | rollout_batch / rollout_instance_execution 表无 version 列
两表均无 `version` 列，C1 要求状态更新带 version。即使代码想带 version 也无列可写。

### M-8 | GroovyScriptSecurityPolicy.java:166 | MEDIUM | B2 | `closuresAllowed(true)` 但无嵌套深度限制
B3 要求闭包嵌套不超过 5 层。`isExpressionAuthorized()` 对 `ClosureExpression` 仅检查代码文本长度，不检查嵌套深度。
- 验证：已通过 `GroovySecurityBypassVerificationTest.deepClosureNestingNotLimited` 实测。

### M-9 | GroovyScriptSecurityPolicy.java:173-178 | MEDIUM | B2 | ForStatement 被完全禁止，但 B2 允许有界 for/range/集合迭代
`DISALLOWED_STATEMENTS` 包含 `ForStatement.class`。B2 明确允许"有界 for/range/集合迭代"。功能限制（非安全漏洞），违反 B2 字面要求。

### M-10 | GroovyScriptSecurityPolicy.java:200-203 | MEDIUM | B3 | 闭包大小检查使用 `getText().length()` 不可靠
`Statement.getText()` 返回文本表示可能不完整，可能不包含嵌套表达式完整文本，导致大小检查被绕过。

### M-11 | GroovyScriptSecurityPolicy.java:207-209 | MEDIUM | B3 | isStatementAuthorized 用 `text.length()` 检查语句大小，与 B3 AST 节点数限制无关
短文本语句可能含大量 AST 节点（如 `a.b.c.d.e.f.g.h.i.j.k.l.m.n.o.p.q.r.s.t`），节点数不受限。

### M-12 | ControlPlaneService.java:261-286 | MEDIUM | E1 | 审计 hash chain 实现脆弱
`audit()` 用 `synchronized` 修饰且 `audits.get(audits.size()-1)` 取前驱 hash，`CopyOnWriteArrayList` 上虽线程安全但架构脆弱。若未来有人在 `ConcurrentHashMap.compute` lambda 内调 `audit()` 可能死锁。

### M-13 | StableTokenizer.java:10-14 | MEDIUM | E10 | domainKey 永不销毁
`StableTokenizer` 构造时克隆并保存 `domainKey`，无 `destroy()`/`zero()` 方法。E10 要求"到期销毁 DEK"。

### M-14 | TypeConverter.java:40-93 | MEDIUM | (类型转换) | 不处理数组和集合类型转换
`convert()` 不支持 `int[]`/`String[]` 等数组类型与 `Set`/`Queue` 等集合类型转换。Mock 场景中目标方法参数为数组类型时 `returnJson`/`set` 操作失败。
- 复现：`TypeConverter.convert(List.of(1,2,3), int[].class)` 抛 `IllegalArgumentException`。

### M-15 | PayloadMasker.java:34-37 | MEDIUM | B3 | maxDepth 检查使用 `>` 而非 `>=`，实际允许 depth 0-8 共 9 层
`if (depth > policy.maxDepth())` 当 maxDepth=8 时，depth=8 仍处理（`8 > 8` false）。若 B3"对象深度 8"意为最多 8 层嵌套，应使用 `>=`。

### M-16 | PlatformJdbcService.java:840-843 | MEDIUM | E3 | decideApproval 对 requester 阻止 REJECTED
requester 无法 reject 自己的请求（语义上 self-reject 应被允许，类似撤回）。

### M-17 | PlatformJdbcService.java:891-907 | MEDIUM | (健壮性) | list 接口无分页，全表返回
`list()` 无 limit/offset，`/audits`/`/outbox`/`/instances` 等在大数据量下可导致 OOM 或慢查询。
- 复现：向 audit_record 插入 1000 万行后 `GET /api/v1/audits`。

### M-18 | RolloutExecutor.java:280-313 | MEDIUM | C7 | ruleCommandPayload 不校验 rule_version 状态即可下发
`select * from rule_version where rule_id=? and version=?` 不检查 `status`，DRAFT/INACTIVE 版本可被 rollout。
- 复现：创建 rule_version status=DRAFT，创建 operation_plan 指向它，rollout 下发 DRAFT 脚本到 agent。

---

## 五、LOW 级 BUG

### L-1 | PlatformJdbcService.java:846-851 | LOW | (健壮性) | decideApproval queryForMap 空结果未捕获
非 approver 调 decide 时 `queryForMap` 抛 `EmptyResultDataAccessException`，落入 `unexpected` 返回 500。应 403/404。

### L-2 | PlatformJdbcService.java:1219-1224 | LOW | E1 | 审计表无防删改约束/触发器，无 1 年保留策略
`audit_record` 表无 trigger 阻止 UPDATE/DELETE，无分区/归档策略保证在线 1 年。DBA 可静默篡改。
- 复现：`UPDATE audit_record SET record_hash='fake' WHERE sequence=1;` 成功。

### L-3 | RuleDispatcher.java:56-71 | LOW | A6 | validateArguments 在无规则匹配时也在热路径运行
即使 `ruleSet.hasPhase(BEFORE)` 为真但无规则匹配，`onEnter` 仍调 `validator.validateArguments`，每次命中增加分配。语义上 no-op。

### L-4 | GroovyScriptSecurityPolicy.java:43-48 | LOW | B1 | FORBIDDEN_SOURCE_MARKERS 缺少关键危险标记
仅含 `@Grab`/`@Grapes`/`groovy.grape.Grape`/`package `。缺 `GroovyShell`/`GroovyClassLoader`/`ScriptEngine`/`evaluate`/`Expando`/`methodMissing`/`propertyMissing`/`MethodHandle`/`Instrumentation`/`AgentBuilder`/`ClassFileTransformer`，使 C-7 在源码文本层面也不拦截。

### L-5 | ByteBuddyTransformerManager.java:73-86 | LOW | A10 | `ignore` 过滤器未排除部分 JDK 扩展包
已排除 `java.`/`javax.`/`jdk.`/`sun.`/`com.sun.`/`net.bytebuddy.`/`groovy.`/`com.example.runtimemock.`，但未排除 `org.w3c.dom.`/`org.xml.sax.`/`org.ietf.jgss.`/`com.oracle.`。

### L-6 | AgentRegistrationWriter.java:77-79 | LOW | A7 | Token 以明文写入 token 文件
虽 POSIX 0600，但 token 是磁盘明文。A7 严格意义上"注册文件不含明文 Token"指注册文件本身（合规），但 token 文件含明文仍是防御性隐患。root 或同 uid 进程可读。

### L-7 | ControlHttpServer.java:64-85 | LOW | D1 | 控制服务器 API 完全无认证
所有 `/api/v1/control/*` 和 `/api/*` 代理端点无需认证。D1 仅明确要求 Agent API，但控制服务器暴露录制/重放管理 + Agent 代理功能，若绑非回环则为安全漏洞。

### L-8 | RuntimeMockAgent.java:56-58 | LOW | A8 | Agent 引导过程 fail-open 静默吞部分初始化运行时
`AgentCore.start` 设置 `currentRuntime` 后、`AgentCoreLauncher.start` 完成前异常被 catch 静默吞。运行时部分活跃（Bridge/transformer/清理 executor 在跑）但无 HTTP 服务管理，无 DEGRADED 状态。

---

## 六、SUSPECT（高度可疑，需运行时确认）

### S-1 | PlatformJdbcService.java:1070-1075 | C7 | nextScopedVersion 读-改-写可能产生重复 version
`select coalesce(max(version),0)+1 from rule_version where rule_id=?` 无 `FOR UPDATE`。两并发 createRuleVersion 可能拿到相同 version，依赖 `unique(rule_id, version)` 报错回滚。功能上靠唯一约束兜底，但产生 500 错误而非友好重试。同样模式见 `nextBatchOrder`、`nextDatasetVersion`。

### S-2 | ExtractionWorker.java:152-188 | C3 | materialize 优先用 template.rows / datasource.sampleRows，可能绕过 DB 抽取约束
当模板含 `rows` 或 datasource 含 `sampleRows` 时，worker 直接返回静态数据而不执行 SQL。这些静态数据不受 quota/timeout/readonly 约束。若被误用为数据通道，可绕过 F8 所有限制。

### S-3 | PlatformJdbcService.java:1109-1112 | C8 | createReplayPlan 不校验 dataset_version 是否为"已执行/已冻结"版本
`getDatasetVersion` 只检查存在性，不检查 dataset_version 的状态/不可变性。若 dataset 后续被删除/修改，replay plan 仍指向旧 version。

### S-4 | RolloutExecutor.java:166-208 | H3 | captureTargets 对已有 executions 不跳过，可能重复插入
`existingExecutions.isEmpty()` 检查 batch 下所有 executions，若不为空就跳过 `captureTargets`。但如果 batch 既有手动创建的 execution 又需要自动捕获，自动捕获被跳过；反之若 `existingExecutions` 非空但不含当前 instance，也不会补充。

### S-5 | ByteBuddyTransformerManager.java:73-86 | A10 | JDK 扩展包排除是否完整
见 L-5。

### S-6 | ControlHttpServer.java:134-158 | D2 | 控制服务器代理在 URL 查询参数中传 token
见 H-24。

### S-7 | GroovyScriptSecurityPolicy.java:106-134 | B5 | DISALLOWED_METHODS 缺少反射/类加载器入口点
缺 `getProtectionDomain`/`getContextClassLoader`/`defineModule`/`loadLibrary`/`getModule`/`getUnnamedModule` 等。结合 C-8 提供更多逃逸面。

### S-8 | ControlPlaneService.java:261-286 | E1 | hash chain 在 CopyOnWriteArrayList + synchronized 下并发正确性
当前实现因 `synchronized` 应正确，但架构脆弱，见 M-12。

### S-9 | AgentRuntime.java 全局 | A19 | DEGRADED 触发与恢复路径完全缺失
见 C-4。无任何代码路径会让 Agent 进入 DEGRADED 状态。

---

## 七、约束覆盖与"通过项"摘要（部分）

为避免误判，下列约束经走查确认**当前实现基本满足**（仅供对比，非 BUG）：

- A1 通过：bootstrap / bootstrap-api pom 不含 ByteBuddy/Groovy/Jackson/Spring/日志框架
- A2 通过：`RuntimeMockBridge`/`BridgeDispatcher`/`EnterResult`/`ExitResult`/`BridgeAction` 仅用 JDK 类型；advice 仅调 Bridge
- A3 通过：`RuntimeMockBridge.enter/exit` catch `Throwable` 返 PROCEED；`uninstall()` 恢复 NOOP
- A4（部分）通过：全局一个 `ResettableClassFileTransformer`；脚本/TTL/优先级变更不 retransform
- A5 通过：0→1 与 1→0 在 `applyInstrumentationTransition` 触发 retransform
- A6 通过：热路径 `ConcurrentHashMap<MethodKey, AtomicReference<RuleSet>>` O(1)
- A11/A12/A13 通过：无 Core 热升级、Agent 无 Spring、热路径不编译 Groovy
- A14（部分）通过：`RuleSet` 不可变，`addRule`/`removeRule` 用 CAS；`replace` 例外见 H-31
- A15 通过：`ReentryGuard.Scope` AutoCloseable + try-with-resources；按 `methodKey + "::" + ruleId` 防重入
- A16 通过：`DecisionValidator.validateReturnValue` 拒 void 非 null 返回；`TypeConverter.isAssignable` 对 primitive 返 false；`MethodKey` 引用相等
- A17 通过：`DecisionValidator.validateThrowable` 拒未声明 checked exception
- B6（部分）通过：每次执行 `getDeclaredConstructor().newInstance()` 创建独立实例，无共享 Binding；generation 例外见 H-28
- D1（部分）通过：`AgentHttpServer.authenticated` 对除 `/health`/`/v1/health` 外所有路由校验 token；空白 token 例外见 C-1
- E10（部分）通过：WAL 用 AES-GCM + 随机 nonce + AAD；DEK 生命周期例外见 H-20

---

## 八、最高优先级修复建议（仅描述，不修改代码）

1. **C-7/C-8/C-9/C-10/C-11/C-12（Groovy 安全）**：SecureASTCustomizer 切换为白名单模式；`groovy.lang.*`、`java.lang.instrument.*`、`net.bytebuddy.*` 入黑名单；`GroovyShell`/`GroovyClassLoader`/`evaluate`/`parseClass` 入 receiver/method 黑名单；`isDangerousType` 改用 `isAssignableFrom`；`PropertyPathAccessor.get/set` 加路径段白名单（拒绝 `class`/`classLoader`/`protectionDomain`/`module` 等）；实现运行时超时与连续慢事件/错误率自动禁用。
2. **C-1/C-2/C-3/C-13/C-14（认证鉴权）**：token fail-open 改 fail-closed；强制 127.0.0.1 不可覆盖；token 15 分钟轮换 + 过期拒绝；`AgentCommandService.requireAgentProtocolOrManager` 改 AND；引入真实身份签发（OIDC/mTLS/签名 token）替代裸 `X-Actor` 头。
3. **C-15（SQL 注入）**：ExtractionWorker SQL 走 allowlist + 参数化 + 语句类型白名单（仅 SELECT）；`where`/`columns`/`rootTable` 严格校验。
4. **H-1（审计哈希链）**：用 `pg_advisory_xact_lock` 或单写者序列化保证 previousHash 串联。
5. **C-4/C-5/H-30/H-31（Agent 状态机与回滚）**：引入 `AgentState` 枚举与 DEGRADED 状态；`resetClass` 逐类返回状态 + DegradedClassRegistry；`RuleRegistry.replace` 改 CAS；`resetAll` 失败进入 DEGRADED。
6. **H-6/H-7/H-8/H-9/H-10/H-11（状态机更新）**：所有 worker/executor 状态更新走 `transitionXxx` 路径或等价地带 `expectedStatus + version + fencingToken`；检查 affected rows。
7. **H-4/H-5/H-16/H-17/H-19（审批全链路）**：默认 approver 不应是 requester；decide 校验 approval 状态；transition 关联 approval_request + APPROVED；subject_hash 结构化。
8. **H-14/H-15（自动回滚/暂停）**：生产默认 automatic=true；batch FAILED 触发 ROLLING_BACK + 规则 LOCKED；ReplayWorker 实现连续失败 10 次/错误率 >20% 且样本 >=50 自动暂停。
9. **H-12/H-13/H-18/H-26（默认值与上限）**：RecordingSession 默认 TTL 900s/上限 7200s；RecordingRule 默认采样 0.001；生产同方法 10 条录制规则上限 + 行为规则 1 条；单事件 256KB/1MB 上限。
10. **H-20/H-21/H-22/H-23/H-25（数据安全）**：WAL 信封加密 + 独立数据集密钥 + DEK 销毁；WAL 配额 10GB/80%/100%；SHA-256 去重；WAL 异步写不阻塞业务线程；PayloadMasker 覆盖数组/Set/Queue。
11. **H-24/H-33/H-32（运维）**：token 从文件/env 读不进 CLI；ControlServer 代理 `?agent=` 改白名单 + `?token=` 改 header；HttpClient 加超时；500 不泄露内部异常；Ops CLI 加 Break Glass 事件号 + 本地审计追加 + 结构化退出码。

---

## 九、验证用测试

新增测试文件（仅测试，不修改应用代码）：
- `runtime-mock-groovy/src/test/java/com/example/runtimemock/groovy/GroovySecurityBypassVerificationTest.java`

实测结果（`mvn -pl runtime-mock-groovy -am test -Dtest=GroovySecurityBypassVerificationTest`）：
```
[verify] GroovyShell bypass compiled=true           ← C-7 确认
[verify] GroovyClassLoader parseClass compiled=true ← C-7 确认
[verify] Instrumentation import compiled=true       ← C-8 确认
[verify] deep closure nesting compiled=true         ← H-27/M-8 确认
```

四个测试断言当前实现"应被拒绝但仍编译通过"，以证明 BUG 存在。若未来修复，断言会失败（compiled=false）从而提示已修复。

---

## 十、基线确认

- `mvn -DskipTests clean compile`：18 模块全部 SUCCESS
- `mvn test`：23 + 2 + 其他单元测试全部通过；BUILD SUCCESS
- Flyway V1/V2/V3/V4/V5 全部迁移成功
- 现有测试未覆盖上述 BUG 场景（多数正向路径测试）

---

报告结束。
