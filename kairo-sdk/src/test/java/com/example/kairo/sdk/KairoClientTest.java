package com.example.kairo.sdk;

import com.example.kairo.api.automation.AutomationSession;
import com.example.kairo.api.automation.AutomationSessionStatus;
import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.operation.Operation;
import com.example.kairo.api.operation.OperationStatus;
import com.example.kairo.api.operation.OperationType;
import com.example.kairo.api.write.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK integration test (V1.6 &sect;9) against a JDK stub HTTP server: verifies
 * typed responses, header propagation (Authorization, Idempotency-Key) and
 * structured error parsing.
 */
class KairoClientTest {

    private HttpServer server;
    private KairoClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> lastIdempotencyKey = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.start();
        client = new KairoClient(new KairoClientConfig("http://127.0.0.1:" + port, "test-token")
                .source("sdk").correlationId("corr-test"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void stub(String path, String method, HttpHandler handler) {
        server.createContext(path, exchange -> {
            if (!exchange.getRequestMethod().equals(method)) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            handler.handle(exchange);
        });
    }

    private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = new ObjectMapper().writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.getResponseBody().close();
    }

    @Test
    void whoAmIReturnsTypedMap() {
        stub("/api/v1/auth/me", "GET", ex -> writeJson(ex, 200, Map.of("subject", "alice", "roles", List.of("BUSINESS_USER"))));
        Map<String, Object> me = client.whoAmI();
        assertThat(me.get("subject")).isEqualTo("alice");
        assertThat(lastAuth.get()).isEqualTo("Bearer test-token");
    }

    @Test
    void createAutomationSessionReturnsTypedRecordAndPropagatesIdempotencyKey() throws Exception {
        AutomationSession session = new AutomationSession("auto-1", "ai-bot", "mcp", "app-default",
                "env-default", null, "agent-1", CapabilityProfile.SAFE, 600_000L, 99_999_999L,
                AutomationSessionStatus.CREATED, RiskLevel.LOW, List.of(), Map.of(), "corr", 0, 1L, 1L);
        stub("/api/v1/automation-sessions", "POST", ex -> writeJson(ex, 201, session));
        AutomationSession result = client.createAutomationSession("ai-bot", "mcp", "app-default",
                "env-default", "SAFE", 600_000L, "idem-1");
        assertThat(result.sessionId()).isEqualTo("auto-1");
        assertThat(result.maxCapabilityProfile()).isEqualTo(CapabilityProfile.SAFE);
        assertThat(lastIdempotencyKey.get()).isEqualTo("idem-1");
    }

    @Test
    void getOperationReturnsTypedRecord() throws Exception {
        Operation op = new Operation("op-1", OperationType.RULE_PUBLISH, OperationStatus.SUCCEEDED,
                "rule", "r-1", RiskLevel.HIGH, null, 100, Map.of("ok", true),
                null, "op-revert-1", "corr", "alice", 1L, 2L, 2L);
        stub("/api/v1/operations/op-1", "GET", ex -> writeJson(ex, 200, op));
        Operation result = client.getOperation("op-1");
        assertThat(result.operationId()).isEqualTo("op-1");
        assertThat(result.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(result.revertOperationId()).isEqualTo("op-revert-1");
    }

    @Test
    void structuredErrorIsParsedIntoApiException() {
        server.createContext("/api/v1/operations/missing", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String body = """
                    {"code":"RESOURCE_NOT_FOUND","message":"未找到资源","category":"NOT_FOUND","retryable":false}
                    """;
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        assertThatThrownBy(() -> client.getOperation("missing"))
                .isInstanceOf(KairoApiException.class)
                .satisfies(ex -> {
                    KairoApiException api = (KairoApiException) ex;
                    assertThat(api.status()).isEqualTo(404);
                    assertThat(api.code()).isEqualTo("RESOURCE_NOT_FOUND");
                    assertThat(api.error().category())
                            .isEqualTo(com.example.kairo.api.error.ErrorCategory.NOT_FOUND);
                });
    }
}
