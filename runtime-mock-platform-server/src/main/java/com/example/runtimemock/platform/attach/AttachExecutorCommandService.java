package com.example.runtimemock.platform.attach;

import com.example.runtimemock.platform.service.PlatformException;
import com.example.runtimemock.platform.service.RbacService;
import com.example.runtimemock.platform.service.RequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public final class AttachExecutorCommandService {

    private final JdbcTemplate jdbcTemplate;
    private final RbacService rbacService;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public AttachExecutorCommandService(JdbcTemplate jdbcTemplate, RbacService rbacService) {
        this(jdbcTemplate, rbacService, Clock.systemUTC());
    }

    AttachExecutorCommandService(JdbcTemplate jdbcTemplate, RbacService rbacService, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
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
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "select * from attach_executor_command where idempotency_key = ?", idempotencyKey);
        if (!existing.isEmpty()) {
            return commandResult(existing.get(0));
        }
        jdbcTemplate.update("""
                insert into attach_executor_command(
                    id, executor_id, instance_id, command_type, status, process_id,
                    agent_jar, agent_args, payload_json, idempotency_key, max_attempts,
                    created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, commandId, executorId, instanceId, commandType, "PENDING", processId,
                agentJar, agentArgs, json(payload), idempotencyKey, Math.max(1, maxAttempts),
                timestamp(now), timestamp(now));
        return commandResult(jdbcTemplate.queryForMap("select * from attach_executor_command where id = ?", commandId));
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
        jdbcTemplate.update("""
                update attach_executor_command
                   set status = ?,
                       result_json = ?,
                       error_message = ?,
                       finished_at = ?,
                       updated_at = ?
                 where id = ?
                """, status, json(request.getOrDefault("result", Map.of())),
                string(request, "message", ""), timestamp(now), timestamp(now), commandId);
        if ("SUCCEEDED".equals(status)) {
            jdbcTemplate.update("""
                    update instance
                       set load_mode = 'attach',
                           updated_at = ?
                     where id = ?
                    """, timestamp(now), command.get("instance_id"));
        }
        return commandResult(jdbcTemplate.queryForMap("select * from attach_executor_command where id = ?", commandId));
    }

    private Map<String, Object> claimNext(String executorId, String actor, long leaseSeconds) {
        Instant now = clock.instant();
        Instant leaseExpiresAt = now.plusSeconds(leaseSeconds);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                update attach_executor_command
                   set status = 'LEASED',
                       attempt = attempt + 1,
                       lease_owner = ?,
                       lease_expires_at = ?,
                       started_at = coalesce(started_at, ?),
                       updated_at = ?
                 where id = (
                       select id
                         from attach_executor_command
                        where executor_id = ?
                          and (
                              status = 'PENDING'
                              or (status = 'LEASED' and lease_expires_at < ?)
                          )
                          and attempt < max_attempts
                        order by created_at, id
                        for update skip locked
                        limit 1
                 )
                returning *
                """, actor, timestamp(leaseExpiresAt), timestamp(now), timestamp(now),
                executorId, timestamp(now));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void heartbeatExecutor(String executorId, long leaseSeconds) {
        Instant now = clock.instant();
        int updated = jdbcTemplate.update("""
                update attach_executor
                   set status = 'ACTIVE',
                       last_heartbeat_at = ?,
                       lease_expires_at = ?,
                       updated_at = ?
                 where id = ?
                """, timestamp(now), timestamp(now.plusSeconds(leaseSeconds)), timestamp(now), executorId);
        if (updated != 1) {
            throw PlatformException.notFound("attach_executor", executorId);
        }
        jdbcTemplate.update("""
                update attach_executor_target
                   set status = 'ACTIVE',
                       last_seen_at = ?,
                       updated_at = ?
                 where executor_id = ?
                   and status in ('ACTIVE', 'ONLINE')
                """, timestamp(now), timestamp(now), executorId);
        jdbcTemplate.update("""
                update instance
                   set status = case when environment_id is null then 'PENDING_ASSIGNMENT' else 'ACTIVE' end,
                       last_seen_at = ?,
                       lease_expires_at = ?,
                       updated_at = ?
                 where id in (
                       select instance_id
                         from attach_executor_target
                        where executor_id = ?
                          and status in ('ACTIVE', 'ONLINE')
                 )
                """, timestamp(now), timestamp(now.plusSeconds(leaseSeconds)), timestamp(now), executorId);
    }

    private Map<String, Object> command(String commandId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from attach_executor_command where id = ?", commandId);
        if (rows.isEmpty()) {
            throw PlatformException.notFound("attach_executor_command", commandId);
        }
        return rows.get(0);
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
