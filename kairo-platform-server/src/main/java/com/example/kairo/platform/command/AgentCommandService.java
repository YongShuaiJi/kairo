package com.example.kairo.platform.command;

import com.example.kairo.platform.persistence.mapper.AgentCommandMapper;
import com.example.kairo.platform.persistence.mapper.RuleUnloadMapper;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.BusinessIdService;
import com.example.kairo.platform.service.PlatformCoreService;
import com.example.kairo.platform.service.PlatformJson;
import com.example.kairo.platform.service.RbacService;
import com.example.kairo.platform.service.RequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentCommandService {

    private final AgentCommandMapper commandMapper;
    private final RbacService rbacService;
    private final PlatformCoreService eventWriter;
    private final BusinessIdService businessIdService;
    private final Clock clock;
    private final CapabilityGate capabilityGate;
    private BytecodeDiagnosticExchange bytecodeExchange;
    private ScriptSessionExchange scriptSessionExchange;
    private TargetResolutionExchange targetResolutionExchange;
    private RuleUnloadMapper ruleUnloadMapper;

    @Autowired
    void setBytecodeExchange(BytecodeDiagnosticExchange bytecodeExchange) {
        this.bytecodeExchange = bytecodeExchange;
    }

    @Autowired
    void setScriptSessionExchange(ScriptSessionExchange scriptSessionExchange) {
        this.scriptSessionExchange = scriptSessionExchange;
    }

    @Autowired
    void setTargetResolutionExchange(TargetResolutionExchange targetResolutionExchange) {
        this.targetResolutionExchange = targetResolutionExchange;
    }

    @Autowired
    void setRuleUnloadMapper(RuleUnloadMapper ruleUnloadMapper) {
        this.ruleUnloadMapper = ruleUnloadMapper;
    }

    @Autowired
    public AgentCommandService(AgentCommandMapper commandMapper, RbacService rbacService,
                               PlatformCoreService eventWriter, BusinessIdService businessIdService,
                               CapabilityGate capabilityGate) {
        this(commandMapper, rbacService, eventWriter, businessIdService, Clock.systemUTC(), capabilityGate);
    }

    AgentCommandService(AgentCommandMapper commandMapper, RbacService rbacService,
                        PlatformCoreService eventWriter, BusinessIdService businessIdService, Clock clock,
                        CapabilityGate capabilityGate) {
        this.commandMapper = commandMapper;
        this.rbacService = rbacService;
        this.eventWriter = eventWriter;
        this.businessIdService = businessIdService;
        this.clock = clock;
        this.capabilityGate = capabilityGate;
    }

    public List<Map<String, Object>> listCommands() {
        return normalizeRows(commandMapper.listCommands());
    }

    public Map<String, Object> command(String id) {
        return getById(id);
    }

    @Transactional
    public Map<String, Object> createManualCommand(RequestContext context, String agentId,
                                                   Map<String, Object> request) {
        rbacService.require(context, "AGENT_MANAGE");
        String commandType = requiredString(request, "commandType", null);
        Map<String, Object> payload = optionalMap(request, "payload");
        String idempotencyKey = optionalString(request, "idempotencyKey", null);
        if (idempotencyKey == null) {
            idempotencyKey = "manual:" + agentId + ":" + commandType + ":"
                    + businessIdService.nextId("agent_command_idempotency",
                    commandBusinessName(commandType, payload));
        }
        return enqueue(context, agentId, commandType, payload, idempotencyKey,
                optionalLong(request, "maxAttempts", 5), clock.instant());
    }

    /** Inserts a diagnostic command and registers transient bytes before the transaction becomes visible. */
    @Transactional
    public Map<String, Object> createBytecodeDiagnosticCommand(RequestContext context, String agentId,
                                                                String commandType,
                                                                Map<String, Object> payload,
                                                                byte[] transientInput) {
        rbacService.require(context, "AGENT_MANAGE");
        if (!commandType.startsWith("BYTECODE_")) {
            throw PlatformException.badRequest("INVALID_FIELD", "Not a bytecode diagnostic command");
        }
        Map<String, Object> created = enqueue(context, agentId, commandType, payload,
                "diagnostic:" + agentId + ":" + UUID.randomUUID(), 1, clock.instant());
        if (bytecodeExchange == null) throw new IllegalStateException("Bytecode diagnostic exchange unavailable");
        bytecodeExchange.register(String.valueOf(created.get("id")), transientInput);
        return created;
    }

    /**
     * Enqueues a script-session or script-compile command and registers it with the script-session
     * exchange so the dispatching API request can await the agent ack. Script commands are not
     * retried ({@code maxAttempts = 1}) because the agent's session state machine is single-flight:
     * a replayed command would observe the wrong state, so a timeout must surface as failure rather
     * than silent re-execution. The {@code scriptSource} is carried in the in-memory exchange only
     * (spliced into the payload at poll time) so the durable command row stores just the script hash.
     */
    @Transactional
    public Map<String, Object> createScriptCommand(RequestContext context, String agentId,
                                                    String commandType, Map<String, Object> payload,
                                                    String scriptSource, String idempotencyKey) {
        rbacService.require(context, "RULE_MANAGE");
        if (!commandType.startsWith("SCRIPT_")) {
            throw PlatformException.badRequest("INVALID_FIELD", "Not a script command: " + commandType);
        }
        Map<String, Object> created = enqueue(context, agentId, commandType, payload,
                idempotencyKey, 1, clock.instant());
        if (scriptSessionExchange == null) {
            throw new IllegalStateException("Script session exchange unavailable");
        }
        scriptSessionExchange.register(String.valueOf(created.get("id")), scriptSource);
        return created;
    }

    /**
     * Enqueues a {@code RESOLVE_TARGET} command for save-time target resolution + drift validation
     * (V1.3 §3.5) and registers it with the target-resolution exchange so the rule-save request can
     * await the agent ack synchronously. Uses {@link Propagation#REQUIRES_NEW} so the command row
     * commits independently of the caller's rule-save transaction: the agent cannot see a pending
     * command inside an uncommitted transaction, so the resolution must be visible before the await.
     * Single-flight ({@code maxAttempts = 1}) like script commands - a replayed resolution against a
     * recompiled class would observe the wrong bytecode, so a timeout must surface as failure.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> createTargetResolutionCommand(RequestContext context, String agentId,
                                                             Map<String, Object> payload) {
        Map<String, Object> created = enqueue(context, agentId, "RESOLVE_TARGET", payload,
                "resolve:" + agentId + ":" + UUID.randomUUID(), 1, clock.instant());
        if (targetResolutionExchange == null) {
            throw new IllegalStateException("Target resolution exchange unavailable");
        }
        targetResolutionExchange.register(String.valueOf(created.get("id")));
        return created;
    }

    @Transactional
    public Map<String, Object> enqueue(RequestContext context, String agentId, String commandType,
                                       Map<String, Object> payload, String idempotencyKey,
                                       long maxAttempts, Instant availableAt) {
        requireExistingAgent(agentId);
        // V1.7 M0 / §3.4: never enqueue a command whose capability the agent did not advertise.
        capabilityGate.requireDispatchable(commandMapper.findAgentCapabilities(agentId), commandType);
        Instant now = clock.instant();
        Map<String, Object> fullPayload = new LinkedHashMap<>(payload);
        fullPayload.putIfAbsent("protocolVersion", "v1");
        String id = businessIdService.nextId("agent_command",
                commandBusinessName(commandType, fullPayload));
        try {
            commandMapper.insertCommand(id, agentId, commandType, "PENDING", idempotencyKey,
                    PlatformJson.write(fullPayload), PlatformJson.write(Map.of()), 0,
                    Math.max(maxAttempts, 1), timestamp(availableAt), context.actor(),
                    timestamp(now), timestamp(now), context.correlationId(),
                    optionalString(fullPayload, "rollbackExecutionId", null));
        } catch (DuplicateKeyException ignored) {
            return getByIdempotencyKey(idempotencyKey);
        }
        Map<String, Object> created = getById(id);
        eventWriter.recordEvent(context, "agent_command.enqueue", "agent_command", id, 1,
                "", created, "SUCCESS", "enqueue agent command",
                Map.of("agentId", agentId, "commandType", commandType, "idempotencyKey", idempotencyKey));
        return created;
    }

    @Transactional
    public Map<String, Object> pollNext(String agentId, RequestContext context, Map<String, Object> request) {
        requireAgentProtocolOrManager(agentId, context);
        requireExistingAgent(agentId);
        Instant now = clock.instant();
        // V1.7 M1-A §8.1: terminate DISPATCHED commands whose lease expired and that exhausted
        // max_attempts, so they never linger invisibly in DISPATCHED. Triggered on each poll
        // (no background sweeper in M1-A; restart reconciliation is M1-B). Agent-scoped: only
        // the polling agent's commands are touched, never another agent's in-flight lease.
        commandMapper.expireExhaustedCommands(agentId, timestamp(now));
        Instant leaseExpiresAt = now.plusSeconds(optionalLong(request, "leaseSeconds", 60));
        List<Map<String, Object>> candidates = commandMapper.pollCandidates(agentId, timestamp(now));
        if (candidates.isEmpty()) {
            return Map.of("status", "NO_COMMAND", "agentId", agentId);
        }
        Map<String, Object> candidate = normalizeRow(candidates.get(0));
        // Capabilities may change after enqueue (agent upgrade/downgrade or corrupted state).
        // Re-negotiate before changing the command row so an incompatible command is never leased.
        capabilityGate.requireDispatchable(commandMapper.findAgentCapabilities(agentId),
                String.valueOf(candidate.get("command_type")));
        int updated = commandMapper.dispatchCommand(candidate.get("id"), timestamp(now),
                timestamp(leaseExpiresAt), timestamp(now));
        if (updated == 0) {
            return Map.of("status", "NO_COMMAND", "agentId", agentId);
        }
        Map<String, Object> command = getById(String.valueOf(candidate.get("id")));
        eventWriter.recordEvent(context, "agent_command.dispatch", "agent_command",
                String.valueOf(command.get("id")), ((Number) command.get("attempts")).longValue(),
                candidate, command, "SUCCESS", "dispatch agent command",
                Map.of("agentId", agentId, "leaseExpiresAt", leaseExpiresAt.toString()));
        Map<String, Object> response = new LinkedHashMap<>(command);
        Map<String, Object> persistedPayload = PlatformJson.readMap(String.valueOf(command.get("payload_json")));
        String commandId = String.valueOf(command.get("id"));
        String commandType = String.valueOf(command.get("command_type"));
        Map<String, Object> enriched = persistedPayload;
        if (bytecodeExchange != null && commandType.startsWith("BYTECODE_")) {
            enriched = bytecodeExchange.enrichPayload(commandId, enriched);
        }
        if (scriptSessionExchange != null && commandType.startsWith("SCRIPT_")) {
            enriched = scriptSessionExchange.enrichPayload(commandId, enriched);
        }
        response.put("payload", enriched);
        return response;
    }

    @Transactional
    public Map<String, Object> ack(String commandId, RequestContext context, Map<String, Object> request) {
        Map<String, Object> current = getById(commandId);
        requireAgentProtocolOrManager(String.valueOf(current.get("agent_id")), context);
        String resultStatus = requiredString(request, "status", null);
        if (!"ACKED".equals(resultStatus) && !"FAILED".equals(resultStatus)) {
            throw PlatformException.badRequest("INVALID_FIELD", "status must be ACKED or FAILED");
        }
        Instant now = clock.instant();
        Map<String, Object> result = optionalMap(request, "result");
        boolean bytecodeDiagnostic = String.valueOf(current.get("command_type")).startsWith("BYTECODE_");
        Map<String, Object> persistedResult = bytecodeDiagnostic && bytecodeExchange != null
                ? bytecodeExchange.sanitizeForPersistence(result) : result;
        String errorMessage = optionalString(request, "errorMessage", null);
        // V1.7 M1 / W1: the agent echoes the dispatch epoch (attempts) it polled as the fencing
        // token. A stale owner whose lease was reclaimed sees a newer attempts and matches zero
        // rows; null preserves the V1.6 wire contract for agents that do not echo the epoch.
        Long expectedAttempts = expectedAttempts(request);
        int updatedCount = commandMapper.ackCommand(commandId, resultStatus,
                PlatformJson.write(persistedResult), errorMessage, timestamp(now), timestamp(now),
                expectedAttempts);
        if (updatedCount == 0) {
            // V1.7 M1-A §8.1: zero rows means the acker is not the current lease owner -- a stale
            // epoch (re-dispatch bumped attempts), a missing epoch on a re-dispatched command, an
            // already-terminal command, or a command that was never dispatched. Terminal state is
            // immutable and a duplicate/delayed ack never re-advances rollout/unload/audit, so we
            // fail closed with the fencing conflict rather than silently replaying the side effects.
            // Reload after the conditional UPDATE. The command may have been re-dispatched between
            // the initial read and the ACK, so the pre-update snapshot can report a stale epoch.
            Map<String, Object> latest = getById(commandId);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("commandId", commandId);
            details.put("status", latest.get("status"));
            details.put("expectedAttempts", expectedAttempts);
            details.put("currentAttempts", latest.get("attempts"));
            throw PlatformException.conflict("AGENT_COMMAND_STATE_CONFLICT",
                    "Agent command ack fenced out: stale/missing dispatch epoch, terminal state, "
                            + "or not currently dispatched",
                    details);
        }
        Map<String, Object> updated = getById(commandId);
        if (bytecodeDiagnostic && bytecodeExchange != null) {
            if ("ACKED".equals(resultStatus)) bytecodeExchange.complete(commandId, result);
            else bytecodeExchange.fail(commandId, errorMessage == null ? "diagnostic failed" : errorMessage);
        }
        if (String.valueOf(current.get("command_type")).startsWith("SCRIPT_") && scriptSessionExchange != null) {
            if ("ACKED".equals(resultStatus)) {
                scriptSessionExchange.complete(commandId, result);
            } else {
                scriptSessionExchange.fail(commandId,
                        errorMessage == null ? "script command failed" : errorMessage, result);
            }
        }
        if ("RESOLVE_TARGET".equals(String.valueOf(current.get("command_type")))
                && targetResolutionExchange != null) {
            if ("ACKED".equals(resultStatus)) {
                targetResolutionExchange.complete(commandId, result);
            } else {
                targetResolutionExchange.fail(commandId,
                        errorMessage == null ? "target resolution failed" : errorMessage, result);
            }
        }
        eventWriter.recordEvent(context, "agent_command.ack", "agent_command", commandId,
                ((Number) updated.get("attempts")).longValue(), current, updated, resultStatus,
                optionalString(request, "reason", "ack agent command"),
                Map.of("agentId", updated.get("agent_id"), "commandType", updated.get("command_type")));
        if ("STOP_AGENT".equals(String.valueOf(updated.get("command_type")))) {
            commandMapper.updateAgentStatus(updated.get("agent_id"),
                    "ACKED".equals(resultStatus) ? "DISABLED" : "ACTIVE", timestamp(now));
        }
        // V1.5 §4.4/§5: an ACKED command whose inner ApplyChainStatus is a chain failure
        // (e.g. TARGET_DRIFTED after a hot update) must surface as a rollout failure, not a
        // silent success. The command-level ACKED only means the agent processed the command;
        // the chain status says whether the rule actually applied.
        boolean rolloutSuccess = "ACKED".equals(resultStatus);
        String commandType = String.valueOf(current.get("command_type"));
        String rolloutError = errorMessage;
        if (rolloutSuccess && ("APPLY_RULE".equals(commandType) || "APPLY_CHAIN".equals(commandType))
                && isChainFailureStatus(result)) {
            rolloutSuccess = false;
            if (rolloutError == null) {
                rolloutError = "agent reported chain status " + result.get("status");
            }
        }
        advanceRolloutFromCommand(context, commandId, rolloutSuccess, rolloutError, result);
        advanceRollbackFromCommand(context, updated);
        return updated;
    }

    private void advanceRollbackFromCommand(RequestContext context, Map<String, Object> command) {
        Object rollbackValue = command.get("rollback_execution_id");
        if (rollbackValue == null || String.valueOf(rollbackValue).isBlank()) {
            return;
        }
        String rollbackId = String.valueOf(rollbackValue);
        List<Map<String, Object>> commands = normalizeRows(commandMapper.commandsByRollbackExecution(rollbackId));
        if (commands.isEmpty() || commands.stream().anyMatch(row ->
                !"ACKED".equals(String.valueOf(row.get("status")))
                        && !"FAILED".equals(String.valueOf(row.get("status"))))) {
            return;
        }
        boolean succeeded = commands.stream()
                .allMatch(row -> "ACKED".equals(String.valueOf(row.get("status"))));
        Map<String, Object> rollback = normalizeRow(commandMapper.rollbackExecution(rollbackId));
        if (!"DISPATCHED".equals(String.valueOf(rollback.get("status")))) {
            return;
        }
        Instant now = clock.instant();
        String rollbackStatus = succeeded ? "SUCCEEDED" : "FAILED";
        int rollbackUpdated = commandMapper.completeRollbackExecution(rollbackId, rollbackStatus, timestamp(now));
        if (rollbackUpdated == 0) {
            return;
        }
        String operationPlanId = String.valueOf(rollback.get("operation_plan_id"));
        String operationStatus = succeeded ? "UNLOADED" : "FAILED";
        commandMapper.updateUnloadingOperationStatus(operationPlanId, operationStatus, context.actor(), timestamp(now));
        if (succeeded) {
            commandMapper.markRuntimeStatusesRemovedForOperation(operationPlanId, timestamp(now));
        }
        Map<String, Object> updatedOperation = normalizeRow(commandMapper.operationPlan(operationPlanId));
        eventWriter.recordEvent(context, "operation_plan.unload_complete", "operation_plan",
                operationPlanId, ((Number) updatedOperation.get("version")).longValue(),
                rollback, updatedOperation, operationStatus,
                succeeded ? "规则字节码卸载完成" : "规则字节码卸载失败",
                Map.of("rollbackExecutionId", rollbackId, "commandCount", commands.size()));
    }

    /**
     * V1.5 &sect;4.4/§5: ApplyChainStatus values that represent a real failure to apply the
     * rule chain, so an ACKED command carrying one of them surfaces as a rollout failure
     * rather than a silent success. {@code APPLIED}, {@code NO_OP}, {@code IDEMPOTENT_REPLAY}
     * and {@code STALE_COMMAND} are not failures; {@code DEGRADED} is left as a partial
     * success (the rule applied, in degraded mode) so the rollout still advances.
     */
    private static final java.util.Set<String> CHAIN_FAILURE_STATUSES = java.util.Set.of(
            "TARGET_DRIFTED", "COMPILE_FAILED", "TRANSFORM_FAILED", "VERIFICATION_FAILED",
            "COEXISTENCE_UNSAFE", "TARGET_NOT_FOUND", "REJECTED");

    /** V1.5 §4.4/§5: whether an ACKED command's inner ApplyChainStatus represents a real apply failure. */
    static boolean isChainFailureStatus(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        Object status = result.get("status");
        return status != null && CHAIN_FAILURE_STATUSES.contains(String.valueOf(status));
    }

    private void advanceRolloutFromCommand(RequestContext context, String commandId, boolean success,
                                           String errorMessage, Map<String, Object> result) {
        List<Map<String, Object>> executions = commandMapper.executionsByCommand(commandId);
        if (executions.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        for (Map<String, Object> rawExecution : executions) {
            Map<String, Object> execution = normalizeRow(rawExecution);
            String executionId = String.valueOf(execution.get("id"));
            String newStatus = success ? "SUCCEEDED" : "FAILED";
            long executionVersion = ((Number) execution.get("version")).longValue();
            int executionUpdated = commandMapper.completeExecution(executionId, newStatus,
                    success ? null : errorMessage, timestamp(now), context.actor(), timestamp(now), executionVersion);
            if (executionUpdated == 0) {
                continue;
            }
            Map<String, Object> updatedExecution = normalizeRow(commandMapper.rolloutExecution(executionId));
            eventWriter.recordEvent(context, "rollout_instance_execution.agent_ack",
                    "rollout_instance_execution", executionId, 1, execution, updatedExecution,
                    success ? "SUCCESS" : "FAILED", "agent command completed",
                    Map.of("commandId", commandId, "result", result));
            advanceOperation(context, String.valueOf(execution.get("operation_plan_id")));
        }
    }

    private void advanceOperation(RequestContext context, String operationPlanId) {
        Map<String, Object> operation = normalizeRow(commandMapper.operationPlan(operationPlanId));
        if (!"RUNNING".equals(String.valueOf(operation.get("status")))) {
            return;
        }
        List<Map<String, Object>> executions = normalizeRows(commandMapper.executionsByOperation(operationPlanId));
        if (executions.isEmpty()) {
            return;
        }
        boolean allTerminal = executions.stream()
                .allMatch(row -> terminalExecutionStatus(String.valueOf(row.get("status"))));
        if (!allTerminal) {
            return;
        }
        boolean allSucceeded = executions.stream()
                .allMatch(row -> "SUCCEEDED".equals(String.valueOf(row.get("status"))));
        if (!allSucceeded && automaticUnloadEnabled(operation)) {
            startAutomaticUnload(context, operation, executions);
            return;
        }
        String newStatus = allSucceeded ? "SUCCEEDED" : "FAILED";
        Instant now = clock.instant();
        long version = ((Number) operation.get("version")).longValue() + 1;
        long currentVersion = ((Number) operation.get("version")).longValue();
        int operationUpdated = commandMapper.completeRunningOperation(operationPlanId, newStatus, version,
                context.actor(), timestamp(now), currentVersion);
        if (operationUpdated == 0) {
            return;
        }
        Map<String, Object> updatedOperation = normalizeRow(commandMapper.operationPlan(operationPlanId));
        if (allSucceeded && "rule".equals(String.valueOf(operation.get("resource_type")))) {
            String ruleId = String.valueOf(operation.get("resource_id"));
            long ruleVersion = ((Number) operation.get("resource_version")).longValue();
            List<Map<String, Object>> successfulExecutions = normalizeRows(commandMapper.successfulExecutions(operationPlanId));
            for (Map<String, Object> execution : successfulExecutions) {
                String instanceId = String.valueOf(execution.get("instance_id"));
                int runtimeStatusUpdated = commandMapper.updateRuleRuntimeStatusActive(ruleId, ruleVersion,
                        instanceId, timestamp(now));
                if (runtimeStatusUpdated == 0) {
                    commandMapper.insertRuleRuntimeStatus(businessIdService.nextId("rule_runtime_status",
                                    eventWriter.rolloutBusinessName("rule", ruleId)), ruleId, ruleVersion,
                            instanceId, timestamp(now));
                }
            }
        }
        eventWriter.recordEvent(context, "operation_plan.auto_complete", "operation_plan", operationPlanId,
                version, operation, updatedOperation, newStatus, "rollout operation completed",
                Map.of("executionCount", executions.size()));
    }

    private boolean terminalExecutionStatus(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private boolean automaticUnloadEnabled(Map<String, Object> operation) {
        Map<String, Object> strategy =
                PlatformJson.readMap(String.valueOf(operation.get("strategy_json")));
        Object value = strategy.containsKey("automaticUnload")
                ? strategy.get("automaticUnload")
                : strategy.getOrDefault("automaticRollback", true);
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private void startAutomaticUnload(RequestContext context, Map<String, Object> operation,
                                      List<Map<String, Object>> executions) {
        String operationPlanId = String.valueOf(operation.get("id"));
        Instant now = clock.instant();
        int transitioned = commandMapper.transitionOperationToUnloading(operationPlanId, context.actor(),
                timestamp(now), ((Number) operation.get("version")).longValue());
        if (transitioned == 0) {
            return;
        }
        String rollbackId = businessIdService.nextId("rollback_execution",
                eventWriter.rolloutBusinessName(String.valueOf(operation.get("resource_type")),
                        String.valueOf(operation.get("resource_id"))));
        commandMapper.insertRollbackExecution(rollbackId, operationPlanId, "实例执行失败后自动卸载",
                context.actor(), timestamp(now));
        // V1.4: auto-unload is a precise per-class rollback, not a coarse RESET_ALL.
        // Resolve the failed rule's target class and dispatch RESET_CLASS so only the
        // affected class is retransformed with the remaining Kairo plan; other classes
        // and other agents' advice are preserved.
        String ruleId = String.valueOf(operation.get("resource_id"));
        Object ruleVersion = operation.get("resource_version");
        String classId = "";
        String className = "";
        if (ruleUnloadMapper != null) {
            List<Map<String, Object>> targets = normalizeRows(
                    ruleUnloadMapper.ruleTarget(ruleId, ruleVersion));
            if (!targets.isEmpty()) {
                Map<String, Object> target = targets.get(0);
                className = String.valueOf(target.getOrDefault("class_name", ""));
                Map<String, Object> matcher = PlatformJson.readMap(String.valueOf(
                        target.getOrDefault("matcher_json", "{}")));
                classId = String.valueOf(matcher.getOrDefault("classId", className));
            }
        }
        List<Map<String, Object>> agents = normalizeRows(commandMapper.activeAgentsForOperation(operationPlanId));
        for (Map<String, Object> agent : agents) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("commandType", "RESET_CLASS");
            payload.put("operationPlanId", operationPlanId);
            payload.put("rollbackExecutionId", rollbackId);
            payload.put("ruleId", ruleId);
            payload.put("ruleVersion", ruleVersion);
            payload.put("instanceId", agent.get("instance_id"));
            payload.put("classId", classId);
            payload.put("className", className);
            enqueue(context, String.valueOf(agent.get("id")), "RESET_CLASS",
                    payload, "unload:" + operationPlanId + ":" + agent.get("id"), 10, now);
        }
        if (agents.isEmpty()) {
            commandMapper.markRollbackSucceeded(rollbackId, timestamp(now));
            commandMapper.markUnloadingOperationUnloadedWithoutAgents(operationPlanId, context.actor(), timestamp(now));
        }
        Map<String, Object> current = normalizeRow(commandMapper.operationPlan(operationPlanId));
        eventWriter.recordEvent(context, "operation_plan.auto_unload", "operation_plan",
                operationPlanId, ((Number) current.get("version")).longValue(),
                operation, current, "UNLOADING", "实例执行失败，已启动精确卸载",
                Map.of("rollbackExecutionId", rollbackId,
                        "executionCount", executions.size(),
                        "commandCount", agents.size(),
                        "classId", classId,
                        "commandType", "RESET_CLASS"));
    }

    private String commandBusinessName(String commandType, Map<String, Object> payload) {
        Object resourceType = payload.getOrDefault("resourceType", "rule");
        Object resourceId = payload.get("resourceId");
        if (resourceId == null) {
            resourceId = payload.get("ruleId");
        }
        if (resourceId != null && !String.valueOf(resourceId).isBlank()) {
            return eventWriter.rolloutBusinessName(String.valueOf(resourceType), String.valueOf(resourceId));
        }
        Object operationPlanId = payload.get("operationPlanId");
        if (operationPlanId != null && !String.valueOf(operationPlanId).isBlank()) {
            Map<String, Object> rawOperation = commandMapper.operationResource(operationPlanId);
            if (rawOperation != null) {
                Map<String, Object> operation = normalizeRow(rawOperation);
                return eventWriter.rolloutBusinessName(String.valueOf(operation.get("resource_type")),
                        String.valueOf(operation.get("resource_id")));
            }
        }
        return commandType;
    }

    private void requireExistingAgent(String agentId) {
        if (commandMapper.countAgent(agentId) == 0) {
            throw PlatformException.notFound("agent_instance", agentId);
        }
    }

    private void requireAgentProtocolOrManager(String agentId, RequestContext context) {
        if (agentId.equals(context.actor()) && "agent".equals(context.identitySource())) {
            return;
        }
        rbacService.require(context, "AGENT_MANAGE");
    }

    private Map<String, Object> getById(String id) {
        Map<String, Object> command = commandMapper.commandById(id);
        if (command == null) {
            throw PlatformException.notFound("agent_command", id);
        }
        return normalizeRow(command);
    }

    private Map<String, Object> getByIdempotencyKey(String idempotencyKey) {
        return normalizeRow(commandMapper.commandByIdempotencyKey(idempotencyKey));
    }

    private List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::normalizeRow).toList();
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(), value));
        return normalized;
    }

    private Map<String, Object> optionalMap(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return PlatformJson.stringKeyMap(map);
        }
        if (value instanceof String text) {
            return PlatformJson.readMap(text);
        }
        throw PlatformException.badRequest("INVALID_FIELD", "Field must be an object: " + key);
    }

    private String requiredString(Map<String, Object> request, String key, String defaultValue) {
        String value = optionalString(request, key, defaultValue);
        if (value == null || value.isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: " + key);
        }
        return value;
    }

    private String optionalString(Map<String, Object> request, String key, String defaultValue) {
        Object value = request.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private long optionalLong(Map<String, Object> request, String key, long defaultValue) {
        Object value = request.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    /**
     * V1.7 M1 / W1: the agent's echoed dispatch epoch ({@code attempts}) used as the ack fencing
     * token, or {@code null} when the agent did not send one (V1.6 wire contract fallback).
     */
    private Long expectedAttempts(Map<String, Object> request) {
        Object value = request.get("expectedAttempts");
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof String) {
            try {
                return new java.math.BigDecimal(String.valueOf(value)).longValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
                // Converted below into the stable public validation error rather than a 500 or a
                // truncated fractional epoch that could accidentally authorize the wrong owner.
            }
        }
        throw PlatformException.badRequest("INVALID_FIELD",
                "expectedAttempts must be an integer dispatch epoch");
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
