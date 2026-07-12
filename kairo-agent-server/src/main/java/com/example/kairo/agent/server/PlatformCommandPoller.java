package com.example.kairo.agent.server;

import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.agent.core.script.ScriptSessionManager;
import com.example.kairo.api.bytecode.DecompilationResult;
import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ScriptDiagnostic;
import com.example.kairo.api.ScriptPolicyRevision;
import com.example.kairo.api.ScriptSessionResult;
import com.example.kairo.api.ScriptSessionSpec;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.groovy.CompiledMockScript;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class PlatformCommandPoller implements AutoCloseable {

    private final AgentRuntime runtime;
    private final AgentLaunchConfig config;
    private final Runnable shutdownCallback;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ScheduledExecutorService executor;

    PlatformCommandPoller(AgentRuntime runtime, AgentLaunchConfig config, Runnable shutdownCallback) {
        this.runtime = runtime;
        this.config = config;
        this.shutdownCallback = shutdownCallback;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kairo-platform-command-poller");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        executor.scheduleWithFixedDelay(this::pollSafely, 0L,
                config.platformPollIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (Exception e) {
            runtime.recordEvent("platform.command.poll_failed", "platform", null, null,
                    e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private void pollOnce() throws Exception {
        post("/api/v1/agents/" + config.platformAgentId() + "/heartbeat",
                Map.of("status", runtime.jvmInfo().status(),
                        "metrics", runtime.metrics()));
        JsonNode command = post("/api/v1/agents/" + config.platformAgentId() + "/commands/next",
                Map.of("leaseSeconds", config.platformCommandLeaseSeconds()));
        if ("NO_COMMAND".equals(command.path("status").asText())) {
            return;
        }
        String commandId = command.path("id").asText();
        try {
            Map<String, Object> result = execute(command);
            post("/api/v1/agent-commands/" + commandId + "/ack",
                    Map.of("status", "ACKED", "result", result, "reason", "agent command applied"));
        } catch (Exception e) {
            post("/api/v1/agent-commands/" + commandId + "/ack",
                    Map.of("status", "FAILED",
                            "errorMessage", e.getClass().getName() + ": " + e.getMessage(),
                            "reason", "agent command failed"));
        }
    }

    Map<String, Object> execute(JsonNode command) {
        JsonNode payload = command.path("payload");
        String commandType = payload.path("commandType").asText(command.path("command_type").asText());
        return switch (commandType) {
            case "APPLY_RULE" -> applyRule(payload.path("rule"));
            case "DISABLE_ALL" -> {
                runtime.disableAll(true);
                yield Map.of("disabled", true);
            }
            case "ENABLE_ALL" -> {
                runtime.disableAll(false);
                yield Map.of("disabled", false);
            }
            case "RESET_CLASS" -> {
                String classId = payload.path("classId").asText(payload.path("className").asText());
                var result = runtime.resetClass(classId, "platform");
                yield Map.of("classId", classId, "remainingRules", result.remainingRules().size(),
                        "removedRuleIds", result.removedRuleIds(), "failedRules", result.failedRules(),
                        "degraded", result.degraded());
            }
            case "RESET_ALL" -> {
                runtime.resetAll("platform");
                yield Map.of("resetAll", true);
            }
            case "STOP_AGENT" -> {
                runtime.resetAll("platform");
                runtime.disableAll(true);
                scheduleShutdown();
                yield Map.of("stopping", true, "restoredBytecode", true);
            }
            case "START_RECORDING" -> startRecording(payload);
            case "STOP_RECORDING" -> stopRecording(payload);
            case "DISCOVER_TARGETS" -> discoverTargets(payload);
            case "REFRESH_RUNTIME_STATE" -> Map.of("refreshed", true);
            case "BYTECODE_TRANSFORMATIONS" -> bytecodeTransformations(payload);
            case "BYTECODE_GET" -> bytecodeGet(payload);
            case "BYTECODE_PREVIEW" -> bytecodePreview(payload);
            case "BYTECODE_CAPTURE" -> bytecodeCapture(payload);
            case "BYTECODE_DIFF" -> bytecodeDiff(payload);
            case "SCRIPT_SESSION_CREATE" -> scriptSessionCreate(payload);
            case "SCRIPT_SESSION_VALIDATE" -> scriptSessionTransition(payload, "validate");
            case "SCRIPT_SESSION_APPLY" -> scriptSessionTransition(payload, "apply");
            case "SCRIPT_SESSION_PROMOTE" -> scriptSessionTransition(payload, "promote");
            case "SCRIPT_SESSION_REVERT" -> scriptSessionTransition(payload, "revert");
            case "SCRIPT_COMPILE" -> scriptCompile(payload);
            default -> throw new IllegalArgumentException("Unsupported platform command: " + commandType);
        };
    }

    // -------------------------------------------------------- ScriptSession (V1.2 phase 5)

    /**
     * Real-JVM trial creation: build a {@link ScriptSessionSpec} from the platform payload (the
     * exchange splices the transient script source in at poll time) and hand it to the agent's
     * {@link ScriptSessionManager}. The manager resolves and validates the target method, enforces
     * the local safety limits and records the deadline; the script is not compiled or applied yet.
     */
    private Map<String, Object> scriptSessionCreate(JsonNode payload) {
        ScriptSessionSpec spec = scriptSessionSpec(payload);
        ScriptSessionResult result = runtime.scriptSessionManager().create(spec);
        return scriptSessionResultMap(result);
    }

    /** Advance one session through validate/apply/promote/revert on the real JVM. */
    private Map<String, Object> scriptSessionTransition(JsonNode payload, String action) {
        String sessionId = requiredText(payload, "sessionId");
        ScriptSessionManager manager = runtime.scriptSessionManager();
        ScriptSessionResult result = switch (action) {
            case "validate" -> manager.validate(sessionId);
            case "apply" -> manager.apply(sessionId);
            case "promote" -> manager.promote(sessionId, "platform");
            case "revert" -> manager.revert(sessionId, "platform");
            default -> throw new IllegalArgumentException("Unsupported script session action: " + action);
        };
        return scriptSessionResultMap(result);
    }

    /**
     * Real compile against an agent ClassLoader, returning the structured result the platform
     * reconciles into a {@code ScriptCompilationResult}. The target loader is resolved by id from
     * the loaded classes; the bootstrap id compiles against the agent loader as parent (Kairo script
     * types still resolve) while recording {@code "bootstrap"}. Diagnostics are structured so the
     * platform can surface them without re-parsing Groovy messages.
     */
    private Map<String, Object> scriptCompile(JsonNode payload) {
        String script = requiredText(payload, "script");
        String targetClassLoaderId = requiredText(payload, "targetClassLoaderId");
        CapabilityProfile profile = CapabilityProfile.valueOf(
                text(payload, "capabilityProfile", CapabilityProfile.SAFE.name()));
        ScriptPolicyRevision revision = scriptPolicyRevision(payload);
        ClassLoader targetLoader = resolveTargetLoader(targetClassLoaderId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetClassLoaderId", targetClassLoaderId);
        try {
            CompiledMockScript compiled = runtime.scriptCompilerFactory().compile(
                    script, profile, revision, targetLoader, targetClassLoaderId);
            result.put("successful", true);
            result.put("compilerVersion", "groovy-" + groovy.lang.GroovySystem.getVersion());
            result.put("diagnostics", List.of());
        } catch (RuntimeException e) {
            result.put("successful", false);
            result.put("compilerVersion", "groovy-unknown");
            result.put("diagnostics", List.of(diagnosticMap(compileDiagnostic(e, targetClassLoaderId))));
        }
        return result;
    }

    private ScriptSessionSpec scriptSessionSpec(JsonNode payload) {
        String sessionId = requiredText(payload, "sessionId");
        String agentId = requiredText(payload, "agentId");
        JsonNode targetNode = payload.path("target");
        String className = requiredText(targetNode, "className");
        String classLoaderId = text(targetNode, "classLoaderId", null);
        String methodName = requiredText(targetNode, "methodName");
        String methodDescriptor = requiredText(targetNode, "methodDescriptor");
        String script = requiredText(payload, "script");
        CapabilityProfile profile = CapabilityProfile.valueOf(
                text(payload, "capabilityProfile", CapabilityProfile.SAFE.name()));
        ScriptPolicyRevision revision = scriptPolicyRevision(payload);
        long ttlMillis = requiredPositiveLong(payload, "ttlMillis");
        long maxHits = requiredPositiveLong(payload, "maxHits");
        String requestedBy = text(payload, "requestedBy", "platform");
        return new ScriptSessionSpec(sessionId, agentId,
                new com.example.kairo.api.MethodSelector(className,
                        classLoaderId == null || classLoaderId.isBlank() ? null : classLoaderId,
                        methodName, methodDescriptor),
                script, profile, revision, ttlMillis, maxHits, requestedBy);
    }

    private ScriptPolicyRevision scriptPolicyRevision(JsonNode payload) {
        JsonNode node = payload.path("policyRevision");
        long revision = node.path("revision").asLong(0L);
        if (revision < 0) {
            revision = 0L;
        }
        String hash = text(node, "hash", "safe-default");
        return new ScriptPolicyRevision(revision, hash);
    }

    private ClassLoader resolveTargetLoader(String targetClassLoaderId) {
        if (ClassLoaderIdentity.BOOTSTRAP.equals(targetClassLoaderId)) {
            return null;
        }
        return runtime.loadedClassRepository().findClassLoader(targetClassLoaderId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Target ClassLoader not found on agent: " + targetClassLoaderId));
    }

    private Map<String, Object> scriptSessionResultMap(ScriptSessionResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionId", result.sessionId());
        map.put("status", result.status().name());
        map.put("createdAt", result.createdAt());
        map.put("expiresAt", result.expiresAt());
        map.put("hitCount", result.hitCount());
        map.put("diagnostics", result.diagnostics().stream().map(this::diagnosticMap).toList());
        return map;
    }

    private Map<String, Object> diagnosticMap(ScriptDiagnostic diagnostic) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("phase", diagnostic.phase().name());
        map.put("severity", diagnostic.severity().name());
        map.put("line", diagnostic.line());
        map.put("column", diagnostic.column());
        map.put("code", diagnostic.code());
        map.put("message", diagnostic.message());
        map.put("targetClassLoaderId", diagnostic.targetClassLoaderId());
        map.put("suggestion", diagnostic.suggestion());
        return map;
    }

    private ScriptDiagnostic compileDiagnostic(Throwable throwable, String targetClassLoaderId) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return new ScriptDiagnostic(ScriptDiagnostic.Phase.COMPILATION,
                ScriptDiagnostic.Severity.ERROR, 0, 0,
                message.toLowerCase(Locale.ROOT).contains("forbidden") ? "FORBIDDEN_SCRIPT" : "SCRIPT_COMPILE_ERROR",
                message, targetClassLoaderId,
                "Fix the script and retry; the script was not applied to any target.");
    }

    private long requiredPositiveLong(JsonNode payload, String field) {
        if (!payload.has(field) || !payload.path(field).canConvertToLong() || payload.path(field).asLong() <= 0) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        return payload.path(field).asLong();
    }

    private Map<String, Object> bytecodeTransformations(JsonNode payload) {
        var identity = runtime.loadedClassRepository().toClassIdentity(requiredText(payload, "classId"));
        return Map.of("classIdentity", identityMap(identity),
                "currentRevision", revisionMap(runtime.transformationJournal().currentRevision(identity).value()),
                "history", runtime.transformationJournal().history(identity).stream()
                        .map(this::transformationMap).toList());
    }

    private Map<String, Object> bytecodeGet(JsonNode payload) {
        var identity = runtime.loadedClassRepository().toClassIdentity(requiredText(payload, "classId"));
        var kind = com.example.kairo.api.bytecode.BytecodeSnapshotKind.valueOf(
                requiredText(payload, "kind").toUpperCase(Locale.ROOT));
        long revision = requiredNonNegativeLong(payload, "revision");
        var key = new com.example.kairo.agent.core.bytecode.BytecodeSnapshotKey(identity,
                com.example.kairo.api.bytecode.TransformationRevision.of(revision), kind);
        byte[] bytes = runtime.snapshotRepository().bytes(key)
                .orElseThrow(() -> new IllegalArgumentException("bytecode snapshot not found"));
        ensureOutputSize(bytes);
        return Map.of("classIdentity", identityMap(identity), "kind", kind.name(),
                "revision", revision, "sizeBytes", bytes.length,
                "hash", com.example.kairo.agent.core.bytecode.BytecodeHash.sha256Hex(bytes),
                "bytecodeBase64Url", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    private Map<String, Object> bytecodePreview(JsonNode payload) {
        var identity = runtime.loadedClassRepository().toClassIdentity(requiredText(payload, "classId"));
        byte[] input = decodeInput(payload, "bytecodeBase64Url");
        var result = runtime.previewService().preview(identity, input);
        byte[] toDecompile = result.plannedBytes() != null ? result.plannedBytes() : input;
        DecompilationResult decompilation = runtime.decompilerService().decompile(identity, toDecompile);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("classIdentity", identityMap(identity));
        response.put("revision", revisionMap(result.revision().value()));
        response.put("inputHash", result.inputHash());
        response.put("plannedHash", result.plannedHash());
        response.put("plannedSizeBytes", result.plannedBytes() == null ? null : result.plannedBytes().length);
        response.put("targetMethodCount", result.targetMethodCount());
        response.put("adviceTypes", result.adviceTypes());
        response.put("diagnostics", result.diagnostics().stream().map(this::diagnosticMap).toList());
        response.put("changed", result.changed());
        response.put("decompilation", decompilationMap(decompilation));
        return response;
    }

    private Map<String, Object> bytecodeCapture(JsonNode payload) {
        Class<?> type = runtime.loadedClassRepository().findClass(requiredText(payload, "classId"))
                .orElseThrow(() -> new IllegalArgumentException("class is not loaded"));
        var result = runtime.captureService().capture(type);
        if (result.appliedBytes() != null) ensureOutputSize(result.appliedBytes());
        DecompilationResult decompilation = result.appliedBytes() != null
                ? runtime.decompilerService().decompile(result.classIdentity(), result.appliedBytes())
                : null;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("classIdentity", identityMap(result.classIdentity()));
        response.put("revision", revisionMap(result.revision().value()));
        response.put("appliedHash", result.appliedHash());
        response.put("sizeBytes", result.appliedBytes() == null ? null : result.appliedBytes().length);
        response.put("diagnostics", result.diagnostics().stream().map(this::diagnosticMap).toList());
        response.put("capturedAtMillis", result.capturedAtMillis());
        response.put("captured", result.captured());
        response.put("decompilation", decompilationMap(decompilation));
        return response;
    }

    private Map<String, Object> bytecodeDiff(JsonNode payload) {
        var identity = runtime.loadedClassRepository().toClassIdentity(requiredText(payload, "classId"));
        var fromKind = com.example.kairo.api.bytecode.BytecodeSnapshotKind.valueOf(
                requiredText(payload, "fromKind").toUpperCase(Locale.ROOT));
        var toKind = com.example.kairo.api.bytecode.BytecodeSnapshotKind.valueOf(
                requiredText(payload, "toKind").toUpperCase(Locale.ROOT));
        long fromRevision = requiredNonNegativeLong(payload, "fromRevision");
        long toRevision = requiredNonNegativeLong(payload, "toRevision");
        byte[] from = snapshot(identity, fromKind, fromRevision);
        byte[] to = snapshot(identity, toKind, toRevision);
        var result = runtime.diffService().diff(identity, from,
                com.example.kairo.api.bytecode.TransformationRevision.of(fromRevision), fromKind, to,
                com.example.kairo.api.bytecode.TransformationRevision.of(toRevision), toKind);
        DecompilationResult fromDecompilation = runtime.decompilerService().decompile(identity, from);
        DecompilationResult toDecompilation = runtime.decompilerService().decompile(identity, to);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("classIdentity", identityMap(identity)); response.put("fromRevision", revisionMap(fromRevision));
        response.put("toRevision", revisionMap(toRevision)); response.put("fromKind", fromKind.name());
        response.put("toKind", toKind.name()); response.put("fromHash", result.fromHash());
        response.put("toHash", result.toHash()); response.put("identical", result.identical());
        response.put("normalized", result.normalized()); response.put("methodDiffs", result.methodDiffs());
        response.put("structuralDiffs", result.structuralDiffs()); response.put("summary", result.summary());
        response.put("fromDecompilation", decompilationMap(fromDecompilation));
        response.put("toDecompilation", decompilationMap(toDecompilation));
        return response;
    }

    /** Serializes a {@link DecompilationResult} to the wire shape expected by the platform/web client. */
    private Map<String, Object> decompilationMap(DecompilationResult decompilation) {
        if (decompilation == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", decompilation.status().name());
        map.put("decompilerName", decompilation.decompilerName());
        map.put("sourceCode", decompilation.sourceCode());
        map.put("diagnostics", decompilation.diagnostics());
        map.put("durationMillis", decompilation.durationMillis());
        return map;
    }

    private byte[] snapshot(com.example.kairo.api.bytecode.ClassIdentity identity,
                            com.example.kairo.api.bytecode.BytecodeSnapshotKind kind, long revision) {
        var key = new com.example.kairo.agent.core.bytecode.BytecodeSnapshotKey(identity,
                com.example.kairo.api.bytecode.TransformationRevision.of(revision), kind);
        return runtime.snapshotRepository().bytes(key)
                .orElseThrow(() -> new IllegalArgumentException("bytecode snapshot not found"));
    }

    private byte[] decodeInput(JsonNode payload, String field) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(requiredText(payload, field));
            if (bytes.length == 0 || bytes.length > 1024 * 1024) {
                throw new IllegalArgumentException("preview input must be 1..1048576 bytes");
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid or oversized bytecodeBase64Url", e);
        }
    }

    private void ensureOutputSize(byte[] bytes) {
        if (bytes.length > 8 * 1024 * 1024) throw new IllegalArgumentException("bytecode output exceeds 8 MiB");
    }

    private long requiredNonNegativeLong(JsonNode payload, String field) {
        if (!payload.has(field) || !payload.path(field).canConvertToLong() || payload.path(field).asLong() < 0) {
            throw new IllegalArgumentException(field + " must be a non-negative integer");
        }
        return payload.path(field).asLong();
    }

    private Map<String, Object> identityMap(com.example.kairo.api.bytecode.ClassIdentity identity) {
        return Map.of("binaryClassName", identity.binaryClassName(), "classLoaderId", identity.classLoaderId());
    }

    private Map<String, Object> transformationMap(com.example.kairo.api.bytecode.TransformationResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("classIdentity", identityMap(result.classIdentity()));
        value.put("revision", revisionMap(result.revision().value()));
        value.put("status", result.status().name());
        value.put("inputHash", result.inputHash()); value.put("outputHash", result.outputHash());
        value.put("diagnostics", result.diagnostics().stream().map(this::diagnosticMap).toList());
        value.put("attemptedAtMillis", result.attemptedAtMillis()); value.put("durationMillis", result.durationMillis());
        return value;
    }

    private Map<String, Object> diagnosticMap(com.example.kairo.api.bytecode.TransformationDiagnostic diagnostic) {
        return mapper.convertValue(diagnostic, Map.class);
    }

    private Map<String, Object> revisionMap(long value) {
        return Map.of("value", value);
    }

    private Map<String, Object> applyRule(JsonNode ruleNode) {
        AgentHttpServer.RuleRequest request = AgentHttpServer.RuleRequest.from(ruleNode);
        runtime.publishTarget(request.classId(), request.toRule(List.of()), "platform");
        return Map.of("ruleId", request.id(), "version", request.version(), "classId", request.classId());
    }

    private Map<String, Object> discoverTargets(JsonNode payload) {
        String query = text(payload, "query", "");
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        int limit = Math.min(200, Math.max(1, payload.path("limit").asInt(100)));
        List<Map<String, Object>> targets = new ArrayList<>();
        for (var classInfo : runtime.searchClasses(query, limit)) {
            for (var method : runtime.methods(classInfo.classId())) {
                String targetSignature = (classInfo.className() + "#" + method.name() + method.descriptor())
                        .toLowerCase(Locale.ROOT);
                if (!normalizedQuery.isBlank()
                        && !classInfo.className().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                        && !method.name().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                        && !targetSignature.contains(normalizedQuery)) {
                    continue;
                }
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("classId", classInfo.classId());
                target.put("className", classInfo.className());
                target.put("classLoaderId", classInfo.classLoaderId());
                target.put("classLoaderClassName", classInfo.classLoaderClassName());
                target.put("modifiable", classInfo.modifiable());
                target.put("methodName", method.name());
                target.put("descriptor", method.descriptor());
                target.put("returnType", method.returnType());
                target.put("parameterTypes", method.parameterTypes());
                target.put("exceptionTypes", method.exceptionTypes());
                target.put("static", method.isStatic());
                target.put("private", method.isPrivate());
                targets.add(target);
                if (targets.size() >= limit) {
                    return Map.of("targets", targets, "truncated", true);
                }
            }
        }
        return Map.of("targets", targets, "truncated", false);
    }

    private Map<String, Object> startRecording(JsonNode payload) {
        JsonNode target = payload.path("target");
        String sessionId = requiredText(payload, "sessionId");
        String classTarget = text(target, "classId", text(target, "className", null));
        String methodName = text(target, "methodName", null);
        String methodDescriptor = text(target, "methodDescriptor",
                target.path("matcher").path("descriptor").asText(null));
        var registration = runtime.startRecording(
                sessionId, classTarget, methodName, methodDescriptor, "platform");
        return Map.of(
                "sessionId", registration.sessionId(),
                "classId", registration.classId(),
                "className", registration.className(),
                "methodName", registration.methodName(),
                "methodDescriptor", registration.methodDescriptor()
        );
    }

    private Map<String, Object> stopRecording(JsonNode payload) {
        String sessionId = requiredText(payload, "sessionId");
        var registration = runtime.stopRecording(sessionId, "platform");
        return Map.of("sessionId", sessionId, "stopped", registration != null);
    }

    private void scheduleShutdown() {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            shutdownCallback.run();
        }, "kairo-agent-stop");
        thread.setDaemon(true);
        thread.start();
    }

    private String requiredText(JsonNode node, String name) {
        String value = text(node, name, null);
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private String text(JsonNode node, String name, String fallback) {
        JsonNode value = node.path(name);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? fallback
                : value.asText();
    }

    private JsonNode post(String path, Object body) throws IOException, InterruptedException {
        String url = config.platformUrl() + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Actor", config.platformAgentId())
                .header("X-Identity-Source", "agent")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (!config.platformToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.platformToken());
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Platform API returned " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
