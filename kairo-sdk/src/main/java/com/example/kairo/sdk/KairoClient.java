package com.example.kairo.sdk;

import com.example.kairo.api.automation.AutomationSession;
import com.example.kairo.api.automation.EnhancementContextBundle;
import com.example.kairo.api.error.ApiError;
import com.example.kairo.api.error.ErrorCategory;
import com.example.kairo.api.operation.Operation;
import com.example.kairo.api.operation.OperationEvent;
import com.example.kairo.api.write.PreviewResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Official Java SDK for the Kairo Platform V1 API (V1.6 &sect;5.4). A thin,
 * strongly-typed HTTP client used by the CLI and MCP server &mdash; it never
 * bypasses the Platform (no agent-direct or DB access) and always carries the
 * caller's Bearer token, correlation id and idempotency key.
 *
 * <p>Covers the full AI/automation lifecycle: discover, validate, preview,
 * trial, promote, observe and revert (&sect;5.4).
 */
public final class KairoClient {

    private final KairoClientConfig config;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public KairoClient(KairoClientConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public KairoClientConfig config() {
        return config;
    }

    // ---- auth ----

    public Map<String, Object> whoAmI() {
        return get("/api/v1/auth/me");
    }

    // ---- targets / discovery ----

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchTargets(String applicationId, String environmentId, String query) {
        String q = query == null ? "" : query;
        return getList("/api/v1/targets?applicationId=" + enc(applicationId)
                + "&environmentId=" + enc(environmentId) + "&query=" + enc(q));
    }

    public Map<String, Object> listLoaders(String applicationId, String environmentId) {
        return get("/api/v1/targets/loaders?applicationId=" + enc(applicationId)
                + "&environmentId=" + enc(environmentId));
    }

    // ---- automation sessions (AI lifecycle) ----

    public AutomationSession createAutomationSession(String caller, String source, String applicationId,
                                                     String environmentId, String requestedCapabilityProfile,
                                                     long ttlMillis, String idempotencyKey) {
        Map<String, Object> body = Map.of(
                "caller", caller,
                "source", source,
                "applicationId", applicationId,
                "environmentId", environmentId,
                "requestedCapabilityProfile", requestedCapabilityProfile,
                "ttlMillis", ttlMillis);
        return post("/api/v1/automation-sessions", body, idempotencyKey, AutomationSession.class);
    }

    public EnhancementContextBundle resolveTargets(String sessionId, String query, String environmentId) {
        Map<String, Object> body = environmentId == null || environmentId.isBlank()
                ? Map.of("query", query == null ? "" : query)
                : Map.of("query", query == null ? "" : query, "environmentId", environmentId);
        return post("/api/v1/automation-sessions/" + enc(sessionId) + "/resolve-targets", body, null,
                EnhancementContextBundle.class);
    }

    public Map<String, Object> validateScript(String sessionId, String script) {
        return post("/api/v1/automation-sessions/" + enc(sessionId) + "/validate-script",
                Map.of("script", script), null);
    }

    public PreviewResult preview(String sessionId, Map<String, Object> target) {
        return post("/api/v1/automation-sessions/" + enc(sessionId) + "/preview",
                Map.of("target", target), null, PreviewResult.class);
    }

    public Map<String, Object> trial(String sessionId, Map<String, Object> target, String script,
                                     String capabilityProfile, long ttlMillis, long maxHits,
                                     String idempotencyKey) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("target", target);
        body.put("script", script);
        body.put("capabilityProfile", capabilityProfile);
        body.put("ttlMillis", ttlMillis);
        body.put("maxHits", maxHits);
        return post("/api/v1/automation-sessions/" + enc(sessionId) + "/trial", body, idempotencyKey);
    }

    public Map<String, Object> promote(String sessionId, String scriptSessionId) {
        return post("/api/v1/automation-sessions/" + enc(sessionId) + "/promote",
                Map.of("scriptSessionId", scriptSessionId), null);
    }

    public AutomationSession revertSession(String sessionId) {
        return post("/api/v1/automation-sessions/" + enc(sessionId) + "/revert", Map.of(), null,
                AutomationSession.class);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> sessionEvents(String sessionId) {
        return getList("/api/v1/automation-sessions/" + enc(sessionId) + "/events");
    }

    public AutomationSession getAutomationSession(String sessionId) {
        return get("/api/v1/automation-sessions/" + enc(sessionId), AutomationSession.class);
    }

    // ---- operations (observe) ----

    public Operation getOperation(String operationId) {
        return get("/api/v1/operations/" + enc(operationId), Operation.class);
    }

    @SuppressWarnings("unchecked")
    public List<Operation> listOperations(String status, int limit) {
        Map<String, Object> page = get("/api/v1/operations?limit=" + limit
                + (status == null || status.isBlank() ? "" : "&status=" + enc(status)));
        Object items = page.get("items");
        if (items instanceof List<?> list) {
            return list.stream().map(o -> mapper.convertValue(o, Operation.class)).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public List<OperationEvent> operationEvents(String operationId) {
        Object raw = request("GET", "/api/v1/operations/" + enc(operationId) + "/events",
                null, null, Object.class, true);
        if (raw instanceof List<?> list) {
            return list.stream().map(o -> mapper.convertValue(o, OperationEvent.class)).toList();
        }
        return List.of();
    }

    // ---- scripts ----

    public Map<String, Object> compileScript(String applicationId, String agentId, String script) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (applicationId != null) {
            body.put("applicationId", applicationId);
        }
        if (agentId != null) {
            body.put("agentId", agentId);
        }
        body.put("script", script);
        return post("/api/v1/scripts/compile", body, null);
    }

    // ---- HTTP plumbing ----

    private Map<String, Object> get(String path) {
        return request("GET", path, null, null, Map.class, true);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String path) {
        Object result = request("GET", path, null, null, Object.class, true);
        if (result instanceof List<?> list) {
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (Object o : list) {
                out.add((Map<String, Object>) o);
            }
            return out;
        }
        return List.of();
    }

    private <T> T get(String path, Class<T> type) {
        return request("GET", path, null, null, type, true);
    }

    private Map<String, Object> post(String path, Object body, String idempotencyKey) {
        return request("POST", path, body, idempotencyKey, Map.class, true);
    }

    private <T> T post(String path, Object body, String idempotencyKey, Class<T> type) {
        return request("POST", path, body, idempotencyKey, type, true);
    }

    @SuppressWarnings("unchecked")
    private <T> T request(String method, String path, Object body, String idempotencyKey,
                          Class<T> type, boolean unwrapPage) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + path))
                    .timeout(config.timeout())
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + config.token());
            if (config.correlationId() != null && !config.correlationId().isBlank()) {
                builder.header("X-Correlation-Id", config.correlationId());
            }
            if (config.source() != null) {
                builder.header("X-Source", config.source());
            }
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                builder.header("Idempotency-Key", idempotencyKey);
            }
            if (body != null) {
                byte[] payload = mapper.writeValueAsBytes(body);
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                if (responseBody == null || responseBody.isBlank()) {
                    return null;
                }
                return mapper.readValue(responseBody, type);
            }
            ApiError error = null;
            if (responseBody != null && !responseBody.isBlank()) {
                try {
                    error = mapper.readValue(responseBody, ApiError.class);
                } catch (Exception ignored) {
                    error = ApiError.of("HTTP_ERROR", "HTTP " + response.statusCode(),
                            ErrorCategory.INTERNAL, false);
                }
            }
            throw new KairoApiException(response.statusCode(), error);
        } catch (KairoApiException e) {
            throw e;
        } catch (Exception e) {
            throw new KairoApiException(0, ApiError.of("SDK_TRANSPORT_ERROR", e.getMessage(),
                    ErrorCategory.INTERNAL, true));
        }
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
