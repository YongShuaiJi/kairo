package com.example.kairo.platform.attach;

import com.example.kairo.platform.persistence.mapper.AttachExecutorCommandMapper;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.RbacService;
import com.example.kairo.platform.service.RequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public final class AttachExecutorCommandService {

    private final AttachExecutorCommandMapper commandMapper;
    private final RbacService rbacService;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public AttachExecutorCommandService(AttachExecutorCommandMapper commandMapper, RbacService rbacService) {
        this(commandMapper, rbacService, Clock.systemUTC());
    }

    AttachExecutorCommandService(AttachExecutorCommandMapper commandMapper, RbacService rbacService, Clock clock) {
        this.commandMapper = commandMapper;
        this.rbacService = rbacService;
        this.clock = clock;
    }

    public Map<String, Object> enqueue(RequestContext context, String executorId, String instanceId,
                                       String commandType, String processId, String agentJar,
                                       String agentArgs, Map<String, Object> payload,
                                       String idempotencyKey, int maxAttempts) {
        rbacService.require(context, "AGENT_MANAGE");
        Instant now = clock.instant();
        String commandId = "attach-command-" + java.util.UUID.randomUUID();
        Map<String, Object> existing = commandMapper.findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return commandResult(existing);
        }
        commandMapper.insertCommand(commandId, executorId, instanceId, commandType, "PENDING", processId,
                agentJar, agentArgs, json(payload), idempotencyKey, Math.max(1, maxAttempts),
                timestamp(now), timestamp(now));
        return commandResult(commandMapper.findById(commandId));
    }

    public Map<String, Object> pollNext(String executorId, RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "AGENT_MANAGE");
        long waitMillis = Math.min(30_000L, Math.max(0L, optionalLong(request, "waitMillis", 25_000L)));
        long leaseSeconds = Math.min(300L, Math.max(5L, optionalLong(request, "leaseSeconds", 30L)));
        Instant deadline = clock.instant().plusMillis(waitMillis);
        while (true) {
            heartbeatExecutor(executorId, leaseSeconds);
            Map<String, Object> command = claimNext(executorId, context.actor(), leaseSeconds);
            if (command != null) {
                return commandPayload(command);
            }
            if (!clock.instant().isBefore(deadline)) {
                return Map.of("status", "NO_COMMAND", "executorId", executorId);
            }
            try {
                Thread.sleep(Math.min(500L, Math.max(50L, deadline.toEpochMilli() - clock.instant().toEpochMilli())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Map.of("status", "NO_COMMAND", "executorId", executorId, "interrupted", true);
            }
        }
    }

    public Map<String, Object> heartbeat(String executorId, RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "AGENT_MANAGE");
        long leaseSeconds = Math.min(300L, Math.max(5L, optionalLong(request, "leaseSeconds", 30L)));
        heartbeatExecutor(executorId, leaseSeconds);
        return Map.of(
                "status", "ACTIVE",
                "executorId", executorId,
                "leaseExpiresAt", clock.instant().plusSeconds(leaseSeconds).toString()
        );
    }

    public Map<String, Object> ack(String commandId, RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "AGENT_MANAGE");
        Map<String, Object> command = command(commandId);
        String status = string(request, "status", "SUCCEEDED").toUpperCase(java.util.Locale.ROOT);
        if (!List.of("SUCCEEDED", "FAILED").contains(status)) {
            throw PlatformException.badRequest("INVALID_COMMAND_STATUS",
                    "Attach executor command status must be SUCCEEDED or FAILED");
        }
        Instant now = clock.instant();
        commandMapper.completeCommand(commandId, status, json(request.getOrDefault("result", Map.of())),
                string(request, "message", ""), timestamp(now), timestamp(now));
        if ("SUCCEEDED".equals(status)) {
            commandMapper.markInstanceAttached(command.get("instance_id"), timestamp(now));
        }
        return commandResult(commandMapper.findById(commandId));
    }

    private Map<String, Object> claimNext(String executorId, String actor, long leaseSeconds) {
        Instant now = clock.instant();
        Instant leaseExpiresAt = now.plusSeconds(leaseSeconds);
        return commandMapper.claimNext(executorId, actor, timestamp(leaseExpiresAt), timestamp(now));
    }

    private void heartbeatExecutor(String executorId, long leaseSeconds) {
        Instant now = clock.instant();
        int updated = commandMapper.heartbeatExecutor(executorId, timestamp(now),
                timestamp(now.plusSeconds(leaseSeconds)), timestamp(now));
        if (updated != 1) {
            throw PlatformException.notFound("attach_executor", executorId);
        }
        commandMapper.heartbeatTargets(executorId, timestamp(now), timestamp(now));
        commandMapper.heartbeatTargetInstances(executorId, timestamp(now),
                timestamp(now.plusSeconds(leaseSeconds)), timestamp(now));
    }

    private Map<String, Object> command(String commandId) {
        Map<String, Object> command = commandMapper.findById(commandId);
        if (command == null) {
            throw PlatformException.notFound("attach_executor_command", commandId);
        }
        return command;
    }

    private Map<String, Object> commandPayload(Map<String, Object> command) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "COMMAND");
        result.put("commandId", command.get("id"));
        result.put("executorId", command.get("executor_id"));
        result.put("instanceId", command.get("instance_id"));
        result.put("commandType", command.get("command_type"));
        result.put("processId", command.get("process_id"));
        result.put("agentJar", command.get("agent_jar"));
        result.put("agentArgs", command.get("agent_args"));
        result.put("attempt", command.get("attempt"));
        result.put("leaseExpiresAt", command.get("lease_expires_at"));
        result.put("payload", parseJson(String.valueOf(command.get("payload_json"))));
        return result;
    }

    private Map<String, Object> commandResult(Map<String, Object> command) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", command.get("id"));
        result.put("executorId", command.get("executor_id"));
        result.put("instanceId", command.get("instance_id"));
        result.put("commandType", command.get("command_type"));
        result.put("status", command.get("status"));
        result.put("attempt", command.get("attempt"));
        result.put("leaseExpiresAt", command.get("lease_expires_at"));
        result.put("createdAt", command.get("created_at"));
        result.put("updatedAt", command.get("updated_at"));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            Object value = mapper.readValue(json, Object.class);
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize JSON", e);
        }
    }

    private String string(Map<String, Object> request, String key, String defaultValue) {
        Object value = request.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
    }

    private long optionalLong(Map<String, Object> request, String key, long defaultValue) {
        Object value = request.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
