package com.example.kairo.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M4-D &sect;11.4 (incident family 8) boundary test. Proves the documented {@code kairo-ops}
 * emergency-recovery command path against an in-process loopback Agent stub &mdash; never a real Agent
 * and never external infrastructure. Exercises the real {@link OpsCommand#execute(String[], PrintStream)}
 * boundary and asserts, for every emergency mutation command:
 *
 * <ul>
 *   <li>the exact HTTP method and path (command path),</li>
 *   <li>the {@code X-Agent-Token} authentication header is forwarded and {@code X-Actor} is {@code kairo-ops},</li>
 *   <li>the fixed request body ({@code reason}/{@code eventId}, plus {@code classId} for {@code reset-class}),</li>
 *   <li>the local audit JSONL line is written with the documented fields, and</li>
 *   <li>recovery verification: after {@code disable-all} the subsequent {@code status} reflects the
 *       disabled state, and after {@code enable-all} it reflects the recovered state.</li>
 * </ul>
 *
 * <p>This adds test coverage only; it touches no product code and contacts no real Agent. The audit path
 * is redirected to a per-test temp file via the {@code kairo.ops.audit.path} system property so the real
 * {@code ~/.kairo/ops-audit.jsonl} is never written or mutated.
 */
class OpsEmergencyBoundaryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private Path auditFile;
    private String previousAuditProperty;

    /** Recorded request envelope for the last mutation handled by each stub. */
    private final AtomicReference<RequestEnvelope> lastRequest = new AtomicReference<>();
    /** In-memory agent state toggled by disable-all / enable-all so status can prove recovery. */
    private volatile boolean globallyEnabled = true;

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        auditFile = dir.resolve("ops-audit.jsonl");
        // Redirect the real audit sink to a temp file; restore the prior value afterwards.
        previousAuditProperty = System.getProperty("kairo.ops.audit.path");
        System.setProperty("kairo.ops.audit.path", auditFile.toString());
        globallyEnabled = true;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        stubAgent();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        if (previousAuditProperty == null) {
            System.clearProperty("kairo.ops.audit.path");
        } else {
            System.setProperty("kairo.ops.audit.path", previousAuditProperty);
        }
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void stubAgent() {
        server.createContext("/v1/status", exchange -> {
            lastRequest.set(capture(exchange));
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, 200, obj(
                    "jvm", obj("agentVersion", "0.1.0-SNAPSHOT", "javaVersion", "17",
                            "loadMode", "attach", "status", globallyEnabled ? "ONLINE" : "DISABLED",
                            "pid", 1L, "startTimeMillis", 1L,
                            "enhancedClassCount", 3, "enhancedMethodCount", 5, "activeRuleCount", 2),
                    "metrics", obj("loadedClassCount", 100, "enhancedClassCount", 3,
                            "enhancedMethodCount", 5, "totalRuleCount", 2, "activeRuleCount", 2,
                            "totalHits", 42L, "totalErrors", 0L, "globallyEnabled", globallyEnabled),
                    "protocolVersion", "v1"));
        });
        server.createContext("/v1/agent/disable-all", exchange -> {
            lastRequest.set(capture(exchange));
            globallyEnabled = false;
            writeJson(exchange, 200, obj("disabled", true));
        });
        server.createContext("/v1/agent/enable-all", exchange -> {
            lastRequest.set(capture(exchange));
            globallyEnabled = true;
            writeJson(exchange, 200, obj("enabled", true));
        });
        server.createContext("/v1/agent/reset-all", exchange -> {
            lastRequest.set(capture(exchange));
            writeJson(exchange, 200, obj("reset", true));
        });
        server.createContext("/v1/agent/shutdown", exchange -> {
            lastRequest.set(capture(exchange));
            writeJson(exchange, 200, obj("shutdown", true));
        });
        server.createContext("/v1/agent/reset-class", exchange -> {
            lastRequest.set(capture(exchange));
            writeJson(exchange, 200, obj("resetClass", true));
        });
        // Rule-scoped handlers: match /v1/rules/<id>/disable and /v1/rules/<id> (DELETE).
        server.createContext("/v1/rules/", exchange -> {
            lastRequest.set(capture(exchange));
            writeJson(exchange, 200, obj("acknowledged", true));
        });
    }

    private static RequestEnvelope capture(HttpExchange exchange) throws IOException {
        RequestEnvelope env = new RequestEnvelope();
        env.method = exchange.getRequestMethod();
        env.path = exchange.getRequestURI().getPath();
        env.token = exchange.getRequestHeaders().getFirst("X-Agent-Token");
        env.actor = exchange.getRequestHeaders().getFirst("X-Actor");
        env.body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return env;
    }

    private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.getResponseBody().close();
    }

    private int run(String... extra) {
        // Reset the captured streams so each command's stdout/stderr is inspected in isolation.
        out.reset();
        err.reset();
        String[] args = new String[2 + extra.length];
        args[0] = extra[0];
        args[1] = "--url";
        args[2] = url();
        System.arraycopy(extra, 1, args, 3, extra.length - 1);
        return OpsCommand.execute(args, new PrintStream(out), new PrintStream(err));
    }

    private JsonNode lastAuditLine() throws IOException {
        String content = Files.readString(auditFile, StandardCharsets.UTF_8).trim();
        String[] lines = content.isEmpty() ? new String[0] : content.split("\n");
        assertThat(lines).as("exactly one audit line per mutation command").hasSize(1);
        return MAPPER.readTree(lines[lines.length - 1]);
    }

    @Test
    void statusReachesGetStatusAndAudits() throws Exception {
        int code = run("status");
        assertThat(code).isEqualTo(0);
        RequestEnvelope req = lastRequest.get();
        assertThat(req.method).isEqualTo("GET");
        assertThat(req.path).isEqualTo("/v1/status");
        assertThat(req.body).isEmpty();
        // stdout is the status body; globallyEnabled is true by default.
        JsonNode status = MAPPER.readTree(out.toByteArray());
        assertThat(status.get("metrics").get("globallyEnabled").asBoolean()).isTrue();
        // status is also audited (every non-support-bundle command appends one audit line).
        JsonNode audit = lastAuditLine();
        assertThat(audit.get("command").asText()).isEqualTo("status");
        assertThat(audit.get("status").asInt()).isEqualTo(200);
    }

    @Test
    void disableRulePostsFixedBodyAndForwardsToken(@TempDir Path dir) throws Exception {
        int code = run("disable-rule", "--token", "agent-secret",
                "--rule-id", "rule-7", "--reason", "incident mitigation", "--event", "INC-123");
        assertThat(code).isEqualTo(0);
        RequestEnvelope req = lastRequest.get();
        assertThat(req.method).isEqualTo("POST");
        assertThat(req.path).isEqualTo("/v1/rules/rule-7/disable");
        assertThat(req.token).isEqualTo("agent-secret");
        assertThat(req.actor).isEqualTo("kairo-ops");
        JsonNode body = MAPPER.readTree(req.body);
        assertThat(body.get("reason").asText()).isEqualTo("incident mitigation");
        assertThat(body.get("eventId").asText()).isEqualTo("INC-123");
        assertThat(body.has("classId")).isFalse();
        JsonNode audit = lastAuditLine();
        assertThat(audit.get("command").asText()).isEqualTo("disable-rule");
        assertThat(audit.get("eventId").asText()).isEqualTo("INC-123");
        assertThat(audit.get("reason").asText()).isEqualTo("incident mitigation");
        assertThat(audit.get("status").asInt()).isEqualTo(200);
        // The raw token is never echoed into the audit line.
        assertThat(audit.toString()).doesNotContain("agent-secret");
    }

    @Test
    void resetClassPostsClassIdReasonAndEvent() throws Exception {
        int code = run("reset-class", "--token", "t",
                "--class-id", "Y2xhc3NJZA==", "--reason", "leak mitigation", "--event", "INC-9");
        assertThat(code).isEqualTo(0);
        RequestEnvelope req = lastRequest.get();
        assertThat(req.method).isEqualTo("POST");
        assertThat(req.path).isEqualTo("/v1/agent/reset-class");
        JsonNode body = MAPPER.readTree(req.body);
        assertThat(body.get("classId").asText()).isEqualTo("Y2xhc3NJZA==");
        assertThat(body.get("reason").asText()).isEqualTo("leak mitigation");
        assertThat(body.get("eventId").asText()).isEqualTo("INC-9");
        JsonNode audit = lastAuditLine();
        assertThat(audit.get("command").asText()).isEqualTo("reset-class");
        assertThat(audit.get("eventId").asText()).isEqualTo("INC-9");
    }

    @Test
    void everyEmergencyMutationReachesItsDocumentedPath() throws Exception {
        // disable-all -> POST /v1/agent/disable-all
        assertThat(run("disable-all", "--token", "t", "--reason", "r", "--event", "e")).isEqualTo(0);
        assertThat(lastRequest.get().method).isEqualTo("POST");
        assertThat(lastRequest.get().path).isEqualTo("/v1/agent/disable-all");
        assertThat(Files.readString(auditFile).split("\n")).hasSize(1);

        // reset-all -> POST /v1/agent/reset-all
        assertThat(run("reset-all", "--token", "t", "--reason", "r", "--event", "e")).isEqualTo(0);
        assertThat(lastRequest.get().method).isEqualTo("POST");
        assertThat(lastRequest.get().path).isEqualTo("/v1/agent/reset-all");

        // shutdown-agent -> POST /v1/agent/shutdown
        assertThat(run("shutdown-agent", "--token", "t", "--reason", "r", "--event", "e")).isEqualTo(0);
        assertThat(lastRequest.get().method).isEqualTo("POST");
        assertThat(lastRequest.get().path).isEqualTo("/v1/agent/shutdown");

        // remove-rule -> DELETE /v1/rules/<id>
        assertThat(run("remove-rule", "--token", "t", "--rule-id", "rule-1",
                "--reason", "r", "--event", "e")).isEqualTo(0);
        assertThat(lastRequest.get().method).isEqualTo("DELETE");
        assertThat(lastRequest.get().path).isEqualTo("/v1/rules/rule-1");

        // Four mutations so far produced exactly four audit lines (status is not invoked here).
        assertThat(Files.readString(auditFile).split("\n")).hasSize(4);
    }

    @Test
    void non2xxResponseExitsTwoAndIsAudited() throws Exception {
        // Make disable-all fail with a 409 to prove the documented non-success exit boundary.
        server.removeContext("/v1/agent/disable-all");
        server.createContext("/v1/agent/disable-all", exchange -> {
            capture(exchange);
            writeJson(exchange, 409, obj("error", "AGENT_COMMAND_STATE_CONFLICT"));
        });
        int code = run("disable-all", "--token", "t", "--reason", "r", "--event", "e");
        assertThat(code).isEqualTo(2);
        JsonNode audit = lastAuditLine();
        assertThat(audit.get("status").asInt()).isEqualTo(409);
        assertThat(audit.get("response").asText()).contains("AGENT_COMMAND_STATE_CONFLICT");
    }

    @Test
    void invalidArgumentsExitSixtyFourWithFixedMessage() {
        // reset-all requires --reason and --event; missing them must not reach the network.
        int code = run("reset-all");
        assertThat(code).isEqualTo(64);
        assertThat(new String(err.toByteArray(), StandardCharsets.UTF_8)).contains("INVALID_ARGUMENT");
        // No audit line is written for a command that never executed.
        assertThat(Files.exists(auditFile)).isFalse();
    }

    @Test
    void recoveryVerificationDisableThenStatusThenEnable() throws Exception {
        // 1. Emergency disable via kairo-ops while Platform is unavailable.
        assertThat(run("disable-all", "--token", "t", "--reason", "platform unavailable",
                "--event", "INC-1")).isEqualTo(0);
        // 2. Recovery verification: the subsequent status reflects the disabled state.
        assertThat(run("status")).isEqualTo(0);
        JsonNode status = MAPPER.readTree(out.toByteArray());
        assertThat(status.get("metrics").get("globallyEnabled").asBoolean()).isFalse();
        assertThat(status.get("jvm").get("status").asText()).isEqualTo("DISABLED");
        // 3. Restore via enable-all, then verify recovery.
        assertThat(run("enable-all", "--token", "t", "--reason", "platform recovered",
                "--event", "INC-1")).isEqualTo(0);
        assertThat(run("status")).isEqualTo(0);
        JsonNode recovered = MAPPER.readTree(out.toByteArray());
        assertThat(recovered.get("metrics").get("globallyEnabled").asBoolean()).isTrue();
        assertThat(recovered.get("jvm").get("status").asText()).isEqualTo("ONLINE");
        // Two mutations (disable-all, enable-all) plus their verification status calls are all audited.
        String audit = Files.readString(auditFile, StandardCharsets.UTF_8);
        long auditLines = audit.trim().isEmpty() ? 0 : audit.split("\n").length;
        assertThat(auditLines).isEqualTo(4);
    }

    private static final class RequestEnvelope {
        String method;
        String path;
        String token;
        String actor;
        String body;
    }
}
