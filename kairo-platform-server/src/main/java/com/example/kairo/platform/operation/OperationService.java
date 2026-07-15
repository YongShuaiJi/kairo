package com.example.kairo.platform.operation;

import com.example.kairo.api.error.ApiError;
import com.example.kairo.api.error.ErrorCategory;
import com.example.kairo.api.operation.Operation;
import com.example.kairo.api.operation.OperationEvent;
import com.example.kairo.api.operation.OperationStatus;
import com.example.kairo.api.operation.OperationType;
import com.example.kairo.api.write.ImpactSummary;
import com.example.kairo.api.write.RiskLevel;
import com.example.kairo.platform.persistence.mapper.OperationMapper;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.PlatformJson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Unified long-running Operation service (V1.6 &sect;5.1). Converges agent
 * command, publish, rollback, unload, preview, script-session, reconcile and
 * automation trial/promote/revert into a single queryable, versioned resource
 * with an append-only event stream.
 *
 * <p>Transitions use optimistic locking on {@code version}; a stale transition
 * yields {@code RESOURCE_VERSION_CONFLICT}. Operations created with an
 * {@code idempotencyKey} are de-duplicated so a replayed write returns the
 * original operation id.
 */
@Service
public final class OperationService {

    private final OperationMapper operationMapper;
    private final Clock clock;

    @Autowired
    public OperationService(OperationMapper operationMapper) {
        this(operationMapper, Clock.systemUTC());
    }

    OperationService(OperationMapper operationMapper, Clock clock) {
        this.operationMapper = operationMapper;
        this.clock = clock;
    }

    /** Request to start a new operation. */
    public record StartRequest(
            OperationType type,
            String resourceType,
            String resourceId,
            RiskLevel riskLevel,
            ImpactSummary impact,
            String actor,
            String correlationId,
            String idempotencyKey,
            String automationSessionId
    ) {
        public StartRequest {
            Objects.requireNonNull(type, "type");
            resourceType = resourceType == null ? "" : resourceType;
            resourceId = resourceId == null ? "" : resourceId;
            riskLevel = riskLevel == null ? RiskLevel.LOW : riskLevel;
            impact = impact == null
                    ? new ImpactSummary(List.of(), "", "", true, 0)
                    : impact;
            actor = actor == null ? "" : actor;
            correlationId = correlationId == null ? "" : correlationId;
            idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey;
            automationSessionId = automationSessionId == null || automationSessionId.isBlank() ? null : automationSessionId;
        }
    }

    /** Start a PENDING operation, de-duplicating by idempotency key when present. */
    public String start(StartRequest request) {
        if (request.idempotencyKey() != null) {
            Map<String, Object> existing = operationMapper.findByIdempotencyKey(request.idempotencyKey());
            if (existing != null) {
                return String.valueOf(existing.get("id"));
            }
        }
        String id = "op-" + UUID.randomUUID();
        Instant now = clock.instant();
        operationMapper.insertOperation(
                id,
                request.type().name(),
                OperationStatus.PENDING.name(),
                request.resourceType(),
                request.resourceId(),
                request.riskLevel().name(),
                PlatformJson.write(toMap(request.impact())),
                -1,
                "{}",
                null,
                null,
                request.automationSessionId(),
                null,
                request.correlationId(),
                request.actor(),
                request.idempotencyKey(),
                Timestamp.from(now),
                Timestamp.from(now));
        recordEvent(id, "CREATED", request.actor(), Map.of("type", request.type().name()));
        return id;
    }

    public void running(String id) {
        transition(id, OperationStatus.RUNNING, -1, null, null, null);
        recordEvent(id, "DISPATCHED", "", Map.of());
    }

    public void succeed(String id, Map<String, Object> result) {
        transition(id, OperationStatus.SUCCEEDED, 100,
                result == null ? Map.of() : result, null, null);
        recordEvent(id, "COMPLETED", "", Map.of("result", result == null ? Map.of() : result));
    }

    public void fail(String id, ApiError error) {
        String errorJson = error == null ? null : PlatformJson.write(toMap(error));
        transition(id, OperationStatus.FAILED, -1, null, errorJson, null);
        recordEvent(id, "FAILED", "", error == null ? Map.of() : Map.of("code", error.code()));
    }

    public void cancel(String id) {
        transition(id, OperationStatus.CANCELLED, -1, null, null, null);
        recordEvent(id, "CANCELLED", "", Map.of());
    }

    public void markReverted(String id, String revertOperationId) {
        transition(id, OperationStatus.REVERTED, -1, null, null, revertOperationId);
        recordEvent(id, "REVERTED", "", Map.of("revertOperationId", revertOperationId));
    }

    /** Mark a previously succeeded operation as reverted by recording the revert op id. */
    public void recordRevertOf(String originalId, String revertOperationId) {
        Map<String, Object> existing = requireExisting(originalId);
        long version = longVal(existing.get("version"));
        Instant now = clock.instant();
        int updated = operationMapper.transition(originalId, OperationStatus.REVERTED.name(),
                -1, String.valueOf(existing.get("result_json")), null,
                revertOperationId, Timestamp.from(now), Timestamp.from(now), version);
        if (updated == 0) {
            throw PlatformException.conflict("RESOURCE_VERSION_CONFLICT",
                    "操作版本已变化，无法标记撤销", Map.of("operationId", originalId));
        }
        recordEvent(originalId, "REVERTED", "", Map.of("revertOperationId", revertOperationId));
    }

    public void linkAgentCommand(String id, String agentCommandId) {
        operationMapper.linkAgentCommand(id, agentCommandId, Timestamp.from(clock.instant()));
    }

    /** Find an operation linked to an agent command, or null. */
    public String findByAgentCommand(String agentCommandId) {
        Map<String, Object> row = operationMapper.findByAgentCommandId(agentCommandId);
        return row == null ? null : String.valueOf(row.get("id"));
    }

    public Operation get(String id) {
        return toOperation(requireExisting(id));
    }

    public Operation findByIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Map<String, Object> row = operationMapper.findByIdempotencyKey(key);
        return row == null ? null : toOperation(row);
    }

    public List<Operation> list(String status, int limit) {
        return operationMapper.listRecent(blankToNull(status), Math.max(1, Math.min(limit, 200)))
                .stream().map(OperationService::toOperation).toList();
    }

    public List<Operation> listByResource(String resourceType, String resourceId) {
        return operationMapper.listByResource(resourceType, resourceId)
                .stream().map(OperationService::toOperation).toList();
    }

    public List<Operation> listBySession(String sessionId) {
        return operationMapper.listBySession(sessionId)
                .stream().map(OperationService::toOperation).toList();
    }

    public List<OperationEvent> events(String id) {
        requireExisting(id);
        return operationMapper.listEvents(id).stream()
                .map(OperationService::toEvent)
                .toList();
    }

    /**
     * Lifecycle events keyed by a non-operation id (e.g. an automation-session id),
     * without requiring an Operation row to exist. Used to surface session-level
     * events recorded via {@link #recordEvent(String, String, String, Map)}.
     */
    public List<OperationEvent> lifecycleEvents(String id) {
        return operationMapper.listEvents(id).stream()
                .map(OperationService::toEvent)
                .toList();
    }

    public void recordEvent(String id, String type, String actor, Map<String, Object> detail) {
        Long seq = operationMapper.nextEventSequence(id);
        operationMapper.insertEvent("ope-" + UUID.randomUUID(), id, seq,
                type, actor == null ? "" : actor,
                PlatformJson.write(detail == null ? Map.of() : detail),
                Timestamp.from(clock.instant()));
    }

    private void transition(String id, OperationStatus status, int progress,
                            Map<String, Object> result, String errorJson, String revertOperationId) {
        Map<String, Object> existing = requireExisting(id);
        long version = longVal(existing.get("version"));
        Instant now = clock.instant();
        Timestamp completedAt = status == OperationStatus.SUCCEEDED
                || status == OperationStatus.FAILED
                || status == OperationStatus.CANCELLED
                || status == OperationStatus.REVERTED
                || status == OperationStatus.TIMEOUT
                ? Timestamp.from(now) : null;
        String resultJson = result == null ? String.valueOf(existing.get("result_json"))
                : PlatformJson.write(result);
        int updated = operationMapper.transition(id, status.name(), progress, resultJson,
                errorJson == null ? nullableString(existing.get("error_json")) : errorJson,
                revertOperationId, completedAt, Timestamp.from(now), version);
        if (updated == 0) {
            throw PlatformException.conflict("RESOURCE_VERSION_CONFLICT",
                    "操作版本已变化，无法更新状态", Map.of("operationId", id));
        }
    }

    private Map<String, Object> requireExisting(String id) {
        Map<String, Object> row = operationMapper.findById(id);
        if (row == null) {
            throw PlatformException.notFound("operation", id);
        }
        return row;
    }

    private static Operation toOperation(Map<String, Object> row) {
        return new Operation(
                str(row.get("id")),
                OperationType.valueOf(str(row.get("operation_type"))),
                OperationStatus.valueOf(str(row.get("status"))),
                str(row.get("resource_type")),
                str(row.get("resource_id")),
                RiskLevel.valueOf(str(row.get("risk_level"))),
                toImpact(row.get("impact_json")),
                intVal(row.get("progress")),
                toMap(row.get("result_json")),
                toApiError(row.get("error_json")),
                nullableString(row.get("revert_operation_id")),
                str(row.get("correlation_id")),
                str(row.get("actor")),
                timestampMillis(row.get("created_at")),
                timestampMillis(row.get("updated_at")),
                timestampMillisOrNeg(row.get("completed_at")));
    }

    private static OperationEvent toEvent(Map<String, Object> row) {
        return new OperationEvent(
                str(row.get("operation_id")),
                longVal(row.get("sequence")),
                str(row.get("event_type")),
                timestampMillis(row.get("occurred_at")),
                str(row.get("actor")),
                toMap(row.get("detail_json")));
    }

    @SuppressWarnings("unchecked")
    private static ImpactSummary toImpact(Object json) {
        if (json == null) {
            return new ImpactSummary(List.of(), "", "", true, 0);
        }
        Map<String, Object> map = PlatformJson.readMap(String.valueOf(json));
        List<ImpactSummary.AffectedResource> resources = new ArrayList<>();
        Object arr = map.get("affectedResources");
        if (arr instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    resources.add(new ImpactSummary.AffectedResource(
                            String.valueOf(m.get("resourceType")),
                            String.valueOf(m.get("resourceId"))));
                }
            }
        }
        return new ImpactSummary(resources,
                strVal(map.get("scope")),
                strVal(map.get("blastRadius")),
                Boolean.TRUE.equals(map.get("reversible")),
                intVal(map.get("estimatedAffectedInstances")));
    }

    private static ApiError toApiError(Object json) {
        if (json == null || String.valueOf(json).isBlank()) {
            return null;
        }
        Map<String, Object> m = PlatformJson.readMap(String.valueOf(json));
        return ApiError.of(strVal(m.get("code")), strVal(m.get("message")),
                ErrorCategory.valueOf(strVal(m.getOrDefault("category", "INTERNAL"))),
                Boolean.TRUE.equals(m.get("retryable")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        String s = String.valueOf(value);
        if (s.isBlank()) {
            return Map.of();
        }
        return PlatformJson.readMap(s);
    }

    private static Map<String, Object> toMap(ImpactSummary impact) {
        List<Map<String, Object>> resources = impact.affectedResources().stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("resourceType", r.resourceType());
                    m.put("resourceId", r.resourceId());
                    return m;
                })
                .toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("affectedResources", resources);
        m.put("scope", impact.scope());
        m.put("blastRadius", impact.blastRadius());
        m.put("reversible", impact.reversible());
        m.put("estimatedAffectedInstances", impact.estimatedAffectedInstances());
        return m;
    }

    private static Map<String, Object> toMap(ApiError error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", error.code());
        m.put("message", error.message());
        m.put("category", error.category().name());
        m.put("retryable", error.retryable());
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String strVal(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String nullableString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static long longVal(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return o == null ? 0L : Long.parseLong(String.valueOf(o));
    }

    private static int intVal(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return o == null ? -1 : Integer.parseInt(String.valueOf(o));
    }

    private static long timestampMillis(Object o) {
        if (o instanceof Timestamp t) {
            return t.getTime();
        }
        if (o instanceof java.util.Date d) {
            return d.getTime();
        }
        return o == null ? 0L : Timestamp.valueOf(String.valueOf(o)).getTime();
    }

    private static long timestampMillisOrNeg(Object o) {
        if (o == null) {
            return -1L;
        }
        return timestampMillis(o);
    }
}
