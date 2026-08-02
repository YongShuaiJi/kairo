package com.example.kairo.cli;

import com.example.kairo.api.support.SupportBundleWriter;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M4-C &sect;11.3 integration test for {@code kairo-cli diagnose}. Invokes the real
 * {@link KairoCli#run(String[])} command boundary against a JDK stub Platform server and inspects the
 * resulting ZIP: exact entry set + bounded counts, strict metric allowlist, bounded reads, exact
 * platform projections, 20 MiB + injectable-size enforcement, cumulative whole-operation timeout,
 * oversized-manifest failure, output-basename sanitisation, atomic cleanup, and canary-redaction
 * across config / auth / error / payload / output-name / source data.
 */
class KairoCliSupportBundleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The exact ten frozen V1.7 M4-B meter names (mirrors DiagnoseSupportBundle.ALLOWED_METERS). */
    private static final List<String> TEN_METERS = List.of(
            "kairo_agent_online", "kairo_agent_command_backlog", "kairo_agent_command_total",
            "kairo_operation_total", "kairo_operation_duration_seconds", "kairo_runtime_rule_targets",
            "kairo_reconcile_total", "kairo_rollback_total", "kairo_ttl_cleanup_total",
            "kairo_platform_build_info");

    /** Captures the Authorization header the CLI sends, to prove auth values are sent but never bundled. */
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    /** Counts hits to /api/v1/operations so the cumulative-timeout test can prove later sources are skipped. */
    private final AtomicInteger operationsHits = new AtomicInteger();


    /** Builds an ordered map from alternating key/value pairs (handles mixed value types & >10 pairs). */
    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }


    private HttpServer server;
    private Path credsPath;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private KairoCli cli;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        credsPath = Files.createTempFile("kairo-creds", ".json");
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        cli = new KairoCli(new PrintStream(out), new PrintStream(err), credsPath);
        stubActuator();
        stubOperations();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.stop(0);
        Files.deleteIfExists(credsPath);
    }

    private void writeCreds(String token) throws IOException {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        Files.writeString(credsPath, MAPPER.writeValueAsString(Map.of("baseUrl", baseUrl, "token", token)));
    }

    private void stubActuator() {
        server.createContext("/actuator/health", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, 200, obj(
                    "status", "UP",
                    "components", obj(
                            "db", obj("status", "UP", "details", obj("database", "H2")),
                            "flyway", obj("status", "UP", "details", obj("validateCount", 5)))));
        });
        server.createContext("/actuator/info", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, 200, obj("build", obj(
                    "version", "1.7.0-SNAPSHOT", "commit", "abc123",
                    "javaTarget", "21",
                    "contractBaseline", obj("version", "V1.6.0", "commit", "113823b4"))));
        });
        server.createContext("/actuator/metrics", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/actuator/metrics".equals(path)) {
                writeJson(exchange, 200, obj("names", List.of(
                        "kairo_operation_total", "kairo_agent_online", "jvm.gc.pause")));
                return;
            }
            String name = path.substring("/actuator/metrics/".length());
            if (name.startsWith("kairo_")) {
                writeJson(exchange, 200, obj(
                        "name", name,
                        "description", "ignore-me",
                        "baseUnit", "ignore",
                        "measurements", List.of(obj("statistic", "COUNT", "value", 3)),
                        "availableTags", List.of(obj("tag", "result",
                                "values", List.of("SUCCESS", "FAILURE")))));
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
    }

    private void stubOperations() {
        server.createContext("/api/v1/operations", exchange -> {
            operationsHits.incrementAndGet();
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            List<Map<String, Object>> items = List.of(
                    obj("operationId", "op-1", "type", "RULE_PUBLISH", "status", "SUCCEEDED",
                            "resourceType", "rule", "resourceId", "r1", "progress", 100,
                            "createdAt", 1L, "updatedAt", 2L, "completedAt", 3L, "correlationId", "c1",
                            "actor", "alice", "riskLevel", "LOW"),
                    obj("operationId", "op-2", "type", "AGENT_COMMAND", "status", "FAILED",
                            "resourceType", "agent_command", "resourceId", "cmd-1", "progress", 100,
                            "createdAt", 4L, "updatedAt", 5L, "completedAt", 6L, "correlationId", "c2",
                            "actor", "bob", "riskLevel", "MEDIUM",
                            "result", obj("secretPayload", "SHOULD-NOT-APPEAR"),
                            "error", obj("code", "AGENT_COMMAND_FAILED",
                                    "message", "SHOULD-NOT-APPEAR", "category", "INTERNAL")));
            writeJson(exchange, 200, obj("items", items, "limit", 20));
        });
    }

    private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.getResponseBody().close();
    }

    @Test
    void diagnoseProducesBoundedBundleWithExpectedEntries(@TempDir Path dir) throws Exception {
        writeCreds("plain-token");
        Path zip = dir.resolve("bundle.zip");
        int code = cli.run(new String[]{"diagnose", "--output", zip.toString()});
        assertThat(code).isEqualTo(0);
        assertThat(Files.exists(zip)).isTrue();
        Map<String, byte[]> entries = readZip(zip);
        assertThat(entries.keySet()).containsExactlyInAnyOrder(
                "manifest.json",
                "actuator/health.json",
                "actuator/info.json",
                "metrics/index.json",
                "metrics/kairo_agent_online.json",
                "metrics/kairo_operation_total.json",
                "operations/recent.json",
                "config.json");
        // Bounded operation summary: only 2 ops, result/error.message projected out.
        JsonNode ops = MAPPER.readTree(entries.get("operations/recent.json"));
        assertThat(ops.get("count").asInt()).isEqualTo(2);
        assertThat(ops.get("items").size()).isEqualTo(2);
        assertThat(new String(entries.get("operations/recent.json"), StandardCharsets.UTF_8))
                .doesNotContain("secretPayload").doesNotContain("SHOULD-NOT-APPEAR");
        assertThat(ops.get("items").get(0).has("result")).isFalse();
        assertThat(ops.get("items").get(0).has("operationId")).isFalse();
        assertThat(ops.get("items").get(0).has("resourceId")).isFalse();
        assertThat(ops.get("items").get(0).has("actor")).isFalse();
        assertThat(ops.get("items").get(0).has("correlationId")).isFalse();
        assertThat(ops.get("items").get(1).has("error")).isFalse();
        assertThat(ops.get("items").get(1).get("errorSummary").get("category").asText())
                .isEqualTo("INTERNAL");
        // Only the kairo_ meters (no jvm.gc.pause), and meter projection drops description/baseUnit/tags.
        JsonNode idx = MAPPER.readTree(entries.get("metrics/index.json"));
        assertThat(idx.get("meterNames").toString()).contains("kairo_operation_total", "kairo_agent_online")
                .doesNotContain("jvm.gc.pause");
        JsonNode meter = MAPPER.readTree(entries.get("metrics/kairo_operation_total.json"));
        assertThat(meter.has("measurements")).isTrue();
        assertThat(meter.has("description")).isFalse();
        assertThat(meter.has("baseUnit")).isFalse();
        assertThat(meter.has("availableTags")).isFalse();
        // Info is projected to build identity only.
        JsonNode info = MAPPER.readTree(entries.get("actuator/info.json"));
        assertThat(info.get("build").get("version").asText()).isEqualTo("1.7.0-SNAPSHOT");
        assertThat(info.get("build").has("javaTarget")).isTrue();
        assertThat(info.size()).isEqualTo(1);
        // Config fully redacted.
        JsonNode config = MAPPER.readTree(entries.get("config.json"));
        assertThat(config.get("platformUrl").get("value").asText()).isEqualTo("***");
        assertThat(config.get("authToken").get("value").asText()).isEqualTo("***");
    }

    @Test
    void canarySecretIsAbsentFromFilenameEntriesAndContent(@TempDir Path dir) throws Exception {
        String canary = "CANARY-7c9f2a-b4e1";
        // Plant the canary in: token (config + auth header), health detail (source data),
        // operation result + error.message (payload + error text), and the output *directory*.
        writeCreds(canary);
        Path canaryDir = dir.resolve(canary + "-outdir");
        Path zip = canaryDir.resolve("bundle.zip");
        // Re-stub health to embed the canary in an error/detail field.
        server.removeContext("/actuator/health");
        server.createContext("/actuator/health", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            writeJson(exchange, 200, obj(
                    "status", "UP",
                    "components", obj("db", obj("status", "UP", "details", obj(
                            "database", "H2", "error", "connection refused: " + canary)))));
        });
        // Re-stub operations so the canary is planted in the result (payload-like) + error.message (error text).
        server.removeContext("/api/v1/operations");
        server.createContext("/api/v1/operations", exchange -> {
            operationsHits.incrementAndGet();
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            List<Map<String, Object>> items = List.of(obj(
                    "operationId", "op-1", "type", "AUTOMATION_TRIAL", "status", "SUCCEEDED",
                    "createdAt", 1L, "updatedAt", 2L, "completedAt", 3L, "correlationId", "c1",
                    "actor", "alice", "riskLevel", "MEDIUM",
                    "result", obj("scriptBody", canary, "secretPayload", canary),
                    "error", obj("code", "TRIAL_FAILED", "message", canary, "category", "INTERNAL")));
            writeJson(exchange, 200, obj("items", items, "limit", 20));
        });
        int code = cli.run(new String[]{"diagnose", "--output", zip.toString()});
        assertThat(code).isEqualTo(0);

        // The canary was carried as the Authorization Bearer token (auth/header-like value) ...
        assertThat(lastAuth.get()).isEqualTo("Bearer " + canary);
        // ... yet it appears in none of the bundle's output locations.
        assertThat(zip.getFileName().toString()).doesNotContain(canary);
        Map<String, byte[]> entries = readZip(zip);
        for (String name : entries.keySet()) {
            assertThat(name).doesNotContain(canary);
            assertThat(new String(entries.get(name), StandardCharsets.UTF_8)).doesNotContain(canary);
        }
        // Health uses a strict status-only projection: contributor details never enter the archive.
        String health = new String(entries.get("actuator/health.json"), StandardCharsets.UTF_8);
        assertThat(health).contains("\"status\":\"UP\"").doesNotContain("details").doesNotContain(canary);
        // Operation raw result (payload) + error.message (error text) are projected out.
        String ops = new String(entries.get("operations/recent.json"), StandardCharsets.UTF_8);
        assertThat(ops).doesNotContain("scriptBody").doesNotContain("secretPayload")
                .doesNotContain("result").doesNotContain(canary);
        // No forbidden artefacts anywhere.
        String all = combined(entries);
        assertThat(all).doesNotContain(canary)
                .doesNotContain("Bearer ")
                .doesNotContain("password")
                .doesNotContain("script")
                .doesNotContain("secretPayload")
                .doesNotContain("stacktrace")
                .doesNotContain("at com.example");
        assertThat(new String(out.toByteArray(), StandardCharsets.UTF_8)).doesNotContain(canary);
        // No temp file left behind in the output directory.
        try (var stream = Files.list(canaryDir)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith(".kairo-bundle-"))
                    .count()).isZero();
        }
    }

    @Test
    void metricsAllowlistIgnoresMaliciousKairoNames(@TempDir Path dir) throws Exception {
        writeCreds("plain-token");
        String canary = "CANARY-METER-NAME";
        // Return the ten real meter names plus hundreds of malicious / traversal-like / canary kairo_* names.
        List<String> names = new ArrayList<>(TEN_METERS);
        names.add("kairo_agent_online");
        names.add("kairo_agent_online");
        for (int i = 0; i < 300; i++) {
            names.add("kairo_malicious_" + i);
        }
        names.add("kairo_../../etc/passwd");
        names.add("kairo_..%2f..%2fetc");
        names.add("kairo_" + canary);
        names.add("kairo_\\" + canary);
        server.removeContext("/actuator/metrics");
        server.createContext("/actuator/metrics", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/actuator/metrics".equals(path)) {
                writeJson(exchange, 200, obj("names", names));
                return;
            }
            String name = path.substring("/actuator/metrics/".length());
            // Only allowlisted names are ever requested; any other name reaching here is a leak.
            if (TEN_METERS.contains(name)) {
                writeJson(exchange, 200, obj("name", name, "measurements",
                        List.of(obj("statistic", "COUNT", "value", 1)),
                        "availableTags", List.of(obj("tag", "evil", "values", List.of(canary)))));
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        Path zip = dir.resolve("bundle.zip");
        int code = cli.run(new String[]{"diagnose", "--output", zip.toString()});
        assertThat(code).isEqualTo(0);
        Map<String, byte[]> entries = readZip(zip);
        JsonNode idx = MAPPER.readTree(entries.get("metrics/index.json"));
        // Exactly the ten frozen meters; no malicious name and no canary leaked as an entry or value.
        assertThat(idx.get("meterNames").toString()).contains(TEN_METERS.toArray(String[]::new));
        assertThat(idx.get("meterNames").toString()).doesNotContain("malicious").doesNotContain(canary)
                .doesNotContain("..");
        for (String name : entries.keySet()) {
            assertThat(name).doesNotContain(canary).doesNotContain("..").doesNotContain("malicious");
            assertThat(new String(entries.get(name), StandardCharsets.UTF_8)).doesNotContain(canary)
                    .doesNotContain("\"evil\"");
        }
        // No entry was created for a malicious name.
        assertThat(entries.keySet().stream().filter(n -> n.startsWith("metrics/kairo_") && n.endsWith(".json"))
                .count()).isEqualTo(TEN_METERS.size());
    }

    @Test
    void partialSourceFailureIsSanitizedAndBounded(@TempDir Path dir) throws Exception {
        writeCreds("plain-token");
        // /actuator/info returns 500; the error body must NOT be read or bundled.
        server.removeContext("/actuator/info");
        server.createContext("/actuator/info", exchange -> {
            String body = "{\"code\":\"INTERNAL\",\"message\":\"CANARY-IN-ERROR-BODY\",\"category\":\"INTERNAL\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        Path zip = dir.resolve("bundle.zip");
        int code = cli.run(new String[]{"diagnose", "--output", zip.toString()});
        assertThat(code).isEqualTo(0);
        Map<String, byte[]> entries = readZip(zip);
        assertThat(entries.keySet()).doesNotContain("actuator/info.json");
        assertThat(entries.keySet()).contains("actuator/health.json", "operations/recent.json", "config.json");
        // Manifest records a bounded failure status: code is HTTP_<status> (error body never parsed), no body/stack.
        JsonNode manifest = MAPPER.readTree(entries.get("manifest.json"));
        JsonNode infoStatus = findSource(manifest, "actuator-info");
        assertThat(infoStatus.get("status").asText()).isEqualTo("failed");
        assertThat(infoStatus.get("code").asText()).isEqualTo("HTTP_500");
        assertThat(infoStatus.get("httpStatus").asInt()).isEqualTo(500);
        assertThat(infoStatus.has("message")).isFalse();
        assertThat(infoStatus.has("body")).isFalse();
        // The 500 body (canary) was never collected.
        assertThat(combined(entries)).doesNotContain("CANARY-IN-ERROR-BODY");
    }

    @Test
    void oversizedSourceIsBoundedRejected(@TempDir Path dir) throws Exception {
        writeCreds("plain-token");
        // /actuator/info returns a body larger than the 4 MiB source cap, streamed in small chunks so the
        // test does not allocate the full body. The bounded reader must abort and record SOURCE_TOO_LARGE.
        server.removeContext("/actuator/info");
        server.createContext("/actuator/info", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            long total = SupportBundleWriter.DEFAULT_SIZE_BUDGET_BYTES; // 20 MiB >> 4 MiB cap
            exchange.sendResponseHeaders(200, 0);
            writeIncompressibleChunked(exchange, total);
            exchange.getResponseBody().close();
        });
        Path zip = dir.resolve("bundle.zip");
        int code = cli.run(new String[]{"diagnose", "--output", zip.toString()});
        assertThat(code).isEqualTo(0);
        Map<String, byte[]> entries = readZip(zip);
        assertThat(entries.keySet()).doesNotContain("actuator/info.json");
        JsonNode manifest = MAPPER.readTree(entries.get("manifest.json"));
        JsonNode infoStatus = findSource(manifest, "actuator-info");
        assertThat(infoStatus.get("code").asText()).isEqualTo("SOURCE_TOO_LARGE");
        // The bundle is otherwise present and small (the oversized body was never retained).
        assertThat(entries.keySet()).contains("actuator/health.json", "config.json");
        assertThat(Files.size(zip)).isLessThan(1024L * 1024);
    }

    @Test
    void sizeBudgetEnforcedDuringGeneration(@TempDir Path dir) throws Exception {
        writeCreds("plain-token");
        // Make an allowed meter projection large and poorly compressible so its compressed size exceeds
        // the tiny budget. Health details no longer qualify: the strict status-only projection drops them.
        java.util.Random random = new java.util.Random(0x4d3443L);
        List<Map<String, Object>> measurements = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            measurements.add(obj("statistic", "COUNT", "value", random.nextLong()));
        }
        server.removeContext("/actuator/metrics");
        server.createContext("/actuator/metrics", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            if ("/actuator/metrics".equals(exchange.getRequestURI().getPath())) {
                writeJson(exchange, 200, obj("names", List.of("kairo_agent_online")));
            } else {
                writeJson(exchange, 200, obj("name", "kairo_agent_online", "measurements", measurements));
            }
        });
        Path zip = dir.resolve("bundle.zip");
        int code = cli.run(new String[]{"diagnose", "--output", zip.toString(),
                "--max-size-bytes", "4096"});
        assertThat(code).isEqualTo(0);
        // The archive never exceeds the enforced budget.
        assertThat(Files.size(zip)).isLessThanOrEqualTo(4096L);
        Map<String, byte[]> entries = readZip(zip);
        JsonNode manifest = MAPPER.readTree(entries.get("manifest.json"));
        assertThat(manifest.get("truncated").asBoolean()).isTrue();
        assertThat(manifest.get("droppedEntries").toString()).contains("metrics/kairo_agent_online.json");
        assertThat(SupportBundleWriter.DEFAULT_SIZE_BUDGET_BYTES).isEqualTo(20L * 1024 * 1024);
    }

    @Test
    void oversizedManifestFailsSafelyWithNoBundle(@TempDir Path dir) throws Exception {
        writeCreds("plain-token");
        // A budget so tiny that even the manifest alone cannot fit: fail safely, no bundle, no temp.
        Path zip = dir.resolve("bundle.zip");
        int code = cli.run(new String[]{"diagnose", "--output", zip.toString(),
                "--max-size-bytes", "1"});
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
        writeCreds("plain-token");
        // A slow health source: the per-request timeout (== the whole-op timeout) interrupts it.
        server.removeContext("/actuator/health");
        server.createContext("/actuator/health", exchange -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeJson(exchange, 200, obj("status", "UP"));
        });
        Path zip = dir.resolve("bundle.zip");
        long start = System.nanoTime();
        int code = cli.run(new String[]{"diagnose", "--output", zip.toString(), "--timeout-ms", "50"});
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
        writeCreds("plain-token");
        server.removeContext("/actuator/health");
        server.createContext("/actuator/health", exchange -> {
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
        int code = cli.run(new String[]{"diagnose", "--output", zip.toString(), "--timeout-ms", "100"});
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(code).isEqualTo(3);
        assertThat(elapsedMs).isLessThan(1_500L);
        assertThat(Files.exists(zip)).isFalse();
    }

    @Test
    void cumulativeTimeoutAbortsAndSkipsLaterSources(@TempDir Path dir) throws Exception {
        writeCreds("plain-token");
        operationsHits.set(0);
        // Two individually-short sources that together exceed the whole deadline: health succeeds (its
        // per-request timeout is the full deadline), then info's per-request timeout is the *remaining*
        // deadline and aborts mid-source; later sources (operations) are never requested.
        server.removeContext("/actuator/health");
        server.createContext("/actuator/health", exchange -> {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeJson(exchange, 200, obj("status", "UP"));
        });
        server.removeContext("/actuator/info");
        server.createContext("/actuator/info", exchange -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeJson(exchange, 200, obj("build", obj("version", "1.7.0-SNAPSHOT")));
        });
        Path zip = dir.resolve("bundle.zip");
        long start = System.nanoTime();
        int code = cli.run(new String[]{"diagnose", "--output", zip.toString(), "--timeout-ms", "600"});
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(code).isEqualTo(3);
        assertThat(Files.exists(zip)).isFalse();
        // The operations source (collected after info) was never requested.
        assertThat(operationsHits.get()).isZero();
        try (var stream = Files.list(dir)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith(".kairo-bundle-"))
                    .count()).isZero();
        }
        assertThat(elapsedMs).isLessThan(3000L);
    }

    @Test
    void outputBasenameWithSecretIsSanitized(@TempDir Path dir) throws Exception {
        String canary = "CANARY-BASENAME-9f2a";
        writeCreds(canary);
        // The requested ZIP basename itself carries the secret token. The command must never create a
        // file whose name contains the secret; it writes a stable sanitized name in the same directory.
        Path requested = dir.resolve(canary + "-bundle.zip");
        int code = cli.run(new String[]{"diagnose", "--output", requested.toString()});
        assertThat(code).isEqualTo(0);
        // No file with the canary in its name is ever created.
        assertThat(Files.exists(requested)).isFalse();
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> assertThat(p.getFileName().toString()).doesNotContain(canary));
        }
        // A stable sanitized bundle was written in the same directory.
        Path actual = dir.resolve("support-bundle.zip");
        assertThat(Files.exists(actual)).isTrue();
        // The reported output path names the sanitized file (no canary on stdout).
        String stdout = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertThat(stdout).contains("support-bundle.zip").doesNotContain(canary);
    }

    @Test
    void infoProjectsOnlyBuildIdentityAndDropsUnknowns(@TempDir Path dir) throws Exception {
        writeCreds("plain-token");
        String canary = "CANARY-INFO-FIELD";
        server.removeContext("/actuator/info");
        server.createContext("/actuator/info", exchange -> {
            writeJson(exchange, 200, obj(
                    "build", obj("version", "1.7.0-SNAPSHOT", "commit", "abc123",
                            "javaTarget", "21", "time", "2026-01-01T00:00:00Z",
                            "contractBaseline", obj("version", "V1.6.0", "commit", "113823b4"),
                            "unknownNested", obj("leak", canary)),
                    "git", obj("branch", canary),
                    "extra", canary));
        });
        Path zip = dir.resolve("bundle.zip");
        int code = cli.run(new String[]{"diagnose", "--output", zip.toString()});
        assertThat(code).isEqualTo(0);
        Map<String, byte[]> entries = readZip(zip);
        JsonNode info = MAPPER.readTree(entries.get("actuator/info.json"));
        assertThat(info.size()).isEqualTo(1);
        assertThat(info.has("git")).isFalse();
        assertThat(info.has("extra")).isFalse();
        JsonNode build = info.get("build");
        assertThat(build.has("unknownNested")).isFalse();
        assertThat(build.get("version").asText()).isEqualTo("1.7.0-SNAPSHOT");
        assertThat(build.get("javaTarget").asText()).isEqualTo("21");
        assertThat(new String(entries.get("actuator/info.json"), StandardCharsets.UTF_8)).doesNotContain(canary);
    }

    @Test
    void operationsOmitSensitiveAndCardinalityFields(@TempDir Path dir) throws Exception {
        writeCreds("plain-token");
        String canary = "CANARY-OP-FIELD";
        // Plant the canary in every omitted field: operationId, resourceId, actor, correlationId,
        // impact, result, error.message/details/suggestedActions.
        server.removeContext("/api/v1/operations");
        server.createContext("/api/v1/operations", exchange -> {
            operationsHits.incrementAndGet();
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            List<Map<String, Object>> items = List.of(obj(
                    "operationId", "op-" + canary, "type", "RULE_PUBLISH", "status", "SUCCEEDED",
                    "resourceType", "rule", "resourceId", "r-" + canary, "progress", 100,
                    "createdAt", 1L, "updatedAt", 2L, "completedAt", 3L, "correlationId", canary,
                    "actor", canary, "riskLevel", "LOW", "revertOperationId", "rev-" + canary,
                    "impact", obj("affectedTargets", canary, "scope", canary),
                    "result", obj("scriptBody", canary, "payload", canary),
                    "error", obj("code", "RULE_PUBLISH_FAILED", "message", canary, "category", "INTERNAL",
                            "details", obj("trace", canary), "suggestedActions", List.of(canary))));
            writeJson(exchange, 200, obj("items", items, "limit", 20));
        });
        Path zip = dir.resolve("bundle.zip");
        int code = cli.run(new String[]{"diagnose", "--output", zip.toString()});
        assertThat(code).isEqualTo(0);
        String ops = new String(readZip(zip).get("operations/recent.json"), StandardCharsets.UTF_8);
        JsonNode item = MAPPER.readTree(ops).get("items").get(0);
        assertThat(item.has("operationId")).isFalse();
        assertThat(item.has("resourceId")).isFalse();
        assertThat(item.has("actor")).isFalse();
        assertThat(item.has("correlationId")).isFalse();
        assertThat(item.has("revertOperationId")).isFalse();
        assertThat(item.has("impact")).isFalse();
        assertThat(item.has("result")).isFalse();
        assertThat(item.has("error")).isFalse();
        assertThat(item.get("errorSummary").get("category").asText()).isEqualTo("INTERNAL");
        assertThat(item.get("errorSummary").has("message")).isFalse();
        assertThat(item.get("errorSummary").has("details")).isFalse();
        assertThat(ops).doesNotContain(canary);
    }

    @Test
    void operationSummaryEnforcesLocalLimitWhenServerIgnoresQuery(@TempDir Path dir) throws Exception {
        writeCreds("plain-token");
        String canary = "CANARY-AFTER-LOCAL-LIMIT";
        server.removeContext("/api/v1/operations");
        server.createContext("/api/v1/operations", exchange -> {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                items.add(obj("type", i < 20 ? "RULE_PUBLISH" : canary,
                        "status", "SUCCEEDED", "riskLevel", "LOW", "progress", 100,
                        "createdAt", (long) i, "updatedAt", (long) i, "completedAt", (long) i,
                        "result", i < 20 ? null : obj("payload", canary)));
            }
            writeJson(exchange, 200, obj("items", items, "limit", 25));
        });
        Path zip = dir.resolve("bundle.zip");
        assertThat(cli.run(new String[]{"diagnose", "--output", zip.toString()})).isZero();
        JsonNode operations = MAPPER.readTree(readZip(zip).get("operations/recent.json"));
        assertThat(operations.get("count").asInt()).isEqualTo(20);
        assertThat(operations.get("items").size()).isEqualTo(20);
        assertThat(operations.toString()).doesNotContain(canary);
    }

    @Test
    void invalidArgumentsAreRejectedWithFixedMessage(@TempDir Path dir) throws Exception {
        writeCreds("plain-token");
        int code = cli.run(new String[]{"diagnose", "--output", dir.resolve("b.zip").toString(),
                "--timeout-ms", "0"});
        assertThat(code).isEqualTo(64);
        assertThat(new String(err.toByteArray(), StandardCharsets.UTF_8))
                .contains("INVALID_ARGUMENT").doesNotContain("timeout-ms");
        // max-size above the cap is capped (not rejected) and still produces a bundle.
        Path zip = dir.resolve("capped.zip");
        int code2 = cli.run(new String[]{"diagnose", "--output", zip.toString(),
                "--max-size-bytes", String.valueOf(50L * 1024 * 1024)});
        assertThat(code2).isEqualTo(0);
        assertThat(Files.exists(zip)).isTrue();
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

    /** Deterministic pseudo-random printable string that DEFLATE cannot compress. */
    private static String randomishString(int len) {
        char[] c = new char[len];
        int state = 0x9e3779b9;
        for (int i = 0; i < len; i++) {
            state = state * 1103515245 + 12345;
            c[i] = (char) (33 + ((state >>> 24) & 0x3F));
        }
        return new String(c);
    }
}
