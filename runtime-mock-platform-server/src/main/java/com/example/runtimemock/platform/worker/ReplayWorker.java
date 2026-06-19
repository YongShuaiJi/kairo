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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            targets = List.of(Map.of("id", "synthetic", "target_type", "SYNTHETIC",
                    "target_json", PlatformJson.write(Map.of("message", "no replay targets configured"))));
        }
        int total = 0;
        int matched = 0;
        long durationMillis = 0;
        for (Map<String, Object> target : targets) {
            Invocation invocation = invoke(target);
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
        Map<String, Object> metrics = Map.of(
                "targetCount", total,
                "matchedCount", matched,
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

    private Invocation invoke(Map<String, Object> targetRow) throws Exception {
        String targetType = String.valueOf(targetRow.get("target_type"));
        Map<String, Object> target = PlatformJson.readMap(String.valueOf(targetRow.get("target_json")));
        Instant start = clock.instant();
        if ("HTTP".equals(targetType) && target.containsKey("url")) {
            String method = String.valueOf(target.getOrDefault("method", "GET"));
            String body = PlatformJson.write(target.getOrDefault("body", Map.of()));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(String.valueOf(target.get("url"))))
                    .timeout(Duration.ofSeconds(10));
            if ("GET".equalsIgnoreCase(method)) {
                builder.GET();
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
                builder.header("Content-Type", "application/json");
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long millis = Duration.between(start, clock.instant()).toMillis();
            Map<String, Object> request = Map.of("method", method, "target", target);
            Map<String, Object> responsePayload = Map.of("statusCode", response.statusCode(), "body", response.body());
            boolean matched = response.statusCode() >= 200 && response.statusCode() < 500;
            return new Invocation("http:" + target.get("url"), matched ? "SUCCEEDED" : "FAILED",
                    PlatformJson.sha256(request), PlatformJson.sha256(responsePayload),
                    matched ? null : "HTTP status " + response.statusCode(), millis, matched,
                    Map.of("response", responsePayload));
        }
        Map<String, Object> synthetic = new LinkedHashMap<>();
        synthetic.put("targetType", targetType);
        synthetic.put("target", target);
        synthetic.put("result", "synthetic replay accepted");
        long millis = Duration.between(start, clock.instant()).toMillis();
        return new Invocation("synthetic:" + targetRow.get("id"), "SUCCEEDED",
                PlatformJson.sha256(target), PlatformJson.sha256(synthetic), null, millis, true,
                Map.of("synthetic", synthetic));
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
}
