# Kairo 诊断日志规范

## 目标与边界

Kairo 的日志用于回答五个问题：哪个请求或命令、作用于哪个资源、状态如何变化、耗时多久、失败根因在哪里。日志不是调用录制器，禁止在每次被增强方法调用、每条 Advice 或每次心跳成功时输出一行日志，否则会改变目标 JVM 的性能特征并产生不可控日志量。

所有关键边界输出单行、可检索的 `key=value` 事件。`event` 是稳定名称；同一条链路使用 `correlationId`、`operationId`、`commandId`、`ruleId`、`requestRef` 等字段关联。

严禁写入日志：

- Access Token、Authorization、Cookie、密码、密钥和凭据；
- Groovy/KairoScript 源码、HTTP 请求体/响应体、MCP 参数；
- 被增强方法的实参、返回值以及业务对象的 `toString()`；
- 完整幂等键。幂等键只记录不可逆的短 SHA-256 指纹。

`DiagnosticEvent` 会做单行化、长度限制和二次脱敏，但调用者仍必须遵守“只记录身份、状态和计数，不记录内容”的原则。

## 日志级别

- `DEBUG`：成功的只读 HTTP 请求和调试信息，生产环境可关闭。
- `INFO`：写请求、命令领取/完成、操作状态迁移、断路器恢复、长稳阶段完成。
- `WARN`：可预期的 4xx、幂等冲突/租约异常、降级和非致命异常。
- `ERROR`：5xx、命令执行/ACK 失败、规则超时/异常、证据写入失败和未捕获异常。

健康检查、空轮询和每次业务方法调用不在 INFO 级别打印。

## 事件目录

| 功能域 | 关键事件 | 主要关联字段 |
| --- | --- | --- |
| Platform HTTP | `http.request.completed`, `http.request.rejected`, `http.request.unexpected_failure` | `correlationId`, `method`, `path`, `status`, `durationMs`, `actor` |
| 幂等写入 | `idempotency.reserved`, `idempotency.completed`, `idempotency.replayed`, `idempotency.reclaimed`, `idempotency.fence_lost` | `keyHash`, `correlationId`, `status` |
| Operation | `operation.event`, `operation.transition`, `operation.transition_conflict`, `operation.idempotent_replay` | `operationId`, `operationType`, `fromStatus`, `toStatus`, `sequence` |
| Agent 本地 API | `agent.http.request.completed` | `correlationId`, `method`, `path`, `status`, `durationMs` |
| Agent 生命周期 | `agent.start`, `agent.register`, `agent.disable-all`, `agent.enable-all`, `agent.reset-all`, `agent.reset-class`, `agent.degraded`, `agent.stop` | `fromState`, `toState`, `loadMode`, `affectedClasses`, `failedRules` |
| 规则管理 | `rule.create`, `rule.update`, `rule.enable`, `rule.disable`, `rule.delete`, `rule.publish.failed`, `rule.cleanup.failed` | `ruleId`, `ruleVersion`, `target`, `location`, `targetTransitioned` |
| 首载/热更新 | `rule.pending.*`, `target.drifted`, `target.reconcile.failed`, `rule.callsite.drifted`, `rule.callsite.revalidation.failed` | `ruleId`, `target`, `phase`, `failureStack` |
| 录制与上传 | `recording.start`, `recording.stop`, `recording.event.dropped`, `recording.upload.completed`, `recording.upload.requeued`, `recording.upload.failed` | `sessionId`, `batchId`, `eventCount`, `queueSize`, `durationMs` |
| Platform→Agent 命令 | `platform.command.received`, `platform.command.acked`, `platform.command.execution_failed`, `platform.command.ack_failed`, `platform.command.poll_failed`, `platform.command.poll_recovered` | `commandId`, `commandType`, `epoch`, `ackStatus`, `durationMs` |
| 规则执行 | `rule.execution.failed`, `rule.execution.timeout`, `rule.execution.rejected` | `ruleId`, `target`, `location`, `circuitReason`, `outcome` |
| 断路器 | `rule.circuit.half_open`, `rule.circuit.closed`, `rule.circuit.opened`, `rule.circuit.reopened` | `ruleId`, `target`, `previousReason`, `reason`, `durationMs` |
| Attach Executor | `attach.executor.registered`, `attach.command.started`, `attach.command.succeeded`, `attach.command.failed`, `attach.command.ack_failed` | `executorId`, `commandId`, `commandType`, `processId`, `durationMs` |
| MCP | `mcp.request.completed`, `mcp.request.failed`, `mcp.request.rejected`（只写 stderr） | `requestRef`, `method`, `toolName`, `outcome`, `durationMs` |
| P7D/Soak | `soak.run.started`, `soak.summary.recorded`, `soak.lifecycle_batch.*`, `soak.disconnect_recovery.*`, `soak.continuous_rule.circuit_*`, `soak.run.completed` | `elapsedSeconds`, `continuousInvocations`, `cycle`, `reason`, 资源指标 |

Agent 自身的规则发布、启停、卸载、漂移、脚本会话和录制功能使用 `RuntimeEvent.type` 作为稳定事件名；事件消息同样采用结构化字段并经过脱敏和限长，缓冲区有界，不会无限占用目标 JVM 内存。连续空轮询和健康检查不记成功日志；轮询故障只记录第一次失败及恢复边沿。

## 排障路径

1. 从客户端响应头或 `ApiError.correlationId` 找到 `http.request.completed`。
2. 若返回了 Operation，沿 `operationId` 查看 `operation.event` 和 `operation.transition`。
3. 若下发 Agent 命令，沿 `commandId` 检查 `received → execution → acked`；缺少 `acked` 时查看 `ack_failed`。
4. 若增强行为短暂失效，沿 `ruleId` 查看 `rule.execution.*` 以及 `OPEN → HALF_OPEN → CLOSED/OPEN`。
5. 若为 P7D，先定位最后一个 `soak.summary.recorded`，再对照 `soak-result.json` 的 `continuousRuleHealth.transitions` 和原始 `soak-timeseries.jsonl`。
6. 异常事件同时提供 `failure`（脱敏后的因果链）与 `failureStack`（根因前八个代码位置）；严禁用业务请求体补充上下文。

任何链路出现“有最终 FAILED、没有阶段事件”的情况，都视为日志契约缺陷，不能通过发布验收。

## P7D 证据约束

`continuousRuleHealth` 必须包含打开次数、恢复次数、结束状态、最后原因及按时间排序的 `transitions`。校验器强制验证：

- `openEvents - recoveries` 必须与 `circuitOpenAtEnd` 一致；
- 每个打开/恢复计数必须有对应的带时间戳迁移；
- 通过的测试不得以打开的断路器结束；
- 打开过断路器时必须保存明确的 `CircuitBreakReason`。

这保证下次长稳失败时可以准确回答“第几秒、累计多少次调用、因何打开、是否恢复”，而不是只看到一个最终失败结果。
