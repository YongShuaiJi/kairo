package com.example.runtimemock.platform.recording;

import com.example.runtimemock.platform.crypto.EnvelopeEncryptionService;
import com.example.runtimemock.platform.service.PlatformException;
import com.example.runtimemock.platform.service.PlatformJdbcService;
import com.example.runtimemock.platform.service.PlatformJson;
import com.example.runtimemock.platform.service.RbacService;
import com.example.runtimemock.platform.service.RequestContext;
import com.example.runtimemock.storage.ObjectStorage;
import com.example.runtimemock.storage.PutObjectRequest;
import com.example.runtimemock.storage.StoredObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "runtime-mock.platform.recording.ingestion",
        name = "enabled", havingValue = "true")
public class RecordingIngestionService {

    private static final List<String> SENSITIVE_KEY_PARTS = List.of(
            "password", "passwd", "secret", "token", "authorization", "cookie", "credential", "api_key", "apikey");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectStorage objectStorage;
    private final EnvelopeEncryptionService encryptionService;
    private final PlatformJdbcService eventWriter;
    private final RbacService rbacService;
    private final int maxBatchEvents;

    public RecordingIngestionService(
            JdbcTemplate jdbcTemplate,
            ObjectStorage objectStorage,
            EnvelopeEncryptionService encryptionService,
            PlatformJdbcService eventWriter,
            RbacService rbacService,
            @Value("${runtime-mock.platform.recording.ingestion.max-batch-events:500}") int maxBatchEvents
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectStorage = objectStorage;
        this.encryptionService = encryptionService;
        this.eventWriter = eventWriter;
        this.rbacService = rbacService;
        this.maxBatchEvents = Math.max(1, maxBatchEvents);
    }

    @Transactional
    public Map<String, Object> ingest(String sessionId, RequestContext context, Map<String, Object> request) {
        String batchId = string(request.get("batchId"), "recording-batch-" + UUID.randomUUID());
        List<Map<String, Object>> existing = rows(jdbcTemplate.queryForList(
                "select * from recording_batch where id = ?", batchId));
        if (!existing.isEmpty()) {
            Map<String, Object> response = new LinkedHashMap<>(existing.get(0));
            response.put("idempotent", true);
            return response;
        }

        Map<String, Object> session = row(jdbcTemplate.queryForMap(
                "select * from recording_session where id = ? for update", sessionId));
        if (!"RECORDING".equals(String.valueOf(session.get("status")))) {
            throw PlatformException.conflict("RECORDING_SESSION_NOT_ACTIVE",
                    "Recording events are accepted only while the session is RECORDING",
                    Map.of("sessionId", sessionId, "status", session.get("status")));
        }
        authorize(context, session);

        List<Map<String, Object>> events = eventList(request.get("events"));
        if (events.isEmpty() || events.size() > maxBatchEvents) {
            throw PlatformException.badRequest("INVALID_RECORDING_BATCH",
                    "events must contain between 1 and " + maxBatchEvents + " items");
        }

        List<Map<String, Object>> sanitizedEvents = events.stream()
                .map(this::sanitizeEvent)
                .toList();
        String jsonLines = sanitizedEvents.stream()
                .map(PlatformJson::write)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("") + "\n";
        byte[] plaintext = jsonLines.getBytes(StandardCharsets.UTF_8);
        enforceQuota(sessionId, session, events.size(), plaintext.length);

        String contentHash = PlatformJson.sha256(jsonLines);
        EnvelopeEncryptionService.EncryptedPayload encrypted =
                encryptionService.encrypt(plaintext, "recording_session:" + sessionId);
        String objectKey = "recording/" + sessionId + "/" + batchId + ".jsonl.enc";
        Map<String, String> metadata = new LinkedHashMap<>(encrypted.metadata());
        metadata.put("plaintext-sha256", contentHash);
        metadata.put("format", "application/x-ndjson");
        metadata.put("recording-session-id", sessionId);
        StoredObject stored = objectStorage.put(new PutObjectRequest(
                objectKey, encrypted.content(), "application/octet-stream", contentHash, metadata));

        Instant now = Instant.now();
        jdbcTemplate.update("""
                insert into recording_batch(
                    id, recording_session_id, status, object_uri, event_count, bytes_count, created_at, sealed_at
                ) values (?, ?, 'SEALED', ?, ?, ?, ?, ?)
                """, batchId, sessionId, stored.objectUri(), events.size(), plaintext.length,
                Timestamp.from(now), Timestamp.from(now));

        String payloadObjectId = "payload-" + UUID.randomUUID();
        jdbcTemplate.update("""
                insert into payload_object(
                    id, content_hash, encryption_domain, object_uri, bytes_count, created_at, metadata_json
                ) values (?, ?, ?, ?, ?, ?, ?)
                """, payloadObjectId, contentHash, "recording_session:" + sessionId,
                stored.objectUri(), plaintext.length, Timestamp.from(now),
                PlatformJson.write(Map.of(
                        "encryptionScope", "recording_session:" + sessionId,
                        "encryption", encrypted.metadata()
                )));

        for (int index = 0; index < sanitizedEvents.size(); index++) {
            Map<String, Object> event = sanitizedEvents.get(index);
            String referenceId = "payload-ref-" + UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into payload_reference(
                        id, payload_object_id, logical_path, reference_json, created_at
                    ) values (?, ?, ?, ?, ?)
                    """, referenceId, payloadObjectId, "$[" + index + "]",
                    PlatformJson.write(Map.of(
                            "batchId", batchId,
                            "line", index + 1,
                            "contentHash", contentHash
                    )), Timestamp.from(now));
            jdbcTemplate.update("""
                    insert into recording_event_index(
                        id, recording_session_id, recording_batch_id, trace_id, span_id,
                        protocol, event_time, payload_reference_id, metadata_json
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, string(event.get("id"), "recording-event-" + UUID.randomUUID()),
                    sessionId, batchId,
                    string(event.get("traceId"), ""),
                    string(event.get("spanId"), ""),
                    string(event.get("protocol"), "JAVA_METHOD"),
                    Timestamp.from(eventTime(event.get("eventTime"), now)),
                    referenceId,
                    PlatformJson.write(event.getOrDefault("metadata", Map.of())));
        }

        long version = ((Number) session.get("version")).longValue();
        eventWriter.recordEvent(context, "recording_batch.ingested", "recording_session", sessionId,
                version, Map.of(), Map.of("batchId", batchId, "eventCount", events.size()),
                "SUCCESS", "recording batch ingested",
                Map.of("batchId", batchId, "objectUri", stored.objectUri(),
                        "eventCount", events.size(), "bytesCount", plaintext.length));
        return Map.of(
                "id", batchId,
                "recording_session_id", sessionId,
                "status", "SEALED",
                "object_uri", stored.objectUri(),
                "event_count", events.size(),
                "bytes_count", plaintext.length,
                "content_hash", contentHash,
                "idempotent", false
        );
    }

    private void authorize(RequestContext context, Map<String, Object> session) {
        if (!"agent".equals(context.identitySource())) {
            rbacService.require(context, "RECORD_ARGUMENTS");
            return;
        }
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from agent_instance a
                  join instance i on i.id = a.instance_id
                 where a.id = ?
                   and a.status <> 'REMOVED'
                   and i.application_id = ?
                   and i.environment_id = ?
                """, Integer.class, context.actor(), session.get("application_id"), session.get("environment_id"));
        if (count == null || count == 0) {
            throw PlatformException.forbidden("RECORDING_SESSION_AGENT_SCOPE");
        }
    }

    private void enforceQuota(String sessionId, Map<String, Object> session, long newEvents, long newBytes) {
        Map<String, Object> quota = row(jdbcTemplate.queryForMap("""
                select * from recording_session_quota where recording_session_id = ?
                """, sessionId));
        Instant expiresAt = instant(quota.get("expires_at"));
        if (!expiresAt.isAfter(Instant.now())) {
            throw PlatformException.conflict("RECORDING_SESSION_EXPIRED",
                    "Recording session quota has expired", Map.of("sessionId", sessionId));
        }
        Map<String, Object> totals = row(jdbcTemplate.queryForMap("""
                select coalesce(sum(event_count), 0) as event_count,
                       coalesce(sum(bytes_count), 0) as bytes_count
                  from recording_batch
                 where recording_session_id = ? and status = 'SEALED'
                """, sessionId));
        long eventCount = ((Number) totals.get("event_count")).longValue() + newEvents;
        long bytesCount = ((Number) totals.get("bytes_count")).longValue() + newBytes;
        long maxEvents = Math.min(
                ((Number) session.get("max_events")).longValue(),
                ((Number) quota.get("max_events")).longValue());
        long maxBytes = ((Number) quota.get("max_bytes")).longValue();
        if (eventCount > maxEvents || bytesCount > maxBytes) {
            throw PlatformException.conflict("RECORDING_QUOTA_EXCEEDED",
                    "Recording batch would exceed the session quota",
                    Map.of("eventCount", eventCount, "maxEvents", maxEvents,
                            "bytesCount", bytesCount, "maxBytes", maxBytes));
        }
    }

    private Map<String, Object> sanitizeEvent(Map<String, Object> event) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        event.forEach((key, value) -> sanitized.put(key, sanitizeValue(key, value)));
        return sanitized;
    }

    private Object sanitizeValue(String key, Object value) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains)) {
            return "[REDACTED]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) -> {
                String stringKey = String.valueOf(nestedKey);
                sanitized.put(stringKey, sanitizeValue(stringKey, nestedValue));
            });
            return sanitized;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> sanitized = new ArrayList<>(collection.size());
            for (Object item : collection) {
                sanitized.add(item instanceof Map<?, ?> map
                        ? sanitizeValue("", PlatformJson.stringKeyMap(map))
                        : sanitizeValue("", item));
            }
            return sanitized;
        }
        return value;
    }

    private List<Map<String, Object>> eventList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw PlatformException.badRequest("INVALID_RECORDING_EVENT", "Each event must be a JSON object");
            }
            events.add(PlatformJson.stringKeyMap(map));
        }
        return events;
    }

    private Instant eventTime(Object value, Instant fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception e) {
            throw PlatformException.badRequest("INVALID_EVENT_TIME", "eventTime must use ISO-8601 format");
        }
    }

    private String string(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC);
        }
        return Instant.parse(String.valueOf(value));
    }

    private List<Map<String, Object>> rows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::row).toList();
    }

    private Map<String, Object> row(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        return normalized;
    }
}
