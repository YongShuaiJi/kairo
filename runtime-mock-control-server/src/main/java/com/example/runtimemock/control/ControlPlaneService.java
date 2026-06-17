package com.example.runtimemock.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class ControlPlaneService {

    private static final String GENESIS_HASH = "GENESIS";

    private final Clock clock;
    private final ObjectMapper canonicalMapper;
    private final ConcurrentMap<String, RecordingSession> recordingSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DatasetVersion> datasetVersions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReplayPlan> replayPlans = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AuditRecord> audits = new CopyOnWriteArrayList<>();

    ControlPlaneService() {
        this(Clock.systemUTC());
    }

    ControlPlaneService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.canonicalMapper = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }

    Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "storage", "in-memory",
                "recordingSessionCount", recordingSessions.size(),
                "datasetVersionCount", datasetVersions.size(),
                "replayPlanCount", replayPlans.size(),
                "auditCount", audits.size()
        );
    }

    List<RecordingSession> recordingSessions() {
        return recordingSessions.values().stream()
                .sorted(Comparator.comparing(RecordingSession::createdAt))
                .toList();
    }

    List<DatasetVersion> datasetVersions() {
        return datasetVersions.values().stream()
                .sorted(Comparator.comparing(DatasetVersion::createdAt))
                .toList();
    }

    List<ReplayPlan> replayPlans() {
        return replayPlans.values().stream()
                .sorted(Comparator.comparing(ReplayPlan::createdAt))
                .toList();
    }

    List<AuditRecord> audits() {
        return List.copyOf(audits);
    }

    RecordingSession createRecordingSession(Map<String, Object> request) {
        Instant now = clock.instant();
        String id = optionalString(request, "id", "rec-" + UUID.randomUUID());
        String actor = optionalString(request, "actor", "anonymous");
        RecordingSession session = new RecordingSession(
                id,
                requiredString(request, "application"),
                requiredString(request, "environment"),
                RecordingSessionStatus.DRAFT,
                1,
                now,
                now,
                actor,
                optionalLong(request, "maxEvents", 10_000),
                optionalLong(request, "ttlSeconds", 3_600),
                optionalMap(request, "target"),
                optionalMap(request, "quota")
        );
        RecordingSession previous = recordingSessions.putIfAbsent(id, session);
        if (previous != null) {
            throw ControlPlaneException.conflict("RECORDING_SESSION_ALREADY_EXISTS",
                    "Recording session already exists: " + id, Map.of("id", id));
        }
        audit(actor, "recording_session.create", "recording_session", id, session.version(),
                optionalString(request, "correlationId", ""),
                "SUCCESS", optionalString(request, "reason", "create recording session"),
                Map.of("status", session.status().name()));
        return session;
    }

    RecordingSession transitionRecordingSession(String id, Map<String, Object> request) {
        String actor = optionalString(request, "actor", "anonymous");
        String reason = requiredString(request, "reason");
        String fencingToken = requiredString(request, "fencingToken");
        RecordingSessionStatus expectedStatus = requiredEnum(request, "expectedStatus", RecordingSessionStatus.class);
        RecordingSessionStatus targetStatus = requiredEnum(request, "targetStatus", RecordingSessionStatus.class);
        long expectedVersion = requiredLong(request, "expectedVersion");

        RecordingSession updated = recordingSessions.compute(id, (ignored, current) -> {
            if (current == null) {
                throw ControlPlaneException.notFound("recording_session", id);
            }
            assertExpectedState(current.status(), expectedStatus, current.version(), expectedVersion);
            if (!current.status().canTransitionTo(targetStatus)) {
                throw ControlPlaneException.conflict("RECORDING_SESSION_INVALID_TRANSITION",
                        "Cannot transition recording session from " + current.status() + " to " + targetStatus,
                        Map.of("id", id, "currentStatus", current.status().name(), "targetStatus", targetStatus.name()));
            }
            return current.transitionTo(targetStatus, clock.instant());
        });

        audit(actor, "recording_session.transition", "recording_session", id, updated.version(),
                optionalString(request, "correlationId", ""),
                "SUCCESS", reason,
                Map.of("from", expectedStatus.name(), "to", targetStatus.name(), "fencingToken", fencingToken));
        return updated;
    }

    DatasetVersion createDatasetVersion(Map<String, Object> request) {
        String actor = optionalString(request, "actor", "anonymous");
        String datasetId = requiredString(request, "datasetId");
        String sourceSessionId = requiredString(request, "sourceSessionId");
        RecordingSession sourceSession = recordingSessions.get(sourceSessionId);
        if (sourceSession == null) {
            throw ControlPlaneException.notFound("recording_session", sourceSessionId);
        }
        if (sourceSession.status() != RecordingSessionStatus.COMPLETED) {
            throw ControlPlaneException.conflict("SOURCE_SESSION_NOT_COMPLETED",
                    "Dataset can only be created from a completed recording session",
                    Map.of("sourceSessionId", sourceSessionId, "status", sourceSession.status().name()));
        }

        long version = nextDatasetVersion(datasetId);
        DatasetVersion datasetVersion = new DatasetVersion(
                datasetId,
                version,
                sourceSessionId,
                requiredString(request, "schemaHash"),
                requiredString(request, "manifestHash"),
                requiredString(request, "maskingHash"),
                optionalString(request, "retentionPolicy", "P30D"),
                clock.instant(),
                actor,
                optionalListOfMaps(request, "objectReferences")
        );
        datasetVersions.put(datasetVersion.id(), datasetVersion);
        audit(actor, "dataset_version.create", "dataset_version", datasetVersion.id(), version,
                optionalString(request, "correlationId", ""),
                "SUCCESS", optionalString(request, "reason", "create dataset version"),
                Map.of("datasetId", datasetId, "sourceSessionId", sourceSessionId));
        return datasetVersion;
    }

    ReplayPlan createReplayPlan(Map<String, Object> request) {
        Instant now = clock.instant();
        String actor = optionalString(request, "actor", "anonymous");
        String datasetId = requiredString(request, "datasetId");
        long datasetVersion = requiredLong(request, "datasetVersion");
        String datasetVersionId = datasetId + ":" + datasetVersion;
        if (!datasetVersions.containsKey(datasetVersionId)) {
            throw ControlPlaneException.notFound("dataset_version", datasetVersionId);
        }
        String id = optionalString(request, "id", "replay-" + UUID.randomUUID());
        ReplayPlan replayPlan = new ReplayPlan(
                id,
                1,
                datasetId,
                datasetVersion,
                requiredString(request, "targetEnvironment"),
                requiredString(request, "targetApplication"),
                PlanStatus.DRAFT,
                requiredString(request, "sideEffectPolicyHash"),
                requiredString(request, "comparisonPolicyHash"),
                now,
                now,
                actor,
                optionalMap(request, "executionPolicy")
        );
        ReplayPlan previous = replayPlans.putIfAbsent(id, replayPlan);
        if (previous != null) {
            throw ControlPlaneException.conflict("REPLAY_PLAN_ALREADY_EXISTS",
                    "Replay plan already exists: " + id, Map.of("id", id));
        }
        audit(actor, "replay_plan.create", "replay_plan", id, replayPlan.version(),
                optionalString(request, "correlationId", ""),
                "SUCCESS", optionalString(request, "reason", "create replay plan"),
                Map.of("datasetVersion", datasetVersionId, "status", replayPlan.status().name()));
        return replayPlan;
    }

    ReplayPlan transitionReplayPlan(String id, Map<String, Object> request) {
        String actor = optionalString(request, "actor", "anonymous");
        String reason = requiredString(request, "reason");
        String fencingToken = requiredString(request, "fencingToken");
        PlanStatus expectedStatus = requiredEnum(request, "expectedStatus", PlanStatus.class);
        PlanStatus targetStatus = requiredEnum(request, "targetStatus", PlanStatus.class);
        long expectedVersion = requiredLong(request, "expectedVersion");

        ReplayPlan updated = replayPlans.compute(id, (ignored, current) -> {
            if (current == null) {
                throw ControlPlaneException.notFound("replay_plan", id);
            }
            assertExpectedState(current.status(), expectedStatus, current.version(), expectedVersion);
            if (!current.status().canTransitionTo(targetStatus)) {
                throw ControlPlaneException.conflict("REPLAY_PLAN_INVALID_TRANSITION",
                        "Cannot transition replay plan from " + current.status() + " to " + targetStatus,
                        Map.of("id", id, "currentStatus", current.status().name(), "targetStatus", targetStatus.name()));
            }
            return current.transitionTo(targetStatus, clock.instant());
        });

        audit(actor, "replay_plan.transition", "replay_plan", id, updated.version(),
                optionalString(request, "correlationId", ""),
                "SUCCESS", reason,
                Map.of("from", expectedStatus.name(), "to", targetStatus.name(), "fencingToken", fencingToken));
        return updated;
    }

    private long nextDatasetVersion(String datasetId) {
        return datasetVersions.values().stream()
                .filter(version -> version.datasetId().equals(datasetId))
                .mapToLong(DatasetVersion::version)
                .max()
                .orElse(0) + 1;
    }

    private void assertExpectedState(Enum<?> currentStatus, Enum<?> expectedStatus,
                                     long currentVersion, long expectedVersion) {
        if (currentStatus != expectedStatus || currentVersion != expectedVersion) {
            throw ControlPlaneException.conflict("RESOURCE_VERSION_CONFLICT",
                    "Resource status or version has changed",
                    Map.of(
                            "currentStatus", currentStatus.name(),
                            "expectedStatus", expectedStatus.name(),
                            "currentVersion", currentVersion,
                            "expectedVersion", expectedVersion
                    ));
        }
    }

    private synchronized AuditRecord audit(String actor, String action, String resourceType,
                                           String resourceId, long resourceVersion,
                                           String correlationId, String result, String reason,
                                           Map<String, Object> details) {
        Instant now = clock.instant();
        String previousHash = audits.isEmpty() ? GENESIS_HASH : audits.get(audits.size() - 1).recordHash();
        String id = "audit-" + UUID.randomUUID();
        String recordHash = sha256(canonical(Map.ofEntries(
                Map.entry("id", id),
                Map.entry("occurredAt", now.toString()),
                Map.entry("actor", actor),
                Map.entry("action", action),
                Map.entry("resourceType", resourceType),
                Map.entry("resourceId", resourceId),
                Map.entry("resourceVersion", resourceVersion),
                Map.entry("previousRecordHash", previousHash),
                Map.entry("correlationId", correlationId),
                Map.entry("result", result),
                Map.entry("reason", reason),
                Map.entry("details", details)
        )));
        AuditRecord record = new AuditRecord(id, now, actor, action, resourceType, resourceId,
                resourceVersion, previousHash, recordHash, correlationId, result, reason, details);
        audits.add(record);
        return record;
    }

    private String canonical(Object value) {
        try {
            return canonicalMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot create canonical audit payload", e);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String requiredString(Map<String, Object> request, String key) {
        String value = optionalString(request, key, "");
        if (value.isBlank()) {
            throw ControlPlaneException.badRequest("FIELD_REQUIRED", "Missing required field: " + key);
        }
        return value;
    }

    private static String optionalString(Map<String, Object> request, String key, String defaultValue) {
        Object value = request.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static long requiredLong(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) {
            throw ControlPlaneException.badRequest("FIELD_REQUIRED", "Missing required field: " + key);
        }
        return toLong(value, key);
    }

    private static long optionalLong(Map<String, Object> request, String key, long defaultValue) {
        Object value = request.get(key);
        return value == null ? defaultValue : toLong(value, key);
    }

    private static long toLong(Object value, String key) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw ControlPlaneException.badRequest("INVALID_FIELD", "Field must be a number: " + key);
        }
    }

    private static <E extends Enum<E>> E requiredEnum(Map<String, Object> request, String key, Class<E> type) {
        String value = requiredString(request, key);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw ControlPlaneException.badRequest("INVALID_FIELD", "Invalid " + key + ": " + value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionalMap(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((mapKey, mapValue) -> copy.put(String.valueOf(mapKey), mapValue));
            return Map.copyOf(copy);
        }
        throw ControlPlaneException.badRequest("INVALID_FIELD", "Field must be an object: " + key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> optionalListOfMaps(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw ControlPlaneException.badRequest("INVALID_FIELD", "Field must be an array: " + key);
        }
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw ControlPlaneException.badRequest("INVALID_FIELD", "Array must contain objects: " + key);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            map.forEach((mapKey, mapValue) -> row.put(String.valueOf(mapKey), mapValue));
            copy.add(Map.copyOf(row));
        }
        return List.copyOf(copy);
    }
}
