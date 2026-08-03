package com.example.kairo.ops.bundle;

import com.example.kairo.api.build.KairoBuildVersion;
import com.example.kairo.api.support.BoundedReads;
import com.example.kairo.api.support.SupportBundleWriter;
import com.example.kairo.api.support.SupportBundleWriter.BundleBudgetExceededException;
import com.example.kairo.api.support.SupportBundleWriter.BundleTimeoutException;
import com.example.kairo.ops.OpsOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * V1.7 M4-C &sect;11.3 {@code kairo-ops support-bundle} command. Collects only local, read-only Agent
 * diagnostic data through the existing loopback HTTP boundary ({@code /v1/health}, {@code /v1/status},
 * {@code /v1/events}, {@code /v1/metrics}) &mdash; bounded, schema-projected Agent status, recent events,
 * metrics and the shared build identity/version where available &mdash; then writes a single
 * safe-by-construction ZIP via {@link SupportBundleWriter}. No remote mutation, no new daemon/service.
 *
 * <p>Safety contract (V1.7 M4-C &sect;11.3):
 * <ul>
 *   <li><b>Bounded reads</b> &mdash; every source body is streamed ({@link HttpResponse.BodyHandlers#ofInputStream})
 *       and read through {@link BoundedReads}, retaining at most {@link #MAX_SOURCE_BYTES} + 1; oversized
 *       sources are recorded as {@code SOURCE_TOO_LARGE} and error bodies are never read or bundled.</li>
 *   <li><b>Schema projection</b> &mdash; each source is parsed and projected to a fixed, bounded schema
 *       (health={status,protocolVersion}; status=protocolVersion + bounded JVM identity + operational
 *       state/count fields; metrics=fixed RuntimeMetrics fields; events=&le;100 of {timestamp,type}).
 *       Raw bodies are never stored; unknown fields, actor, ruleId, target, message, applicationName and
 *       host are omitted because messages can carry script output and exception text. Malformed or
 *       wrong-shape JSON is recorded as a sanitized {@code SOURCE_MALFORMED} failure.</li>
 *   <li><b>Whole-operation timeout</b> &mdash; one monotonic deadline; each request uses the remaining
 *       duration and a per-request timeout aborts the whole command (no bundle) on expiry.</li>
 *   <li><b>Secret scrubbing</b> &mdash; the agent token and URL are scrubbed from all bytes; the output
 *       basename is sanitised so a secret in the requested filename never lands on disk.</li>
 *   <li><b>Fixed errors</b> &mdash; the CLI prints fixed code + message only, never raw exception text.</li>
 * </ul>
 */
public final class OpsSupportBundle {

    /** Per-source response cap collected before the writer's budget applies. */
    static final int MAX_SOURCE_BYTES = 4 * 1024 * 1024;

    /** At most this many recent events are kept (bounded). */
    static final int EVENT_LIMIT = 100;

    /** Stable basename used when the requested output basename carries a registered secret. */
    static final String SAFE_BASENAME = "support-bundle.zip";

    private static final int SCALAR_MAX = 256;
    private static final String TOOL_VERSION = KairoBuildVersion.resolve();

    private final OpsOptions options;
    private final PrintStream out;
    private final PrintStream err;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public OpsSupportBundle(OpsOptions options, PrintStream out, PrintStream err) {
        this.options = options;
        this.out = out;
        this.err = err;
    }

    public int run() {
        long deadline = SupportBundleWriter.deadlineNanos(options.timeoutMs());
        Set<String> secrets = secrets();
        Path safeOutput = sanitizeOutputPath(Path.of(options.output()), secrets);
        List<SourceStatus> sources = new ArrayList<>();
        List<SupportBundleWriter.Entry> entries = new ArrayList<>();

        try {
            // Each loopback source is read-only; order is deterministic.
            collect(entries, sources, "agent-health", "/v1/health", this::projectHealth, deadline);
            collect(entries, sources, "agent-status", "/v1/status", this::projectStatus, deadline);
            collect(entries, sources, "agent-events", "/v1/events", this::projectEvents, deadline);
            collect(entries, sources, "agent-metrics", "/v1/metrics", this::projectMetrics, deadline);
            entries.add(configEntry());

            SupportBundleWriter.checkDeadline(deadline);
            byte[] zip = SupportBundleWriter.buildBundle(entries, manifestSupplier(sources), secrets,
                    options.maxSizeBytes(), deadline);
            SupportBundleWriter.writeAtomically(safeOutput, zip, deadline);
            printOk(safeOutput, zip.length, entries.size(), secrets);
            return 0;
        } catch (BundleTimeoutException e) {
            printError("BUNDLE_TIMEOUT", "support-bundle timed out; no bundle written");
            return 3;
        } catch (BundleBudgetExceededException e) {
            printError("BUNDLE_TOO_LARGE", "support bundle exceeds size budget; no bundle written");
            return 4;
        } catch (Exception e) {
            printError("BUNDLE_WRITE_FAILED", "failed to write support bundle");
            return 4;
        }
    }

    private Set<String> secrets() {
        Set<String> secrets = new TreeSet<>();
        if (options.token() != null && !options.token().isBlank()) {
            secrets.add(options.token());
        }
        String url = options.baseUrl().toString();
        if (!url.isBlank()) {
            secrets.add(url);
        }
        return secrets;
    }

    /** Sanitise the output basename if it carries a registered secret (same contract as the CLI). */
    static Path sanitizeOutputPath(Path output, Set<String> secrets) {
        Path normalized = output.toAbsolutePath().normalize();
        String basename = normalized.getFileName() == null ? "" : normalized.getFileName().toString();
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty() && basename.contains(secret)) {
                Path parent = normalized.getParent();
                return (parent == null ? Path.of(".") : parent).resolve(SAFE_BASENAME);
            }
        }
        return normalized;
    }

    @FunctionalInterface
    private interface Projection {
        byte[] apply(JsonNode node) throws IOException;
    }

    private void collect(List<SupportBundleWriter.Entry> entries, List<SourceStatus> sources,
                         String sourceName, String path, Projection projection, long deadline) {
        SupportBundleWriter.checkDeadline(deadline);
        long remaining = SupportBundleWriter.remainingMillis(deadline);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(options.baseUrl().resolve(path))
                    .timeout(Duration.ofMillis(Math.max(1L, remaining)))
                    .header("Accept", "application/json")
                    .header("X-Actor", "kairo-ops")
                    .GET();
            if (options.token() != null && !options.token().isBlank()) {
                builder.header("X-Agent-Token", options.token());
            }
            HttpResponse<InputStream> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                // Error bodies must not be read or bundled: discard and close without materialising.
                try (InputStream body = response.body()) {
                    // intentionally not read
                }
                sources.add(SourceStatus.failed(sourceName, "HTTP_" + status, status));
                return;
            }
            try (InputStream body = response.body()) {
                BoundedReads.ReadResult read = BoundedReads.readAtMost(body, MAX_SOURCE_BYTES,
                        Duration.ofMillis(Math.max(1L, SupportBundleWriter.remainingMillis(deadline))));
                if (read.isTooLarge()) {
                    sources.add(SourceStatus.failed(sourceName, "SOURCE_TOO_LARGE", status));
                    return;
                }
                JsonNode node = mapper.readTree(read.bytes());
                byte[] projected = projection.apply(node);
                entries.add(new SupportBundleWriter.Entry(entryName(sourceName), projected));
                sources.add(SourceStatus.ok(sourceName, status));
            }
        } catch (HttpTimeoutException | BoundedReads.ReadTimeoutException e) {
            // Per-request timeout tracks the whole-operation deadline: abort, leave no bundle.
            throw new BundleTimeoutException("deadline expired during " + sourceName);
        } catch (SourceMalformedException | JsonProcessingException e) {
            sources.add(SourceStatus.failed(sourceName, "SOURCE_MALFORMED", 0));
        } catch (Exception e) {
            sources.add(SourceStatus.failed(sourceName, "SOURCE_FAILED", 0));
        }
    }

    /** health: only status and protocolVersion. */
    private byte[] projectHealth(JsonNode node) throws IOException {
        if (node == null || !node.isObject()) {
            throw new SourceMalformedException("health is not an object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", boundedScalar(text(node, "status")));
        out.put("protocolVersion", boundedScalar(text(node, "protocolVersion")));
        return jsonBytes(out);
    }

    /** status: protocolVersion + bounded JVM/build identity + operational state/count fields.
     *  Omits applicationName, host, raw messages and arbitrary extra fields. */
    private byte[] projectStatus(JsonNode node) throws IOException {
        if (node == null || !node.isObject()) {
            throw new SourceMalformedException("status is not an object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", boundedScalar(text(node, "protocolVersion")));
        Map<String, Object> jvm = new LinkedHashMap<>();
        JsonNode j = node.path("jvm");
        if (!j.isObject()) {
            throw new SourceMalformedException("status.jvm is not an object");
        }
        jvm.put("agentVersion", boundedScalar(text(j, "agentVersion")));
        jvm.put("javaVersion", boundedScalar(text(j, "javaVersion")));
        jvm.put("loadMode", boundedScalar(text(j, "loadMode")));
        jvm.put("status", boundedScalar(text(j, "status")));
        jvm.put("pid", requiredLong(j, "pid"));
        jvm.put("startTimeMillis", requiredLong(j, "startTimeMillis"));
        jvm.put("enhancedClassCount", requiredInt(j, "enhancedClassCount"));
        jvm.put("enhancedMethodCount", requiredInt(j, "enhancedMethodCount"));
        jvm.put("activeRuleCount", requiredInt(j, "activeRuleCount"));
        // Deliberately omitted: applicationName, host, and arbitrary fields.
        out.put("jvm", jvm);
        Map<String, Object> metrics = new LinkedHashMap<>();
        JsonNode mn = node.path("metrics");
        if (!mn.isObject()) {
            throw new SourceMalformedException("status.metrics is not an object");
        }
        putRuntimeMetrics(metrics, mn);
        out.put("metrics", metrics);
        return jsonBytes(out);
    }

    /** metrics: only the fixed numeric/boolean RuntimeMetrics fields. */
    private byte[] projectMetrics(JsonNode node) throws IOException {
        if (node == null || !node.isObject()) {
            throw new SourceMalformedException("metrics is not an object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        putRuntimeMetrics(out, node);
        return jsonBytes(out);
    }

    private void putRuntimeMetrics(Map<String, Object> out, JsonNode n) {
        out.put("loadedClassCount", requiredInt(n, "loadedClassCount"));
        out.put("enhancedClassCount", requiredInt(n, "enhancedClassCount"));
        out.put("enhancedMethodCount", requiredInt(n, "enhancedMethodCount"));
        out.put("totalRuleCount", requiredInt(n, "totalRuleCount"));
        out.put("activeRuleCount", requiredInt(n, "activeRuleCount"));
        out.put("totalHits", requiredLong(n, "totalHits"));
        out.put("totalErrors", requiredLong(n, "totalErrors"));
        JsonNode enabled = n.path("globallyEnabled");
        if (!enabled.isBoolean()) {
            throw new SourceMalformedException("globallyEnabled is not boolean");
        }
        out.put("globallyEnabled", enabled.booleanValue());
    }

    /** events: at most {@link #EVENT_LIMIT}, only timestamp and a bounded/sanitized event type.
     *  Omits actor, ruleId, target, message and all unknown fields. */
    private byte[] projectEvents(JsonNode node) throws IOException {
        if (node == null || !node.isArray()) {
            throw new SourceMalformedException("events is not an array");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int start = Math.max(0, node.size() - EVENT_LIMIT);
        for (int i = start; i < node.size(); i++) {
            JsonNode ev = node.get(i);
            if (!ev.isObject()) {
                throw new SourceMalformedException("event is not an object");
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("timestamp", requiredLong(ev, "timestamp"));
            e.put("type", boundedScalar(text(ev, "type"), 128));
            out.add(e);
        }
        return jsonBytes(out);
    }

    private SupportBundleWriter.Entry configEntry() {
        Map<String, String> urlEntry = new LinkedHashMap<>();
        urlEntry.put("source", "kairo-ops");
        urlEntry.put("value", "***");
        Map<String, String> tokenEntry = new LinkedHashMap<>();
        tokenEntry.put("source", "kairo-ops");
        tokenEntry.put("value", "***");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("agentUrl", urlEntry);
        config.put("authToken", tokenEntry);
        config.put("source", "kairo-ops");
        config.put("note", "values redacted; no environment values collected");
        return new SupportBundleWriter.Entry("config.json", toJsonBytes(config));
    }

    private SupportBundleWriter.ManifestSupplier manifestSupplier(List<SourceStatus> sources) {
        return (kept, dropped, truncated) -> {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("bundleType", "agent-support");
            manifest.put("generatedAt", Instant.now().toString());
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", "kairo-ops");
            tool.put("version", TOOL_VERSION);
            manifest.put("tool", tool);
            manifest.put("budgetBytes", options.maxSizeBytes());
            manifest.put("timeoutMillis", options.timeoutMs());
            manifest.put("truncated", truncated);
            manifest.put("sizeBudgetExceeded", !dropped.isEmpty());
            manifest.put("entries", kept);
            manifest.put("droppedEntries", dropped);
            manifest.put("sources", sources.stream().map(SourceStatus::toJson).toList());
            return toJsonBytes(manifest);
        };
    }

    private byte[] jsonBytes(Object value) throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }

    private static byte[] toJsonBytes(Object value) {
        return toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    private static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        appendJson(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            appendQuoted(sb, s);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendQuoted(sb, String.valueOf(e.getKey()));
                sb.append(':');
                appendJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendJson(sb, item);
            }
            sb.append(']');
        } else {
            appendQuoted(sb, String.valueOf(value));
        }
    }

    private static void appendQuoted(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (!v.isIntegralNumber() || !v.canConvertToLong()) {
            throw new SourceMalformedException(field + " is not an integer");
        }
        return v.longValue();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (!v.isIntegralNumber() || !v.canConvertToInt()) {
            throw new SourceMalformedException(field + " is not an integer");
        }
        return v.intValue();
    }

    private static String boundedScalar(String value) {
        return boundedScalar(value, SCALAR_MAX);
    }

    private static String boundedScalar(String value, int max) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length() && sb.length() < max; i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c != 0x7f) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String entryName(String sourceName) {
        return sourceName + ".json";
    }

    private void printOk(Path output, int bytes, int entryCount, Set<String> secrets) {
        out.println("{\"ok\":true,\"output\":\"" + escape(scrubDisplay(output.toString(), secrets))
                + "\",\"bytes\":" + bytes + ",\"entries\":" + (entryCount + 1) + "}");
    }

    private static String scrubDisplay(String value, Set<String> secrets) {
        String scrubbed = value;
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) {
                scrubbed = scrubbed.replace(secret, "***");
            }
        }
        return scrubbed;
    }

    private void printError(String code, String message) {
        err.println("{\"code\":\"" + code + "\",\"message\":\"" + escape(message) + "\"}");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Raised by a projection when a source is malformed or wrong-shape; recorded as SOURCE_MALFORMED. */
    private static final class SourceMalformedException extends RuntimeException {
        SourceMalformedException(String message) {
            super(message);
        }
    }

    /** Bounded per-source status: name + status + code (+ httpStatus); no body, no URL, no stack. */
    private static final class SourceStatus {
        private final String name;
        private final boolean ok;
        private final String code;
        private final int httpStatus;

        static SourceStatus ok(String name, int httpStatus) {
            return new SourceStatus(name, true, "OK", httpStatus);
        }

        static SourceStatus failed(String name, String code, int httpStatus) {
            return new SourceStatus(name, false, code, httpStatus);
        }

        private SourceStatus(String name, boolean ok, String code, int httpStatus) {
            this.name = name;
            this.ok = ok;
            this.code = code;
            this.httpStatus = httpStatus;
        }

        Map<String, Object> toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("status", ok ? "ok" : "failed");
            out.put("code", code);
            if (httpStatus > 0) {
                out.put("httpStatus", httpStatus);
            }
            return out;
        }
    }
}
