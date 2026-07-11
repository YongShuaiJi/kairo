package com.example.kairo.platform.service;

import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.command.BytecodeDiagnosticExchange;
import com.example.kairo.platform.persistence.mapper.BytecodeMetadataMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Proxies diagnostics over the existing Agent-initiated command channel; never connects to Agent URLs. */
@Service
public class BytecodeDiagnosticProxyService {
    private final AgentCommandService commands;
    private final BytecodeDiagnosticExchange exchange;
    private final BytecodeMetadataMapper metadataMapper;
    private final RbacService rbac;
    private final PlatformCoreService events;
    private final BytecodeMetadataService metadata;

    @Value("${kairo.platform.bytecode.timeout-ms:15000}")
    private long timeoutMillis;

    public BytecodeDiagnosticProxyService(AgentCommandService commands, BytecodeDiagnosticExchange exchange,
                                          BytecodeMetadataMapper metadataMapper, RbacService rbac,
                                          PlatformCoreService events, BytecodeMetadataService metadata) {
        this.commands = commands; this.exchange = exchange; this.metadataMapper = metadataMapper;
        this.rbac = rbac; this.events = events;
        this.metadata = metadata;
    }

    public Map<String, Object> transformations(RequestContext context, String agentId, String classId) {
        return invoke(context, agentId, "BYTECODE_TRANSFORMATIONS", classId, Map.of(), null, false);
    }

    public byte[] bytecode(RequestContext context, String agentId, String classId, String kind, long revision) {
        Map<String, Object> result = invoke(context, agentId, "BYTECODE_GET", classId,
                Map.of("kind", required(kind, "kind"), "revision", nonNegative(revision)), null, true);
        Object encoded = result.get("bytecodeBase64Url");
        if (!(encoded instanceof String text)) throw PlatformException.conflict("BYTECODE_RESULT_MISSING",
                "Agent returned no bytecode", Map.of("agentId", agentId));
        byte[] bytes = Base64.getUrlDecoder().decode(text);
        if (bytes.length > 8 * 1024 * 1024) throw PlatformException.badRequest("BYTECODE_TOO_LARGE", "Bytecode exceeds 8 MiB");
        return bytes;
    }

    public Map<String, Object> preview(RequestContext context, String agentId, String classId, byte[] input) {
        return invoke(context, agentId, "BYTECODE_PREVIEW", classId, Map.of(), input, true);
    }

    public Map<String, Object> capture(RequestContext context, String agentId, String classId) {
        return invoke(context, agentId, "BYTECODE_CAPTURE", classId, Map.of(), null, false);
    }

    public Map<String, Object> diff(RequestContext context, String agentId, String classId,
                                    String fromKind, long fromRevision, String toKind, long toRevision) {
        return invoke(context, agentId, "BYTECODE_DIFF", classId,
                Map.of("fromKind", required(fromKind, "fromKind"), "fromRevision", nonNegative(fromRevision),
                        "toKind", required(toKind, "toKind"), "toRevision", nonNegative(toRevision)), null, false);
    }

    private Map<String, Object> invoke(RequestContext context, String agentId, String commandType,
                                       String classId, Map<String, Object> arguments,
                                       byte[] transientInput, boolean auditSensitiveAction) {
        rbac.require(context, "AGENT_MANAGE", "agent_instance", agentId);
        required(classId, "classId");
        String runtimeId = metadataMapper.runtimeInstanceIdForAgent(agentId);
        if (runtimeId == null) throw PlatformException.notFound("agent_instance", agentId);
        Map<String, Object> payload = new LinkedHashMap<>(arguments);
        payload.put("commandType", commandType); payload.put("classId", classId);
        Map<String, Object> command = commands.createBytecodeDiagnosticCommand(
                context, agentId, commandType, payload, transientInput);
        String commandId = String.valueOf(command.get("id"));
        try {
            Map<String, Object> result = exchange.await(commandId,
                    Duration.ofMillis(Math.max(1000L, Math.min(timeoutMillis, 60_000L))));
            persistMetadata(runtimeId, agentId, commandType, result);
            if (auditSensitiveAction) {
                events.recordEvent(context, commandType.equals("BYTECODE_GET") ? "bytecode.export" : "bytecode.preview",
                        "agent_instance", agentId, 1, null, Map.of("completed", true), "SUCCESS",
                        "bytecode diagnostic", Map.of("agentId", agentId, "runtimeInstanceId", runtimeId,
                                "classId", classId, "commandId", commandId));
            }
            return result;
        } catch (RuntimeException failure) {
            throw PlatformException.conflict("BYTECODE_DIAGNOSTIC_TIMEOUT",
                    "Agent diagnostic did not complete before timeout", Map.of("agentId", agentId, "commandId", commandId));
        } finally {
            exchange.remove(commandId);
        }
    }

    @SuppressWarnings("unchecked")
    private void persistMetadata(String runtimeId, String agentId, String commandType,
                                 Map<String, Object> result) {
        if ("BYTECODE_TRANSFORMATIONS".equals(commandType)) {
            Object history = result.get("history");
            if (history instanceof List<?> rows) {
                for (Object row : rows) if (row instanceof Map<?, ?> map) {
                    persistOne(runtimeId, agentId, "INPUT", stringMap(map));
                }
            }
        } else if ("BYTECODE_PREVIEW".equals(commandType)) {
            persistOne(runtimeId, agentId, "PLANNED", result);
        } else if ("BYTECODE_CAPTURE".equals(commandType)) {
            persistOne(runtimeId, agentId, "APPLIED", result);
        } else if ("BYTECODE_GET".equals(commandType)) {
            persistOne(runtimeId, agentId, String.valueOf(result.getOrDefault("kind", "INPUT")), result);
        }
    }

    private void persistOne(String runtimeId, String agentId, String kind, Map<String, Object> result) {
        Object rawIdentity = result.get("classIdentity");
        if (!(rawIdentity instanceof Map<?, ?> identity)) return;
        String className = String.valueOf(identity.get("binaryClassName"));
        String loaderId = String.valueOf(identity.get("classLoaderId"));
        long revision = number(result.get("revision"), 0L);
        String status = String.valueOf(result.getOrDefault("status",
                Boolean.TRUE.equals(result.get("captured")) ? "SUCCEEDED" : "OBSERVED"));
        String hash = switch (kind) {
            case "PLANNED" -> nullableString(result.get("plannedHash"));
            case "APPLIED" -> nullableString(result.getOrDefault("appliedHash", result.get("outputHash")));
            default -> nullableString(result.getOrDefault("inputHash", result.get("hash")));
        };
        Long size = result.get("sizeBytes") instanceof Number n ? n.longValue()
                : result.get("plannedSizeBytes") instanceof Number n ? n.longValue() : null;
        String diagnostics = PlatformJson.write(result.getOrDefault("diagnostics", List.of()));
        if (diagnostics.length() > BytecodeMetadataService.MAX_DIAGNOSTICS_CHARS) {
            diagnostics = "[{\"code\":\"DIAGNOSTICS_TRUNCATED\"}]";
        }
        Timestamp now = Timestamp.from(Instant.now());
        metadata.upsert(new BytecodeMetadataService.BytecodeMetadata(runtimeId, agentId, className, loaderId,
                revision, kind, hash, size, status, diagnostics, now, now, now));
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw PlatformException.badRequest("FIELD_REQUIRED", field + " is required");
        return value;
    }

    private static long nonNegative(long value) {
        if (value < 0) throw PlatformException.badRequest("INVALID_REVISION", "revision must be non-negative");
        return value;
    }
}
