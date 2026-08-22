package com.example.kairo.agent.server;

import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.agent.core.MethodInfo;
import com.example.kairo.agent.core.ResetClassResult;
import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.InvokeOpcode;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationRevision;
import com.example.kairo.api.diagnostics.DiagnosticEvent;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentHttpServer implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "v1";
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private final AgentRuntime runtime;
    private final AgentTokenManager tokenManager;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    private final BytecodeRoutes bytecodeRoutes;

    public AgentHttpServer(AgentRuntime runtime, String host, int port, String token) {
        this(runtime, host, port, new AgentTokenManager(token, java.time.Duration.ofMinutes(15)),
                BytecodeApiLimits.STANDARD);
    }

    AgentHttpServer(AgentRuntime runtime, String host, int port, AgentTokenManager tokenManager) {
        this(runtime, host, port, tokenManager, BytecodeApiLimits.STANDARD);
    }

    /**
     * Full constructor, primarily for tests that need non-standard bytecode limits
     * (e.g. a tiny response cap to exercise the 413 path).
     */
    AgentHttpServer(AgentRuntime runtime, String host, int port, AgentTokenManager tokenManager,
                    BytecodeApiLimits bytecodeApiLimits) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.tokenManager = Objects.requireNonNull(tokenManager, "tokenManager");
        // The frozen bytecode DTOs ClassIdentity and TransformationRevision are plain
        // classes (not records) with xxxName()/value() accessors that Jackson does not
        // treat as getters. Serialize them by field so they appear on the wire with their
        // real property names without polluting the pure kairo-api module with Jackson
        // annotations.
        objectMapper.addMixIn(ClassIdentity.class, FieldVisibilityMixin.class);
        objectMapper.addMixIn(TransformationRevision.class, FieldVisibilityMixin.class);
        String bindHost = host == null || host.isBlank() ? LOOPBACK_HOST : host;
        if (!LOOPBACK_HOST.equals(bindHost) && !"localhost".equalsIgnoreCase(bindHost)) {
            throw new IllegalArgumentException("Agent HTTP server must bind to loopback");
        }
        try {
            this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 64);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start agent HTTP server", e);
        }
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "kairo-agent-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        this.bytecodeRoutes = new BytecodeRoutes(
                runtime.loadedClassRepository(),
                runtime.snapshotRepository(),
                runtime.transformationJournal(),
                runtime.previewService(),
                runtime.captureService(),
                runtime.diffService(),
                runtime.decompilerService(),
                objectMapper,
                Objects.requireNonNull(bytecodeApiLimits, "bytecodeApiLimits"));
        registerContexts();
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        bytecodeRoutes.close();
    }

    private void registerContexts() {
        server.createContext("/", authenticated(this::route));
    }

    private HttpHandler authenticated(ExchangeHandler handler) {
        return exchange -> {
            long started = System.nanoTime();
            String requestId = requestId(exchange);
            exchange.getResponseHeaders().set("X-Correlation-Id", requestId);
            Throwable failure = null;
            try {
                if (!isPublicPath(exchange.getRequestURI().getPath()) && !authorized(exchange)) {
                    write(exchange, 401, Map.of("error", "Unauthorized"));
                    return;
                }
                handler.handle(exchange);
            } catch (IllegalArgumentException e) {
                failure = e;
                write(exchange, 400, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                failure = e;
                write(exchange, 500, Map.of("error", "Internal server error", "correlationId", requestId));
            } finally {
                int status = exchange.getResponseCode();
                if (isMutation(exchange.getRequestMethod()) || status >= 400 || failure != null) {
                    runtime.recordEvent("agent.http.request.completed", actor(exchange), null,
                            normalizePath(exchange.getRequestURI().getPath()),
                            DiagnosticEvent.format("agent.http.request.completed",
                                    "correlationId", requestId,
                                    "method", exchange.getRequestMethod(),
                                    "path", normalizePath(exchange.getRequestURI().getPath()),
                                    "status", status,
                                    "durationMs", (System.nanoTime() - started) / 1_000_000L,
                                    "failureType", failure == null ? "" : failure.getClass().getName(),
                                    "failure", DiagnosticEvent.failureSummary(failure),
                                    "failureStack", DiagnosticEvent.stackSummary(failure)));
                }
                exchange.close();
            }
        };
    }

    private static boolean isMutation(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private static String requestId(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
        return supplied != null && supplied.length() <= 128 && supplied.matches("[A-Za-z0-9._:-]+")
                ? supplied : UUID.randomUUID().toString();
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getPath();
        if (("GET".equals(method) || "HEAD".equals(method)) && isConsolePath(rawPath)) {
            serveConsoleAsset(exchange, rawPath);
            return;
        }
        String path = normalizePath(rawPath);
        if ("GET".equals(method) && "/health".equals(path)) {
            write(exchange, 200, Map.of("status", "UP", "protocolVersion", PROTOCOL_VERSION));
            return;
        }
        if (bytecodeRoutes.handle(exchange, method, path)) {
            return;
        }
        if ("GET".equals(method) && "/status".equals(path)) {
            write(exchange, 200, Map.of(
                    "jvm", runtime.jvmInfo(),
                    "metrics", runtime.metrics(),
                    "protocolVersion", PROTOCOL_VERSION
            ));
            return;
        }
        if ("GET".equals(method) && "/jvm".equals(path)) {
            write(exchange, 200, runtime.jvmInfo());
            return;
        }
        if ("GET".equals(method) && "/classes".equals(path)) {
            Map<String, String> query = query(exchange.getRequestURI());
            write(exchange, 200, runtime.searchClasses(query.get("keyword"), parseInt(query.get("limit"), 100)));
            return;
        }
        if ("GET".equals(method) && path.startsWith("/classes/") && path.endsWith("/methods")) {
            String classId = segment(path, "/classes/", "/methods");
            write(exchange, 200, runtime.methods(classId));
            return;
        }
        if ("POST".equals(method) && "/scripts/compile".equals(path)) {
            JsonNode body = readJson(exchange);
            String ruleId = text(body, "ruleId", "compile-check");
            long version = body.path("version").asLong(1L);
            String script = requiredText(body, "script");
            write(exchange, 200, Map.of(
                    "ruleId", ruleId,
                    "version", version,
                    "scriptHash", runtime.compileScript(ruleId, version, script).scriptHash()
            ));
            return;
        }
        if ("GET".equals(method) && "/rules".equals(path)) {
            write(exchange, 200, runtime.rules());
            return;
        }
        if ("POST".equals(method) && "/rules".equals(path)) {
            RuleRequest request = readRuleRequest(exchange);
            MockRule rule = request.toRule(runtime.methods(request.classId()));
            runtime.publish(request.classId(), rule, actor(exchange));
            write(exchange, 201, runtime.rules());
            return;
        }
        if ("PUT".equals(method) && path.startsWith("/rules/")) {
            String ruleId = lastSegment(path);
            RuleRequest request = readRuleRequest(exchange);
            MockRule rule = request.toRule(runtime.methods(request.classId())).toBuilder().id(ruleId).build();
            runtime.publish(request.classId(), rule, actor(exchange));
            write(exchange, 200, runtime.rules());
            return;
        }
        if ("POST".equals(method) && path.startsWith("/rules/") && path.endsWith("/enable")) {
            runtime.setEnabled(segment(path, "/rules/", "/enable"), true, actor(exchange));
            write(exchange, 200, runtime.rules());
            return;
        }
        if ("POST".equals(method) && path.startsWith("/rules/") && path.endsWith("/disable")) {
            runtime.setEnabled(segment(path, "/rules/", "/disable"), false, actor(exchange));
            write(exchange, 200, runtime.rules());
            return;
        }
        if ("DELETE".equals(method) && path.startsWith("/rules/")) {
            runtime.remove(lastSegment(path), actor(exchange));
            write(exchange, 200, runtime.rules());
            return;
        }
        if ("POST".equals(method) && "/agent/disable-all".equals(path)) {
            // V1.7 M1-F §8.6: a loopback emergency op marks the agent so Platform reconciliation
            // does not blindly re-apply desired state over the operator's manual recovery. Mark
            // first so a concurrent snapshot can never observe the mutation without the hold.
            runtime.markEmergency(actor(exchange));
            runtime.disableAll(true);
            write(exchange, 200, runtime.metrics());
            return;
        }
        if ("POST".equals(method) && "/agent/enable-all".equals(path)) {
            runtime.disableAll(false);
            runtime.clearEmergency(actor(exchange));
            write(exchange, 200, runtime.metrics());
            return;
        }
        if ("POST".equals(method) && "/agent/reset-all".equals(path)) {
            runtime.markEmergency(actor(exchange));
            runtime.resetAll(actor(exchange));
            write(exchange, 200, runtime.metrics());
            return;
        }
        if ("POST".equals(method) && "/agent/reset-class".equals(path)) {
            JsonNode body = readJson(exchange);
            String classId = text(body, "classId", text(body, "className", null));
            runtime.markEmergency(actor(exchange));
            ResetClassResult result = runtime.resetClass(classId, actor(exchange));
            write(exchange, 200, result);
            return;
        }
        if ("POST".equals(method) && "/agent/shutdown".equals(path)) {
            runtime.recordEvent("agent.shutdown.requested", actor(exchange), null, null,
                    "Shutdown requested through local API");
            write(exchange, 200, Map.of("status", "SHUTTING_DOWN"));
            shutdownAsync();
            return;
        }
        if ("GET".equals(method) && "/events".equals(path)) {
            write(exchange, 200, runtime.events());
            return;
        }
        if ("GET".equals(method) && "/metrics".equals(path)) {
            write(exchange, 200, runtime.metrics());
            return;
        }
        write(exchange, 404, Map.of("error", "Not found", "path", path));
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("X-Agent-Token");
        if (tokenManager.accepts(header)) {
            return true;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        return authorization != null
                && authorization.startsWith("Bearer ")
                && tokenManager.accepts(authorization.substring("Bearer ".length()));
    }

    private boolean isPublicPath(String path) {
        return "/health".equals(path)
                || "/v1/health".equals(path)
                || isConsolePath(path);
    }

    private boolean isConsolePath(String path) {
        return "/".equals(path)
                || "/index.html".equals(path)
                || "/styles.css".equals(path)
                || "/app.js".equals(path);
    }

    private void serveConsoleAsset(HttpExchange exchange, String path) throws IOException {
        String asset = switch (path) {
            case "/", "/index.html" -> "/web/index.html";
            case "/styles.css" -> "/web/styles.css";
            case "/app.js" -> "/web/app.js";
            default -> throw new IllegalArgumentException("Unknown console asset: " + path);
        };
        try (InputStream inputStream = AgentHttpServer.class.getResourceAsStream(asset)) {
            if (inputStream == null) {
                write(exchange, 404, Map.of("error", "Console asset not found"));
                return;
            }
            byte[] content = inputStream.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(asset));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(content);
            }
        }
    }

    private String contentType(String asset) {
        if (asset.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (asset.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (asset.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        if ("/v1".equals(path)) {
            return "/";
        }
        if (path.startsWith("/v1/")) {
            return path.substring(3);
        }
        return path;
    }

    private RuleRequest readRuleRequest(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        return RuleRequest.from(body);
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            byte[] body = inputStream.readAllBytes();
            if (body.length == 0) {
                return objectMapper.createObjectNode();
            }
            JsonNode node = objectMapper.readTree(body);
            return node == null ? objectMapper.createObjectNode() : node;
        }
    }

    private void shutdownAsync() {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(100L);
                runtime.close();
                close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new UncheckedIOException(new IOException(e));
            }
        }, "kairo-agent-shutdown");
        thread.setDaemon(true);
        thread.start();
    }

    private void write(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Kairo-Protocol", PROTOCOL_VERSION);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, json.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(json);
        }
    }

    private String actor(HttpExchange exchange) {
        String actor = exchange.getRequestHeaders().getFirst("X-Actor");
        return actor == null || actor.isBlank() ? "api" : actor;
    }

    private Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return values;
        }
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            String value = separator < 0 ? "" : part.substring(separator + 1);
            values.put(decode(key), decode(value));
        }
        return values;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    private String requiredText(JsonNode body, String field) {
        String value = text(body, field, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private String text(JsonNode body, String field, String defaultValue) {
        JsonNode node = body.path(field);
        return node.isMissingNode() || node.isNull() ? defaultValue : node.asText();
    }

    private String lastSegment(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? path : decode(path.substring(index + 1));
    }

    private String segment(String path, String prefix, String suffix) {
        return decode(path.substring(prefix.length(), path.length() - suffix.length()));
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    /** Mixin: serialize only fields (no get/is accessors), used for the frozen bytecode DTOs. */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
            getterVisibility = JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = JsonAutoDetect.Visibility.NONE,
            setterVisibility = JsonAutoDetect.Visibility.NONE,
            creatorVisibility = JsonAutoDetect.Visibility.NONE)
    private interface FieldVisibilityMixin {
    }

    public record RuleRequest(
            String id,
            long version,
            String name,
            String description,
            String classId,
            String className,
            String classLoaderId,
            String methodName,
            String methodDescriptor,
            InvokePhase phase,
            EnhancementLocation location,
            CallSiteSelector callSiteSelector,
            String script,
            int priority,
            int percentage,
            long maxHits,
            long expireAt,
            boolean failOpen,
            boolean enabled
    ) {
        static RuleRequest from(JsonNode body) {
            String id = textValue(body, "id", "rule-" + System.currentTimeMillis());
            return new RuleRequest(
                    id,
                    body.path("version").asLong(1L),
                    textValue(body, "name", id),
                    textValue(body, "description", null),
                    required(body, "classId"),
                    required(body, "className"),
                    required(body, "classLoaderId"),
                    required(body, "methodName"),
                    required(body, "methodDescriptor"),
                    InvokePhase.valueOf(textValue(body, "phase", "BEFORE")),
                    readLocation(body),
                    readCallSiteSelector(body),
                    required(body, "script"),
                    body.path("priority").asInt(0),
                    body.path("percentage").asInt(100),
                    body.path("maxHits").asLong(0L),
                    body.path("expireAt").asLong(0L),
                    !body.has("failOpen") || body.path("failOpen").asBoolean(true),
                    !body.has("enabled") || body.path("enabled").asBoolean(true)
            );
        }

        MockRule toRule(List<MethodInfo> ignored) {
            MockRule.Builder builder = MockRule.builder()
                    .id(id)
                    .version(version)
                    .name(name)
                    .description(description)
                    .target(MethodSelector.builder()
                            .className(className)
                            .classLoaderId(classLoaderId)
                            .methodName(methodName)
                            .methodDescriptor(methodDescriptor)
                            .build())
                    .phase(phase)
                    .script(script)
                    .priority(priority)
                    .percentage(percentage)
                    .maxHits(maxHits)
                    .expireAt(expireAt)
                    .failOpen(failOpen)
                    .enabled(enabled);
            // V1.3: an explicit location (and, for call-sites, a selector) is authoritative.
            // A legacy rule without location leaves the builder null so MockRule derives
            // the location from the legacy phase, preserving V1.0/V1.2 behaviour exactly.
            if (location != null) {
                builder.location(location);
            }
            if (callSiteSelector != null) {
                builder.callSiteSelector(callSiteSelector);
            }
            return builder.build();
        }

        /** A constructor rule targets {@code <init>}; the platform publish path must use it. */
        boolean isConstructorRule() {
            return "<init>".equals(methodName);
        }

        private static EnhancementLocation readLocation(JsonNode body) {
            String value = textValue(body, "location", null);
            return value == null || value.isBlank()
                    ? null
                    : EnhancementLocation.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        }

        static CallSiteSelector readCallSiteSelector(JsonNode body) {
            JsonNode node = body.path("callSiteSelector");
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            CallSiteSelector.Builder builder = CallSiteSelector.builder()
                    .owner(required(node, "owner"))
                    .name(required(node, "name"))
                    .descriptor(required(node, "descriptor"))
                    .opcode(InvokeOpcode.valueOf(required(node, "opcode").toUpperCase(java.util.Locale.ROOT)))
                    .occurrenceIndex(node.path("occurrenceIndex").asInt(0));
            String fingerprint = textValue(node, "fingerprint", null);
            if (fingerprint != null) {
                builder.fingerprint(fingerprint);
            }
            return builder.build();
        }

        private static String required(JsonNode body, String field) {
            String value = textValue(body, field, null);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value;
        }

        private static String textValue(JsonNode body, String field, String defaultValue) {
            JsonNode node = body.path(field);
            return node.isMissingNode() || node.isNull() ? defaultValue : node.asText();
        }
    }
}
