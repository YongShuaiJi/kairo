package com.example.kairo.ops;

import com.example.kairo.api.support.SupportBundleWriter;
import com.example.kairo.ops.bundle.OpsSupportBundle;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M4-C &sect;11.3 integration test for {@code kairo-ops support-bundle}. Invokes the real
 * {@link OpsCommand#execute(String[], PrintStream)} command boundary against a JDK stub Agent loopback
 * server and inspects the resulting ZIP: exact entry set + bounded counts, strict schema projection,
 * bounded reads, cumulative whole-operation timeout, oversized-manifest failure, output-basename
 * sanitisation, atomic cleanup, and canary-redaction across config / auth / error / payload /
 * output-name / source data.
 */
class OpsSupportBundleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private final AtomicInteger eventsHits = new AtomicInteger();
    private final AtomicInteger metricsHits = new AtomicInteger();

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        stubAgent();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void stubAgent() {
        server.createContext("/v1/health", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, 200, obj("status", "UP", "protocolVersion", "v1"));
        });
        server.createContext("/v1/status", exchange -> {
            lastToken.set(exchange.getRequestHeaders().getFirst("X-Agent-Token"));
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, 200, obj(
                    "jvm", obj("applicationName", "demo", "pid", 1L, "host", "127.0.0.1",
                            "javaVersion", "17", "startTimeMillis", 1L, "agentVersion", "0.1.0-SNAPSHOT",
                            "loadMode", "attach", "status", "ONLINE", "enhancedClassCount", 3,
                            "enhancedMethodCount", 5, "activeRuleCount", 2),
                    "metrics", obj("loadedClassCount", 100, "enhancedClassCount", 3, "enhancedMethodCount", 5,
                            "totalRuleCount", 2, "activeRuleCount", 2, "totalHits", 42L, "totalErrors", 0L,
                            "globallyEnabled", true),
                    "protocolVersion", "v1"));
        });
        server.createContext("/v1/events", exchange -> {
            eventsHits.incrementAndGet();
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, 200, List.of(
                    obj("timestamp", 1L, "type", "rule.published", "actor", "alice", "ruleId", "r1",
                            "target", "com.example.Foo", "message", "rule published"),
                    obj("timestamp", 2L, "type", "agent.disable-all", "actor", "ops", "ruleId", null,
                            "target", null, "message", "all rules disabled")));
        });
        server.createContext("/v1/metrics", exchange -> {
            metricsHits.incrementAndGet();
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, 200, obj("loadedClassCount", 100, "enhancedClassCount", 3, "enhancedMethodCount", 5,
                    "totalRuleCount", 2, "activeRuleCount", 2, "totalHits", 42L, "totalErrors", 0L,
                    "globallyEnabled", true, "unknownField", "ignored"));
        });
    }

    private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.getResponseBody().close();
    }

    private int run(String... extra) {
        String[] args = new String[3 + extra.length];
        args[0] = "support-bundle";
        args[1] = "--url";
        args[2] = url();
        System.arraycopy(extra, 0, args, 3, extra.length);
        return OpsCommand.execute(args, new PrintStream(out), new PrintStream(err));
    }

    @Test
    void supportBundleProducesBoundedBundleWithExpectedEntries(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("bundle.zip");
        int code = run("--output", zip.toString(), "--token", "plain-token");
        assertThat(code).isEqualTo(0);
        assertThat(Files.exists(zip)).isTrue();
        Map<String, byte[]> entries = readZip(zip);
        assertThat(entries.keySet()).containsExactlyInAnyOrder(
                "manifest.json", "agent-health.json", "agent-status.json",
                "agent-events.json", "agent-metrics.json", "config.json");
        // Bounded events: only 2 entries, projected to {timestamp,type}; status carries the shared agent version.
        JsonNode events = MAPPER.readTree(entries.get("agent-events.json"));
        assertThat(events.size()).isEqualTo(2);
        assertThat(events.get(0).has("actor")).isFalse();
        assertThat(events.get(0).has("ruleId")).isFalse();
        assertThat(events.get(0).has("target")).isFalse();
        assertThat(events.get(0).has("message")).isFalse();
        JsonNode status = MAPPER.readTree(entries.get("agent-status.json"));
        assertThat(status.get("jvm").get("agentVersion").asText()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(status.get("jvm").has("applicationName")).isFalse();
        assertThat(status.get("jvm").has("host")).isFalse();
        JsonNode manifest = MAPPER.readTree(entries.get("manifest.json"));
        assertThat(manifest.get("bundleType").asText()).isEqualTo("agent-support");
        assertThat(manifest.get("tool").get("name").asText()).isEqualTo("kairo-ops");
        // V1.7 M5-A §12.1: the support-bundle manifest tool identity uses the shared build-version resolver.
        assertThat(manifest.get("tool").get("version").asText())
                .isEqualTo(com.example.kairo.api.build.KairoBuildVersion.resolve());
        // Config fully redacted.
        JsonNode config = MAPPER.readTree(entries.get("config.json"));
        assertThat(config.get("agentUrl").get("value").asText()).isEqualTo("***");
        assertThat(config.get("authToken").get("value").asText()).isEqualTo("***");
    }

    @Test
    void canarySecretIsAbsentFromFilenameEntriesAndContent(@TempDir Path dir) throws Exception {
        String canary = "CANARY-7c9f2a-b4e1";
        // Plant the canary in: token (config + auth header), an event message (source data),
        // and the output *directory*.
        server.removeContext("/v1/events");
        server.createContext("/v1/events", exchange -> {
            eventsHits.incrementAndGet();
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, 200, List.of(obj("timestamp", 1L, "type", "rule.published", "actor", "alice",
                    "ruleId", "r1", "target", "com.example.Foo",
                    "message", "rule published with note " + canary)));
        });
        Path canaryDir = dir.resolve(canary + "-outdir");
        Path zip = canaryDir.resolve("bundle.zip");
        int code = run("--output", zip.toString(), "--token", canary);
        assertThat(code).isEqualTo(0);

        // The canary was carried as the X-Agent-Token header (auth/header-like value) ...
        assertThat(lastToken.get()).isEqualTo(canary);
        // ... yet appears in none of the bundle's output locations.
        assertThat(zip.getFileName().toString()).doesNotContain(canary);
        Map<String, byte[]> entries = readZip(zip);
        for (String name : entries.keySet()) {
            assertThat(name).doesNotContain(canary);
            assertThat(new String(entries.get(name), StandardCharsets.UTF_8)).doesNotContain(canary);
        }
        // The event message was projected out (it is never stored, sanitized or not).
        String events = new String(entries.get("agent-events.json"), StandardCharsets.UTF_8);
        assertThat(events).doesNotContain(canary).doesNotContain("rule published with note");
        // Config token redacted.
        assertThat(new String(entries.get("config.json"), StandardCharsets.UTF_8)).doesNotContain(canary);
        // No forbidden artefacts anywhere.
        String all = combined(entries);
        assertThat(all).doesNotContain(canary)
                .doesNotContain("X-Agent-Token")
                .doesNotContain("Bearer ")
                .doesNotContain("password")
                .doesNotContain("script")
                .doesNotContain("stacktrace")
                .doesNotContain("at com.example");
        assertThat(new String(out.toByteArray(), StandardCharsets.UTF_8)).doesNotContain(canary);
        // No temp file left behind.
        try (var stream = Files.list(canaryDir)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith(".kairo-bundle-"))
                    .count()).isZero();
        }
    }

    @Test
    void agentSourceProjectionOmitsCanaryFields(@TempDir Path dir) throws Exception {
        // A distinct canary (NOT the token) planted in every omitted field: only schema projection
        // (not scrubbing) can remove it. Also proves oversized event lists are truncated to 100.
        String canary = "CANARY-OMITTED-FIELD-3f";
        server.removeContext("/v1/status");
        server.createContext("/v1/status", exchange -> {
            writeJson(exchange, 200, obj(
                    "jvm", obj("applicationName", canary, "host", canary, "agentVersion", "0.1.0-SNAPSHOT",
                            "pid", 1L, "javaVersion", "17", "startTimeMillis", 1L, "loadMode", "attach",
                            "status", "ONLINE", "enhancedClassCount", 3, "enhancedMethodCount", 5,
                            "activeRuleCount", 2, "message", "script error: " + canary,
                            "nested", obj("secret", canary)),
                    "metrics", obj("loadedClassCount", 100, "enhancedClassCount", 3,
                            "enhancedMethodCount", 5, "totalRuleCount", 2, "activeRuleCount", 2,
                            "totalHits", 1L, "totalErrors", 0L, "globallyEnabled", true),
                    "protocolVersion", "v1", "extra", canary));
        });
        server.removeContext("/v1/events");
        server.createContext("/v1/events", exchange -> {
            // 150 events: only 100 may be kept; every event carries the canary in omitted fields.
            List<Map<String, Object>> evs = new java.util.ArrayList<>();
            for (int i = 0; i < 150; i++) {
                evs.add(obj("timestamp", (long) i, "type", "rule.published", "actor", canary,
                        "ruleId", canary, "target", canary, "message", "script output: " + canary));
            }
            writeJson(exchange, 200, evs);
        });
        Path zip = dir.resolve("bundle.zip");
        int code = run("--output", zip.toString(), "--token", "plain-token");
        assertThat(code).isEqualTo(0);
        Map<String, byte[]> entries = readZip(zip);
        // Events truncated to exactly 100, and every omitted field is gone.
        JsonNode events = MAPPER.readTree(entries.get("agent-events.json"));
        assertThat(events.size()).isEqualTo(100);
        JsonNode first = events.get(0);
        assertThat(first.get("timestamp").asLong()).isEqualTo(50L);
        assertThat(events.get(99).get("timestamp").asLong()).isEqualTo(149L);
        assertThat(first.has("actor")).isFalse();
        assertThat(first.has("ruleId")).isFalse();
        assertThat(first.has("target")).isFalse();
        assertThat(first.has("message")).isFalse();
        // Status omits applicationName, host, raw message, nested unknowns and extra fields.
        JsonNode status = MAPPER.readTree(entries.get("agent-status.json"));
        assertThat(status.get("jvm").has("applicationName")).isFalse();
        assertThat(status.get("jvm").has("host")).isFalse();
        assertThat(status.get("jvm").has("message")).isFalse();
        assertThat(status.get("jvm").has("nested")).isFalse();
        assertThat(status.has("extra")).isFalse();
        // The canary is absent everywhere (projection removed it; scrubbing could not, it is not the token).
        assertThat(combined(entries)).doesNotContain(canary);
    }

    @Test
    void partialSourceFailureIsSanitizedAndBounded(@TempDir Path dir) throws Exception {
        // /v1/status returns 500 with a body that must NOT be collected.
        server.removeContext("/v1/status");
        server.createContext("/v1/status", exchange -> {
            String body = "{\"error\":\"CANARY-IN-ERROR-BODY\",\"detail\":\"secret\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        Path zip = dir.resolve("bundle.zip");
        int code = run("--output", zip.toString(), "--token", "plain-token");
        assertThat(code).isEqualTo(0);
        Map<String, byte[]> entries = readZip(zip);
        assertThat(entries.keySet()).doesNotContain("agent-status.json");
        assertThat(entries.keySet()).contains("agent-health.json", "agent-events.json", "config.json");
        JsonNode manifest = MAPPER.readTree(entries.get("manifest.json"));
        JsonNode statusSrc = findSource(manifest, "agent-status");
        assertThat(statusSrc.get("status").asText()).isEqualTo("failed");
        assertThat(statusSrc.get("code").asText()).isEqualTo("HTTP_500");
        assertThat(statusSrc.get("httpStatus").asInt()).isEqualTo(500);
        assertThat(statusSrc.has("body")).isFalse();
        assertThat(statusSrc.has("message")).isFalse();
        assertThat(combined(entries)).doesNotContain("CANARY-IN-ERROR-BODY");
    }

    @Test
    void malformedAgentSourceIsSanitizedFailure(@TempDir Path dir) throws Exception {
        // /v1/metrics returns wrong-shape JSON (an array instead of an object).
        server.removeContext("/v1/metrics");
        server.createContext("/v1/metrics", exchange -> {
            metricsHits.incrementAndGet();
            writeJson(exchange, 200, List.of("not", "an", "object"));
        });
        Path zip = dir.resolve("bundle.zip");
        int code = run("--output", zip.toString(), "--token", "plain-token");
        assertThat(code).isEqualTo(0);
        Map<String, byte[]> entries = readZip(zip);
        assertThat(entries.keySet()).doesNotContain("agent-metrics.json");
        JsonNode manifest = MAPPER.readTree(entries.get("manifest.json"));
        JsonNode metricsSrc = findSource(manifest, "agent-metrics");
        assertThat(metricsSrc.get("status").asText()).isEqualTo("failed");
        assertThat(metricsSrc.get("code").asText()).isEqualTo("SOURCE_MALFORMED");
        assertThat(metricsSrc.has("body")).isFalse();
    }

    @Test
    void oversizedSourceIsBoundedRejected(@TempDir Path dir) throws Exception {
        // /v1/status returns a body larger than the 4 MiB source cap, streamed in small chunks.
        server.removeContext("/v1/status");
        server.createContext("/v1/status", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            writeIncompressibleChunked(exchange, 5L * 1024 * 1024);
            exchange.getResponseBody().close();
        });
        Path zip = dir.resolve("bundle.zip");
        int code = run("--output", zip.toString(), "--token", "plain-token");
        assertThat(code).isEqualTo(0);
        Map<String, byte[]> entries = readZip(zip);
        assertThat(entries.keySet()).doesNotContain("agent-status.json");
        JsonNode manifest = MAPPER.readTree(entries.get("manifest.json"));
        JsonNode statusSrc = findSource(manifest, "agent-status");
        assertThat(statusSrc.get("code").asText()).isEqualTo("SOURCE_TOO_LARGE");
        assertThat(entries.keySet()).contains("agent-health.json", "config.json");
        assertThat(Files.size(zip)).isLessThan(1024L * 1024);
    }

    @Test
    void oversizedManifestFailsSafelyWithNoBundle(@TempDir Path dir) throws Exception {
        eventsHits.set(0);
        metricsHits.set(0);
        // A budget so tiny that even the manifest alone cannot fit: fail safely, no bundle, no temp.
        Path zip = dir.resolve("bundle.zip");
        int code = run("--output", zip.toString(), "--token", "plain-token", "--max-size-bytes", "1");
        assertThat(code).isEqualTo(4);
        assertThat(Files.exists(zip)).isFalse();
        try (var stream = Files.list(dir)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith(".kairo-bundle-"))
                    .count()).isZero();
        }
        assertThat(new String(err.toByteArray(), StandardCharsets.UTF_8)).contains("BUNDLE_TOO_LARGE");
    }

    @Test
    void defaultTimeoutIsThirtySeconds() {
        assertThat(SupportBundleWriter.DEFAULT_TIMEOUT_MILLIS).isEqualTo(30_000L);
    }

    @Test
    void shortTimeoutAbortsBundleWithoutSleepingThirtySeconds(@TempDir Path dir) throws Exception {
        server.removeContext("/v1/health");
        server.createContext("/v1/health", exchange -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeJson(exchange, 200, obj("status", "UP"));
        });
        Path zip = dir.resolve("bundle.zip");
        long start = System.nanoTime();
        int code = run("--output", zip.toString(), "--token", "plain-token", "--timeout-ms", "50");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(code).isEqualTo(3);
        assertThat(elapsedMs).isLessThan(1500L);
        assertThat(Files.exists(zip)).isFalse();
        try (var stream = Files.list(dir)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith(".kairo-bundle-"))
                    .count()).isZero();
        }
    }

    @Test
    void timeoutAlsoBoundsBodyConsumptionAfterHeaders(@TempDir Path dir) throws Exception {
        server.removeContext("/v1/health");
        server.createContext("/v1/health", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write("{\"status\":\"".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                Thread.sleep(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Expected when the deadline-aware client closes the stalled body.
            } finally {
                exchange.close();
            }
        });
        Path zip = dir.resolve("bundle.zip");
        long start = System.nanoTime();
        int code = run("--output", zip.toString(), "--token", "plain-token", "--timeout-ms", "100");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(code).isEqualTo(3);
        assertThat(elapsedMs).isLessThan(1_500L);
        assertThat(Files.exists(zip)).isFalse();
    }

    @Test
    void cumulativeTimeoutAbortsAndSkipsLaterSources(@TempDir Path dir) throws Exception {
        eventsHits.set(0);
        metricsHits.set(0);
        // Two individually-short sources that together exceed the whole deadline: health succeeds (its
        // per-request timeout is the full deadline), then status's per-request timeout is the *remaining*
        // deadline and aborts mid-source; later sources (events/metrics) are never requested.
        server.removeContext("/v1/health");
        server.createContext("/v1/health", exchange -> {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeJson(exchange, 200, obj("status", "UP", "protocolVersion", "v1"));
        });
        server.removeContext("/v1/status");
        server.createContext("/v1/status", exchange -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeJson(exchange, 200, obj("protocolVersion", "v1"));
        });
        Path zip = dir.resolve("bundle.zip");
        long start = System.nanoTime();
        int code = run("--output", zip.toString(), "--token", "plain-token", "--timeout-ms", "600");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(code).isEqualTo(3);
        assertThat(Files.exists(zip)).isFalse();
        // Later sources (events, metrics) were never requested.
        assertThat(eventsHits.get()).isZero();
        assertThat(metricsHits.get()).isZero();
        try (var stream = Files.list(dir)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith(".kairo-bundle-"))
                    .count()).isZero();
        }
        assertThat(elapsedMs).isLessThan(3000L);
    }

    @Test
    void outputBasenameWithSecretIsSanitized(@TempDir Path dir) throws Exception {
        String canary = "CANARY-BASENAME-ops-2a";
        // The requested ZIP basename itself carries the secret token.
        Path requested = dir.resolve(canary + "-bundle.zip");
        int code = run("--output", requested.toString(), "--token", canary);
        assertThat(code).isEqualTo(0);
        assertThat(Files.exists(requested)).isFalse();
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> assertThat(p.getFileName().toString()).doesNotContain(canary));
        }
        Path actual = dir.resolve("support-bundle.zip");
        assertThat(Files.exists(actual)).isTrue();
        String stdout = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertThat(stdout).contains("support-bundle.zip").doesNotContain(canary);
    }

    @Test
    void invalidArgumentsAreRejectedWithFixedMessage(@TempDir Path dir) {
        int code = run("--output", dir.resolve("b.zip").toString(), "--token", "t", "--timeout-ms", "0");
        assertThat(code).isEqualTo(64);
        assertThat(new String(err.toByteArray(), StandardCharsets.UTF_8))
                .contains("INVALID_ARGUMENT");
    }

    private static JsonNode findSource(JsonNode manifest, String name) {
        for (JsonNode s : manifest.get("sources")) {
            if (name.equals(s.get("name").asText())) {
                return s;
            }
        }
        throw new AssertionError("source not found: " + name);
    }

    private static Map<String, byte[]> readZip(Path zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (ZipEntry entry : zf.stream().toList()) {
                entries.put(entry.getName(), zf.getInputStream(entry).readAllBytes());
            }
        }
        return entries;
    }

    private static String combined(Map<String, byte[]> entries) {
        StringBuilder sb = new StringBuilder();
        for (byte[] b : entries.values()) {
            sb.append(new String(b, StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString();
    }

    /** Stream `total` deterministic incompressible bytes to the exchange in small chunks (no full allocation). */
    private static void writeIncompressibleChunked(HttpExchange exchange, long total) throws IOException {
        byte[] buf = new byte[8192];
        int state = 0x12345678;
        long written = 0;
        var os = exchange.getResponseBody();
        while (written < total) {
            for (int i = 0; i < buf.length; i++) {
                state = state * 1103515245 + 12345;
                buf[i] = (byte) (state >>> 24);
            }
            int n = (int) Math.min(buf.length, total - written);
            os.write(buf, 0, n);
            written += n;
        }
    }
}
