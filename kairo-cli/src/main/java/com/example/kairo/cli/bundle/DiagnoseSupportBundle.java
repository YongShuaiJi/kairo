package com.example.kairo.cli.bundle;

import com.example.kairo.api.error.ErrorCategory;
import com.example.kairo.api.operation.OperationStatus;
import com.example.kairo.api.operation.OperationType;
import com.example.kairo.api.support.SupportBundleWriter;
import com.example.kairo.api.support.SupportBundleWriter.BundleBudgetExceededException;
import com.example.kairo.api.support.SupportBundleWriter.BundleTimeoutException;
import com.example.kairo.api.write.RiskLevel;
import com.example.kairo.sdk.KairoClient;
import com.example.kairo.sdk.KairoClient.BoundedJsonRead;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * V1.7 M4-C &sect;11.3 {@code kairo-cli diagnose} support bundle. Collects only bounded, read-only
 * Platform diagnostic data through the official {@link KairoClient} SDK boundary &mdash; actuator
 * health (sanitised), actuator info / build identity, the frozen {@code kairo_*} meter readings, a
 * bounded recent-operation summary, and a fully redacted connection-config snapshot &mdash; then writes
 * a single safe-by-construction ZIP via {@link SupportBundleWriter}.
 *
 * <p>Safety contract (V1.7 M4-C &sect;11.3):
 * <ul>
 *   <li><b>Bounded reads</b> &mdash; every source is fetched via {@link KairoClient#getJsonBounded},
 *       which streams the body and retains at most {@link #MAX_SOURCE_BYTES} + 1; oversized sources are
 *       recorded as {@code SOURCE_TOO_LARGE} and error bodies are never read or bundled.</li>
 *   <li><b>Exact metric allowlist</b> &mdash; only the ten frozen V1.7 M4-B meter names are collected;
 *       every other name (including malicious {@code kairo_*} values) is ignored, and a remote name is
 *       never used in a ZIP entry name until exact allowlist membership is proven.</li>
 *   <li><b>Strict projection</b> &mdash; meter responses keep only the name and bounded numeric
 *       measurements (no {@code availableTags}); {@code /actuator/info} keeps only the M4-A build-identity
 *       fields; health is status-only; the operation summary omits identifiers, actor, correlation id,
 *       result, impact and error text, keeping only finite type/status/risk/category values, progress and
 *       timestamps.</li>
 *   <li><b>Whole-operation timeout</b> &mdash; one monotonic deadline; each request uses the remaining
 *       duration and a per-request timeout aborts the whole command (no bundle) on expiry.</li>
 *   <li><b>Secret scrubbing</b> &mdash; the caller's token and platform URL are scrubbed from all bytes;
 *       the output basename is sanitised so a secret in the requested filename never lands on disk.</li>
 *   <li><b>Fixed errors</b> &mdash; the CLI prints fixed code + message only, never raw exception text.</li>
 * </ul>
 *
 * <p>Never collected: script source/body, Authorization/tokens, environment values, JDBC/Redis
 * credential-bearing URLs, raw class bytes, heap dumps, arbitrary log files, command payload/result
 * bodies, or unbounded stack traces.
 */
public final class DiagnoseSupportBundle {

    /** Cap on recent operations in the summary (bounded). */
    static final int OPERATION_LIMIT = 20;

    /** Per-entry source-response cap collected before the writer's budget applies. */
    static final int MAX_SOURCE_BYTES = 4 * 1024 * 1024;

    /** The exact ten frozen V1.7 M4-B (&sect;11.2) meter names; no other {@code kairo_*} name is collected. */
    static final Set<String> ALLOWED_METERS = Set.of(
            "kairo_agent_online",
            "kairo_agent_command_backlog",
            "kairo_agent_command_total",
            "kairo_operation_total",
            "kairo_operation_duration_seconds",
            "kairo_runtime_rule_targets",
            "kairo_reconcile_total",
            "kairo_rollback_total",
            "kairo_ttl_cleanup_total",
            "kairo_platform_build_info");

    /** Bounded Micrometer {@code statistic} vocabulary; any other value is dropped. */
    private static final Set<String> STATISTICS = Set.of(
            "COUNT", "TOTAL", "MAX", "NONE", "ACTIVE_TASKS", "DURATION", "TOTAL_TIME");

    private static final Set<String> HEALTH_STATUSES = Set.of("UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN");

    /** Only contributor identities maintained by this service; unknown remote keys are omitted. */
    private static final List<String> HEALTH_COMPONENTS = List.of(
            "db", "flyway", "redis", "readinessState", "livenessState", "diskSpace", "ping", "ssl");

    /** Stable basename used when the requested output basename carries a registered secret. */
    static final String SAFE_BASENAME = "support-bundle.zip";

    private static final int SCALAR_MAX = 256;

    private final KairoClient client;
    private final ObjectMapper mapper;
    private final PrintStream out;
    private final PrintStream err;
    private final Path output;
    private final long timeoutMillis;
    private final long maxBytes;

    public DiagnoseSupportBundle(KairoClient client, ObjectMapper mapper, PrintStream out, PrintStream err,
                                 Path output, long timeoutMillis, long maxBytes) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("max-size must be positive");
        }
        this.client = client;
        this.mapper = mapper;
        this.out = out;
        this.err = err;
        this.output = output;
        this.timeoutMillis = timeoutMillis;
        this.maxBytes = Math.min(maxBytes, SupportBundleWriter.DEFAULT_SIZE_BUDGET_BYTES);
    }

    /** Run the bundle. Returns 0 on success (partial sources are recorded in the manifest, still 0). */
    public int run() {
        long deadline = SupportBundleWriter.deadlineNanos(timeoutMillis);
        Set<String> secrets = secrets();
        Path safeOutput = sanitizeOutputPath(output, secrets);
        List<SourceStatus> sources = new ArrayList<>();
        List<SupportBundleWriter.Entry> entries = new ArrayList<>();

        try {
            // --- actuator/health (strict status projection; bounded read) ---
            collectActuator(entries, sources, "actuator-health", "/actuator/health",
                    json -> mapper.writeValueAsBytes(projectHealth(json)), deadline);

            // --- actuator/info (build identity only; bounded read) ---
            collectActuator(entries, sources, "actuator-info", "/actuator/info",
                    json -> mapper.writeValueAsBytes(projectInfo(json)), deadline);

            // --- metrics: only the frozen kairo_* meters, with their readings ---
            collectMetrics(entries, sources, deadline);

            // --- bounded recent-operation summary (no identifiers/result/error text) ---
            collectOperations(entries, sources, deadline);

            // --- redacted connection config (keys + source type + "***"; no env values) ---
            entries.add(configEntry());

            SupportBundleWriter.checkDeadline(deadline);
            byte[] zip = SupportBundleWriter.buildBundle(entries, manifestSupplier(sources), secrets,
                    maxBytes, deadline);
            SupportBundleWriter.writeAtomically(safeOutput, zip, deadline);
            printOk(safeOutput, zip.length, entries.size(), secrets);
            return 0;
        } catch (BundleTimeoutException e) {
            printError("BUNDLE_TIMEOUT", "diagnose timed out; no bundle written");
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
        addIfPresent(secrets, client.config().token());
        addIfPresent(secrets, client.config().baseUrl());
        return secrets;
    }

    private static void addIfPresent(Set<String> secrets, String value) {
        if (value != null && !value.isBlank()) {
            secrets.add(value);
        }
    }

    /**
     * Sanitise the output path: if the requested basename contains a registered secret, choose a stable
     * sanitized basename in the same directory. The output directory is left as the caller requested;
     * only the basename is scrubbed. The actual output path is returned so the success message reports it.
     */
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
        byte[] apply(Map<String, Object> json) throws IOException;
    }

    private void collectActuator(List<SupportBundleWriter.Entry> entries, List<SourceStatus> sources,
                                 String sourceName, String path, Projection projection, long deadline) {
        SupportBundleWriter.checkDeadline(deadline);
        Duration requestTimeout = Duration.ofMillis(Math.max(1L, SupportBundleWriter.remainingMillis(deadline)));
        try {
            BoundedJsonRead read = client.getJsonBounded(path, MAX_SOURCE_BYTES, requestTimeout);
            switch (read.outcome()) {
                case TOO_LARGE -> {
                    sources.add(SourceStatus.failed(sourceName, "SOURCE_TOO_LARGE", 0));
                    return;
                }
                case HTTP_ERROR -> {
                    sources.add(SourceStatus.failed(sourceName, "HTTP_" + read.httpStatus(), read.httpStatus()));
                    return;
                }
                case OK -> {
                    Map<String, Object> json = read.json();
                    if (json == null) {
                        sources.add(SourceStatus.failed(sourceName, "SOURCE_EMPTY", 0));
                        return;
                    }
                    byte[] body = projection.apply(json);
                    entries.add(new SupportBundleWriter.Entry(entryName(sourceName), body));
                    sources.add(SourceStatus.ok(sourceName, Map.of()));
                }
            }
        } catch (com.example.kairo.sdk.KairoClient.BoundedReadTimeoutException e) {
            throw new BundleTimeoutException("deadline expired during " + sourceName);
        } catch (Exception e) {
            sources.add(SourceStatus.failed(sourceName, "SOURCE_FAILED", 0));
        }
    }

    private void collectMetrics(List<SupportBundleWriter.Entry> entries, List<SourceStatus> sources,
                                long deadline) {
        SupportBundleWriter.checkDeadline(deadline);
        BoundedJsonRead read;
        try {
            Duration requestTimeout = Duration.ofMillis(Math.max(1L, SupportBundleWriter.remainingMillis(deadline)));
            read = client.getJsonBounded("/actuator/metrics", MAX_SOURCE_BYTES, requestTimeout);
        } catch (com.example.kairo.sdk.KairoClient.BoundedReadTimeoutException e) {
            throw new BundleTimeoutException("deadline expired during metrics");
        } catch (Exception e) {
            sources.add(SourceStatus.failed("metrics", "SOURCE_FAILED", 0));
            return;
        }
        if (read.outcome() == BoundedJsonRead.Outcome.TOO_LARGE) {
            sources.add(SourceStatus.failed("metrics", "SOURCE_TOO_LARGE", 0));
            return;
        }
        if (read.outcome() == BoundedJsonRead.Outcome.HTTP_ERROR) {
            sources.add(SourceStatus.failed("metrics", "HTTP_" + read.httpStatus(), read.httpStatus()));
            return;
        }
        Map<String, Object> root = read.json();
        if (root == null) {
            sources.add(SourceStatus.failed("metrics", "SOURCE_EMPTY", 0));
            return;
        }
        Object namesObj = root.get("names");
        if (!(namesObj instanceof List<?> names)) {
            sources.add(SourceStatus.failed("metrics", "SOURCE_MALFORMED", 0));
            return;
        }
        // Exact allowlist membership only; ignore every other name (incl. malicious kairo_*).
        List<String> allowed = names.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(ALLOWED_METERS::contains)
                .distinct()
                .sorted()
                .toList();
        List<String> collected = new ArrayList<>();
        for (String name : allowed) {
            SupportBundleWriter.checkDeadline(deadline);
            try {
                Duration requestTimeout = Duration.ofMillis(Math.max(1L, SupportBundleWriter.remainingMillis(deadline)));
                BoundedJsonRead meter = client.getJsonBounded("/actuator/metrics/" + name,
                        MAX_SOURCE_BYTES, requestTimeout);
                if (meter.outcome() == BoundedJsonRead.Outcome.TOO_LARGE) {
                    sources.add(SourceStatus.failed("metrics:" + name, "SOURCE_TOO_LARGE", 0));
                    continue;
                }
                if (meter.outcome() == BoundedJsonRead.Outcome.HTTP_ERROR) {
                    sources.add(SourceStatus.failed("metrics:" + name, "HTTP_" + meter.httpStatus(),
                            meter.httpStatus()));
                    continue;
                }
                Map<String, Object> meterJson = meter.json();
                if (meterJson == null) {
                    sources.add(SourceStatus.failed("metrics:" + name, "SOURCE_EMPTY", 0));
                    continue;
                }
                byte[] body = mapper.writeValueAsBytes(projectMeter(name, meterJson));
                // `name` is proven to be one of the ten frozen meter names, so it is safe as an entry name.
                entries.add(new SupportBundleWriter.Entry("metrics/" + name + ".json", body));
                collected.add(name);
            } catch (com.example.kairo.sdk.KairoClient.BoundedReadTimeoutException e) {
                throw new BundleTimeoutException("deadline expired during metrics:" + name);
            } catch (Exception e) {
                sources.add(SourceStatus.failed("metrics:" + name, "SOURCE_MALFORMED", 0));
            }
        }
        entries.add(new SupportBundleWriter.Entry("metrics/index.json", meterIndex(collected)));
        sources.add(SourceStatus.ok("metrics", Map.of("meterCount", collected.size())));
    }

    private void collectOperations(List<SupportBundleWriter.Entry> entries, List<SourceStatus> sources,
                                   long deadline) {
        SupportBundleWriter.checkDeadline(deadline);
        BoundedJsonRead read;
        try {
            Duration requestTimeout = Duration.ofMillis(Math.max(1L, SupportBundleWriter.remainingMillis(deadline)));
            read = client.getJsonBounded("/api/v1/operations?limit=" + OPERATION_LIMIT,
                    MAX_SOURCE_BYTES, requestTimeout);
        } catch (com.example.kairo.sdk.KairoClient.BoundedReadTimeoutException e) {
            throw new BundleTimeoutException("deadline expired during operations");
        } catch (Exception e) {
            sources.add(SourceStatus.failed("operations", "SOURCE_FAILED", 0));
            return;
        }
        if (read.outcome() == BoundedJsonRead.Outcome.TOO_LARGE) {
            sources.add(SourceStatus.failed("operations", "SOURCE_TOO_LARGE", 0));
            return;
        }
        if (read.outcome() == BoundedJsonRead.Outcome.HTTP_ERROR) {
            sources.add(SourceStatus.failed("operations", "HTTP_" + read.httpStatus(), read.httpStatus()));
            return;
        }
        try {
            Map<String, Object> page = read.json();
            if (page == null) {
                sources.add(SourceStatus.failed("operations", "SOURCE_EMPTY", 0));
                return;
            }
            Object itemsObj = page.get("items");
            if (!(itemsObj instanceof List<?> items)) {
                sources.add(SourceStatus.failed("operations", "SOURCE_MALFORMED", 0));
                return;
            }
            List<Map<String, Object>> summary = new ArrayList<>();
            for (Object item : items) {
                if (summary.size() >= OPERATION_LIMIT) {
                    break;
                }
                if (item instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) m;
                    summary.add(projectOperationItem(map));
                }
            }
            byte[] body = mapper.writeValueAsBytes(
                    Map.of("limit", OPERATION_LIMIT, "count", summary.size(), "items", summary));
            entries.add(new SupportBundleWriter.Entry("operations/recent.json", body));
            sources.add(SourceStatus.ok("operations", Map.of("operationCount", summary.size())));
        } catch (Exception e) {
            sources.add(SourceStatus.failed("operations", "SOURCE_MALFORMED", 0));
        }
    }

    private SupportBundleWriter.Entry configEntry() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("platformUrl", redacted("kairo-cli"));
        config.put("authToken", redacted("kairo-cli"));
        config.put("source", "kairo-cli");
        config.put("note", "values redacted; no environment values collected");
        try {
            return new SupportBundleWriter.Entry("config.json",
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(config));
        } catch (IOException e) {
            return new SupportBundleWriter.Entry("config.json", new byte[0]);
        }
    }

    private static Map<String, String> redacted(String source) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("source", source);
        entry.put("value", "***");
        return entry;
    }

    /** Health data is status-only: no contributor details or remote-defined keys can enter the bundle. */
    private Map<String, Object> projectHealth(Map<String, Object> health) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", fixedValue(health.get("status"), HEALTH_STATUSES));
        Map<String, Object> components = asMap(health.get("components"));
        Map<String, Object> projected = new LinkedHashMap<>();
        for (String component : HEALTH_COMPONENTS) {
            Map<String, Object> value = asMap(components.get(component));
            if (!value.isEmpty()) {
                projected.put(component, Map.of("status", fixedValue(value.get("status"), HEALTH_STATUSES)));
            }
        }
        if (!projected.isEmpty()) {
            out.put("components", projected);
        }
        return out;
    }

    /** Project /actuator/info to the M4-A build-identity fields only; bounded scalar lengths, no unknowns. */
    private Map<String, Object> projectInfo(Map<String, Object> info) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> build = asMap(info.get("build"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("version", boundedScalar(stringField(build, "version")));
        b.put("commit", boundedScalar(stringField(build, "commit")));
        b.put("time", boundedScalar(stringField(build, "time")));
        b.put("javaTarget", boundedScalar(stringField(build, "javaTarget"), 16));
        Map<String, Object> cb = asMap(build.get("contractBaseline"));
        Map<String, Object> cbOut = new LinkedHashMap<>();
        cbOut.put("version", boundedScalar(stringField(cb, "version"), 32));
        cbOut.put("commit", boundedScalar(stringField(cb, "commit")));
        b.put("contractBaseline", cbOut);
        out.put("build", b);
        return out;
    }

    /** Project a meter to {name, measurements:[{statistic,value}]} with bounded statistic vocabulary. */
    private Map<String, Object> projectMeter(String name, Map<String, Object> meter) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        List<Object> measurements = new ArrayList<>();
        Object raw = meter.get("measurements");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String statistic = stringField(m, "statistic");
                if (!STATISTICS.contains(statistic)) {
                    continue;
                }
                Object value = m.get("value");
                if (!(value instanceof Number)) {
                    continue;
                }
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("statistic", statistic);
                mm.put("value", value);
                measurements.add(mm);
            }
        }
        out.put("measurements", measurements);
        return out;
    }

    /** Project an operation item to a recent operational summary; all strings use finite vocabularies. */
    private Map<String, Object> projectOperationItem(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", enumValue(item.get("type"), OperationType.class));
        out.put("status", enumValue(item.get("status"), OperationStatus.class));
        out.put("riskLevel", enumValue(item.get("riskLevel"), RiskLevel.class));
        out.put("progress", asInt(item.get("progress")));
        out.put("createdAt", asLong(item.get("createdAt")));
        out.put("updatedAt", asLong(item.get("updatedAt")));
        out.put("completedAt", asLong(item.get("completedAt")));
        Object error = item.get("error");
        if (error instanceof Map<?, ?> em) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("category", enumValue(em.get("category"), ErrorCategory.class));
            out.put("errorSummary", err);
        }
        // Deliberately omitted: operationId, resourceId, actor, correlationId, revertOperationId,
        // result (raw payload), impact, error.message/details/suggestedActions, and all unknown fields.
        return out;
    }

    private static String fixedValue(Object value, Set<String> allowed) {
        String text = value == null ? null : value.toString();
        return allowed.contains(text) ? text : "UNKNOWN";
    }

    private static <E extends Enum<E>> String enumValue(Object value, Class<E> enumType) {
        String text = value == null ? null : value.toString();
        if (text != null) {
            for (E constant : enumType.getEnumConstants()) {
                if (constant.name().equals(text)) {
                    return text;
                }
            }
        }
        return "UNKNOWN";
    }

    private byte[] meterIndex(List<String> names) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("meterNames", names));
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String entryName(String sourceName) {
        return sourceName.replace("actuator-", "actuator/") + ".json";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    private static String stringField(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
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

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private SupportBundleWriter.ManifestSupplier manifestSupplier(List<SourceStatus> sources) {
        return (kept, dropped, truncated) -> {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("bundleType", "platform-diagnose");
            manifest.put("generatedAt", java.time.Instant.now().toString());
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", "kairo-cli");
            tool.put("version", toolVersion());
            manifest.put("tool", tool);
            manifest.put("budgetBytes", maxBytes);
            manifest.put("timeoutMillis", timeoutMillis);
            manifest.put("truncated", truncated);
            manifest.put("sizeBudgetExceeded", !dropped.isEmpty());
            manifest.put("entries", kept);
            manifest.put("droppedEntries", dropped);
            manifest.put("sources", sources.stream().map(SourceStatus::toJson).toList());
            try {
                return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
            } catch (IOException e) {
                return "{\"error\":\"manifest-serialize-failed\"}".getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    private static String toolVersion() {
        try {
            String v = DiagnoseSupportBundle.class.getPackage().getImplementationVersion();
            return (v != null && !v.isBlank()) ? v : "0.1.0-SNAPSHOT";
        } catch (Exception e) {
            return "0.1.0-SNAPSHOT";
        }
    }

    private void printOk(Path output, int bytes, int entryCount, Set<String> secrets) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("output", scrubDisplay(output.toString(), secrets));
            result.put("bytes", bytes);
            result.put("entries", entryCount + 1);
            out.println(mapper.writeValueAsString(result));
        } catch (IOException e) {
            out.println("{\"ok\":true}");
        }
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
        err.println("{\"code\":\"" + code + "\",\"message\":\""
                + (message == null ? "" : message.replace("\"", "\\\"")) + "\"}");
    }

    /** Bounded per-source collection status recorded in the manifest (no body, no URL, no stack). */
    private static final class SourceStatus {
        private final String name;
        private final boolean ok;
        private final String code;
        private final int httpStatus;
        private final Map<String, Object> details;

        static SourceStatus ok(String name, Map<String, Object> details) {
            return new SourceStatus(name, true, "OK", 0, details);
        }

        static SourceStatus failed(String name, String code, int httpStatus) {
            return new SourceStatus(name, false, code == null ? "SOURCE_FAILED" : code, httpStatus, Map.of());
        }

        private SourceStatus(String name, boolean ok, String code, int httpStatus,
                             Map<String, Object> details) {
            this.name = name;
            this.ok = ok;
            this.code = code;
            this.httpStatus = httpStatus;
            this.details = details;
        }

        Map<String, Object> toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("status", ok ? "ok" : "failed");
            out.put("code", code);
            if (httpStatus > 0) {
                out.put("httpStatus", httpStatus);
            }
            if (details != null && !details.isEmpty()) {
                out.put("details", details);
            }
            return out;
        }
    }
}
