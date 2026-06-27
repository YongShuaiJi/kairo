package com.example.runtimemock.platform.worker;

import com.example.runtimemock.platform.service.PlatformJdbcService;
import com.example.runtimemock.platform.service.PlatformJson;
import com.example.runtimemock.platform.service.RequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "runtime-mock.platform",
        name = {"worker.enabled", "replay.worker.enabled"}, havingValue = "true")
public class ReplayWorker {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformJdbcService eventWriter;
    private final WorkerArtifactStore objectStore;
    private final HttpClient httpClient;
    private final Clock clock;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicLong totalExecutions = new AtomicLong();
    private final AtomicLong totalFailures = new AtomicLong();
    private final AtomicLong consecutiveFailures = new AtomicLong();

    @Autowired
    public ReplayWorker(JdbcTemplate jdbcTemplate, PlatformJdbcService eventWriter,
                        WorkerArtifactStore objectStore,
                        @Value("${runtime-mock.platform.replay.worker.batch-size:5}") int batchSize) {
        this(jdbcTemplate, eventWriter, objectStore, HttpClient.newHttpClient(), Clock.systemUTC(), batchSize);
    }

    ReplayWorker(JdbcTemplate jdbcTemplate, PlatformJdbcService eventWriter,
                 WorkerArtifactStore objectStore, HttpClient httpClient, Clock clock, int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventWriter = eventWriter;
        this.objectStore = objectStore;
        this.httpClient = httpClient;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${runtime-mock.platform.replay.worker.fixed-delay-ms:5000}",
            initialDelayString = "${runtime-mock.platform.replay.worker.fixed-delay-ms:5000}")
    public void scheduledRun() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            runOnce(systemContext());
        } finally {
            running.set(false);
        }
    }

    @Transactional
    public Map<String, Object> runOnce(RequestContext context) {
        if (paused.get()) {
            return Map.of("processed", 0, "succeeded", 0, "failed", 0, "paused", true);
        }
        int processed = 0;
        int succeeded = 0;
        int failed = 0;
        List<Map<String, Object>> executions = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from replay_execution
                 where status = 'QUEUED'
                 order by updated_at, id
                 limit ?
                """, batchSize));
        for (Map<String, Object> execution : executions) {
            processed++;
            try {
                processExecution(context, execution);
                succeeded++;
                totalExecutions.incrementAndGet();
                consecutiveFailures.set(0);
            } catch (Exception e) {
                failExecution(context, execution, e);
                failed++;
                long total = totalExecutions.incrementAndGet();
                long failures = totalFailures.incrementAndGet();
                long consecutive = consecutiveFailures.incrementAndGet();
                if (consecutive >= 10 || (total >= 50 && failures * 5 > total)) {
                    paused.set(true);
                    eventWriter.recordEvent(context, "replay_worker.auto_paused", "replay_worker",
                            "singleton", total, Map.of(), Map.of("paused", true), "PAUSED",
                            "Replay worker exceeded failure threshold",
                            Map.of("total", total, "failures", failures, "consecutiveFailures", consecutive));
                    break;
                }
            }
        }
        return Map.of("processed", processed, "succeeded", succeeded, "failed", failed, "paused", paused.get());
    }

    private void processExecution(RequestContext context, Map<String, Object> execution) throws Exception {
        String executionId = String.valueOf(execution.get("id"));
        long runningVersion = ((Number) execution.get("version")).longValue() + 1;
        long queuedVersion = ((Number) execution.get("version")).longValue();
        Instant now = clock.instant();
        int claimed = jdbcTemplate.update("""
                update replay_execution
                   set status = 'RUNNING', version = ?, updated_by = ?, updated_at = ?
                 where id = ? and status = 'QUEUED' and version = ?
                """, runningVersion, context.actor(), timestamp(now), executionId, queuedVersion);
        if (claimed == 0) {
            return;
        }
        String batchId = "replay-batch-" + UUID.randomUUID();
        jdbcTemplate.update("""
                insert into replay_batch(
                    id, replay_execution_id, batch_order, status, trace_selector_json, started_at, finished_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """, batchId, executionId, 1, "RUNNING", PlatformJson.write(Map.of("mode", "all")),
                timestamp(now), null);
        Map<String, Object> plan = normalizeRow(jdbcTemplate.queryForMap("""
                select p.*
                  from replay_plan p
                  join replay_execution e on e.replay_plan_id = p.id
                 where e.id = ?
                """, executionId));
        List<Map<String, Object>> targets = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from replay_target
                 where replay_plan_id = ?
                 order by created_at, id
                """, plan.get("id")));
        if (targets.isEmpty()) {
            throw new IllegalStateException("回放计划没有配置真实目标：" + plan.get("id"));
        }
        List<DatasetRow> datasetRows = loadDatasetRows(plan);
        if (datasetRows.isEmpty()) {
            throw new IllegalStateException("数据集没有可回放的记录："
                    + plan.get("dataset_id") + ":" + plan.get("dataset_version"));
        }
        Map<String, Object> comparisonPolicy = loadComparisonPolicy(String.valueOf(plan.get("id")));
        int total = 0;
        int matched = 0;
        long durationMillis = 0;
        for (DatasetRow datasetRow : datasetRows) {
            for (Map<String, Object> target : targets) {
                Invocation invocation = invoke(target, datasetRow, comparisonPolicy);
                durationMillis += invocation.durationMillis();
                total++;
                if (invocation.matched()) {
                    matched++;
                }
                String resultId = "replay-invocation-" + UUID.randomUUID();
                jdbcTemplate.update("""
                        insert into replay_invocation_result(
                            id, replay_batch_id, invocation_key, status, request_hash, response_hash,
                            error_message, duration_millis, created_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, resultId, batchId, invocation.key(), invocation.status(),
                        invocation.requestHash(), invocation.responseHash(), invocation.errorMessage(),
                        invocation.durationMillis(), timestamp(clock.instant()));
                jdbcTemplate.update("""
                        insert into comparison_result(
                            id, replay_invocation_result_id, status, diff_json, created_at
                        ) values (?, ?, ?, ?, ?)
                        """, "comparison-" + UUID.randomUUID(), resultId,
                        invocation.matched() ? "MATCHED" : "DIFFERENT",
                        PlatformJson.write(invocation.diff()), timestamp(clock.instant()));
            }
        }
        Map<String, Object> metrics = Map.of(
                "datasetRecordCount", datasetRows.size(),
                "targetCount", targets.size(),
                "invocationCount", total,
                "matchedCount", matched,
                "differentCount", total - matched,
                "durationMillis", durationMillis);
        WorkerArtifactStore.ArtifactObject artifact = objectStore.putJson("replay", "replay_execution", executionId,
                "SUMMARY_JSON", metrics, Map.of("replayPlanId", plan.get("id")));
        int batchCompleted = jdbcTemplate.update("""
                update replay_batch
                   set status = ?, finished_at = ?
                 where id = ? and status = 'RUNNING'
                """, matched == total ? "SUCCEEDED" : "FAILED", timestamp(clock.instant()), batchId);
        if (batchCompleted == 0) {
            throw new IllegalStateException("Replay batch state changed while worker was running: " + batchId);
        }
        long completedVersion = runningVersion + 1;
        int executionCompleted = jdbcTemplate.update("""
                update replay_execution
                   set status = ?, version = ?, metrics_json = ?, updated_by = ?, updated_at = ?
                 where id = ? and status = 'RUNNING' and version = ?
                """, matched == total ? "SUCCEEDED" : "FAILED", completedVersion,
                PlatformJson.write(metrics), context.actor(), timestamp(clock.instant()), executionId, runningVersion);
        if (executionCompleted == 0) {
            throw new IllegalStateException("Replay execution state changed while worker was running: " + executionId);
        }
        Map<String, Object> updated = normalizeRow(jdbcTemplate.queryForMap(
                "select * from replay_execution where id = ?", executionId));
        eventWriter.recordEvent(context, "replay_execution.worker_completed", "replay_execution", executionId,
                completedVersion, execution, updated, matched == total ? "SUCCESS" : "FAILED",
                "replay worker completed", Map.of("metrics", metrics, "artifact", artifact.objectUri()));
    }

    private List<DatasetRow> loadDatasetRows(Map<String, Object> plan) {
        String datasetVersionId = plan.get("dataset_id") + ":" + plan.get("dataset_version");
        List<Map<String, Object>> references = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from dataset_object_reference
                 where dataset_version_id = ?
                 order by created_at, id
                """, datasetVersionId));
        if (references.isEmpty()) {
            throw new IllegalStateException("数据集版本没有对象引用：" + datasetVersionId);
        }
        List<DatasetRow> rows = new ArrayList<>();
        for (Map<String, Object> reference : references) {
            String objectUri = String.valueOf(reference.get("object_uri"));
            String objectType = String.valueOf(reference.get("object_type"));
            Map<String, Object> metadata =
                    PlatformJson.readMap(String.valueOf(reference.getOrDefault("metadata_json", "{}")));
            List<Map<String, Object>> objectRows = objectStore.readRows(objectUri, objectType, metadata);
            for (int index = 0; index < objectRows.size(); index++) {
                rows.add(new DatasetRow(String.valueOf(reference.get("id")), index, objectRows.get(index)));
            }
        }
        return rows;
    }

    private Map<String, Object> loadComparisonPolicy(String replayPlanId) {
        List<Map<String, Object>> policies = normalizeRows(jdbcTemplate.queryForList("""
                select policy_json
                  from comparison_policy
                 where replay_plan_id = ?
                 order by created_at desc, id desc
                 limit 1
                """, replayPlanId));
        if (policies.isEmpty()) {
            return Map.of();
        }
        return PlatformJson.readMap(String.valueOf(policies.get(0).get("policy_json")));
    }

    private Invocation invoke(Map<String, Object> targetRow, DatasetRow datasetRow,
                              Map<String, Object> comparisonPolicy) throws Exception {
        String targetType = String.valueOf(targetRow.get("target_type"));
        Map<String, Object> target = PlatformJson.readMap(String.valueOf(targetRow.get("target_json")));
        Instant start = clock.instant();
        if (!"HTTP".equalsIgnoreCase(targetType) || !target.containsKey("url")) {
            throw new IllegalArgumentException("不支持的回放目标类型或缺少 URL："
                    + targetType + " target=" + targetRow.get("id"));
        }

        Map<String, Object> row = datasetRow.value();
        String method = String.valueOf(target.getOrDefault("method", "POST")).toUpperCase();
        String url = interpolate(String.valueOf(target.get("url")), row);
        Object requestBody = requestBody(target, row);
        String body = requestBody instanceof String text ? interpolate(text, row) : PlatformJson.write(requestBody);
        long timeoutSeconds = longValue(target.getOrDefault("timeoutSeconds", 10), 10);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
        Map<String, Object> headers = mapValue(target.get("headers"));
        headers.forEach((name, value) -> builder.header(name, interpolate(String.valueOf(value), row)));
        if ("GET".equals(method) || "DELETE".equals(method)) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            if (!headers.keySet().stream().anyMatch(name -> "content-type".equalsIgnoreCase(name))) {
                builder.header("Content-Type", "application/json");
            }
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        long millis = Duration.between(start, clock.instant()).toMillis();
        Object actualBody = parseResponseBody(response.body());
        Comparison comparison = compare(target, comparisonPolicy, row, response.statusCode(), actualBody);
        Map<String, Object> requestPayload = new LinkedHashMap<>();
        requestPayload.put("method", method);
        requestPayload.put("url", url);
        requestPayload.put("headers", headers);
        requestPayload.put("body", requestBody);
        requestPayload.put("datasetReferenceId", datasetRow.referenceId());
        requestPayload.put("datasetRowIndex", datasetRow.index());
        Map<String, Object> responsePayload = Map.of(
                "statusCode", response.statusCode(),
                "body", actualBody
        );
        String invocationKey = "http:" + targetRow.get("id") + ":"
                + datasetRow.referenceId() + ":" + datasetRow.index();
        return new Invocation(invocationKey, comparison.matched() ? "SUCCEEDED" : "FAILED",
                PlatformJson.sha256(requestPayload), PlatformJson.sha256(responsePayload),
                comparison.matched() ? null : comparison.message(), millis, comparison.matched(),
                comparison.diff());
    }

    private Object requestBody(Map<String, Object> target, Map<String, Object> row) {
        if (target.containsKey("body")) {
            return resolveTemplates(target.get("body"), row);
        }
        if (target.containsKey("bodyPath")) {
            Object selected = valueAtPath(row, String.valueOf(target.get("bodyPath")));
            if (selected == null) {
                throw new IllegalArgumentException("回放记录中不存在 bodyPath：" + target.get("bodyPath"));
            }
            return selected;
        }
        Object arguments = row.get("arguments");
        if (arguments instanceof List<?> list) {
            return list.isEmpty() ? Map.of() : list.get(0);
        }
        if (arguments != null) {
            return arguments;
        }
        return row;
    }

    private Comparison compare(Map<String, Object> target, Map<String, Object> policy,
                               Map<String, Object> row, int actualStatus, Object actualBody) {
        Object expectedStatusValue = target.containsKey("expectedStatus")
                ? target.get("expectedStatus") : policy.get("expectedStatus");
        List<Integer> expectedStatuses = integerList(target.containsKey("expectedStatusCodes")
                ? target.get("expectedStatusCodes") : policy.get("expectedStatusCodes"));
        boolean statusMatched;
        Object expectedStatusDescription;
        if (!expectedStatuses.isEmpty()) {
            statusMatched = expectedStatuses.contains(actualStatus);
            expectedStatusDescription = expectedStatuses;
        } else if (expectedStatusValue != null) {
            int expectedStatus = (int) longValue(expectedStatusValue, 200);
            statusMatched = actualStatus == expectedStatus;
            expectedStatusDescription = expectedStatus;
        } else {
            statusMatched = actualStatus >= 200 && actualStatus < 300;
            expectedStatusDescription = "2xx";
        }

        boolean compareBody = booleanValue(target.getOrDefault(
                "compareBody", policy.getOrDefault("compareBody", false)));
        Object expectedBody = null;
        boolean bodyMatched = true;
        if (compareBody) {
            if (target.containsKey("expectedBody")) {
                expectedBody = resolveTemplates(target.get("expectedBody"), row);
            } else {
                String expectedSource = String.valueOf(target.getOrDefault(
                        "expectedSource", policy.getOrDefault("expectedSource", "RECORDED_RESULT")));
                expectedBody = "RECORDED_RESULT".equalsIgnoreCase(expectedSource)
                        ? row.get("result")
                        : valueAtPath(row, expectedSource);
            }
            List<String> compareFields = stringList(target.containsKey("compareFields")
                    ? target.get("compareFields") : policy.get("compareFields"));
            List<String> ignoreFields = stringList(target.containsKey("ignoreFields")
                    ? target.get("ignoreFields") : policy.get("ignoreFields"));
            Object normalizedExpected = normalizeForComparison(expectedBody, compareFields, ignoreFields);
            Object normalizedActual = normalizeForComparison(actualBody, compareFields, ignoreFields);
            bodyMatched = Objects.equals(normalizedExpected, normalizedActual);
            expectedBody = normalizedExpected;
            actualBody = normalizedActual;
        }

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("expectedStatus", expectedStatusDescription);
        diff.put("actualStatus", actualStatus);
        diff.put("statusMatched", statusMatched);
        diff.put("bodyCompared", compareBody);
        diff.put("bodyMatched", bodyMatched);
        if (compareBody) {
            diff.put("expectedBody", expectedBody);
            diff.put("actualBody", actualBody);
        }
        boolean matched = statusMatched && bodyMatched;
        String message = matched ? "" : "回放结果不匹配：statusMatched="
                + statusMatched + ", bodyMatched=" + bodyMatched;
        return new Comparison(matched, message, diff);
    }

    private Object normalizeForComparison(Object value, List<String> compareFields,
                                          List<String> ignoreFields) {
        if (!(value instanceof Map<?, ?> map)) {
            return value;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(PlatformJson.stringKeyMap(map));
        if (!compareFields.isEmpty()) {
            Map<String, Object> selected = new LinkedHashMap<>();
            for (String field : compareFields) {
                selected.put(field, valueAtPath(normalized, field));
            }
            normalized = selected;
        }
        ignoreFields.forEach(normalized::remove);
        return normalized;
    }

    private Object resolveTemplates(Object value, Map<String, Object> row) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((key, item) -> resolved.put(String.valueOf(key), resolveTemplates(item, row)));
            return resolved;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(item -> resolveTemplates(item, row)).toList();
        }
        if (value instanceof String text) {
            if (text.startsWith("{{") && text.endsWith("}}")
                    && text.indexOf("{{", 2) < 0) {
                Object selected = valueAtPath(row, text.substring(2, text.length() - 2).trim());
                return selected == null ? "" : selected;
            }
            return interpolate(text, row);
        }
        return value;
    }

    private String interpolate(String template, Map<String, Object> row) {
        String result = template;
        for (Map.Entry<String, Object> entry : flatten(row).entrySet()) {
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            result = result.replace("{{" + entry.getKey() + "}}", value)
                    .replace("${" + entry.getKey() + "}", value);
        }
        return result;
    }

    private Map<String, Object> flatten(Map<String, Object> source) {
        Map<String, Object> flattened = new LinkedHashMap<>();
        flatten("", source, flattened);
        return flattened;
    }

    private void flatten(String prefix, Map<String, Object> source, Map<String, Object> target) {
        source.forEach((key, value) -> {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            target.put(path, value);
            if (value instanceof Map<?, ?> nested) {
                flatten(path, PlatformJson.stringKeyMap(nested), target);
            }
        });
    }

    private Object valueAtPath(Map<String, Object> source, String path) {
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private Object parseResponseBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.trim();
        try {
            if (trimmed.startsWith("{")) {
                return PlatformJson.readMap(trimmed);
            }
            if (trimmed.startsWith("[")) {
                return PlatformJson.readList(trimmed);
            }
        } catch (RuntimeException ignored) {
            // Preserve non-JSON responses as plain text.
        }
        return body;
    }

    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? PlatformJson.stringKeyMap(map) : Map.of();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream().map(String::valueOf).toList();
    }

    private List<Integer> integerList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream().map(item -> (int) longValue(item, -1)).toList();
    }

    private long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private void failExecution(RequestContext context, Map<String, Object> execution, Exception e) {
        String executionId = String.valueOf(execution.get("id"));
        long queuedVersion = ((Number) execution.get("version")).longValue();
        long runningVersion = queuedVersion + 1;
        long failedVersion = runningVersion + 1;
        Map<String, Object> metrics = Map.of("error", e.getClass().getName(),
                "message", String.valueOf(e.getMessage()));
        int updatedCount = jdbcTemplate.update("""
                update replay_execution
                   set status = 'FAILED', version = ?, metrics_json = ?, updated_by = ?, updated_at = ?
                 where id = ? and status = 'RUNNING' and version = ?
                """, failedVersion, PlatformJson.write(metrics), context.actor(),
                timestamp(clock.instant()), executionId, runningVersion);
        if (updatedCount == 0) {
            return;
        }
        Map<String, Object> updated = normalizeRow(jdbcTemplate.queryForMap(
                "select * from replay_execution where id = ?", executionId));
        eventWriter.recordEvent(context, "replay_execution.worker_failed", "replay_execution", executionId,
                failedVersion, execution, updated, "FAILED", "replay worker failed", metrics);
    }

    private List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::normalizeRow).toList();
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(), value));
        return normalized;
    }

    private RequestContext systemContext() {
        return new RequestContext("replay-worker", "replay-" + clock.instant().toEpochMilli(),
                "127.0.0.1", "scheduler");
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private record Invocation(String key, String status, String requestHash, String responseHash,
                              String errorMessage, long durationMillis, boolean matched,
                              Map<String, Object> diff) {
    }

    private record DatasetRow(String referenceId, int index, Map<String, Object> value) {
    }

    private record Comparison(boolean matched, String message, Map<String, Object> diff) {
    }
}
