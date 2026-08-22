package com.example.kairo.agent.server;

import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.agent.core.RecordedInvocation;
import com.example.kairo.agent.core.RecordingEventSink;
import com.example.kairo.api.diagnostics.DiagnosticEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class PlatformRecordingUploader implements RecordingEventSink, AutoCloseable {

    private final AgentRuntime runtime;
    private final AgentLaunchConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ArrayBlockingQueue<RecordedInvocation> queue;
    private final ScheduledExecutorService executor;

    PlatformRecordingUploader(AgentRuntime runtime, AgentLaunchConfig config) {
        this.runtime = runtime;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.queue = new ArrayBlockingQueue<>(config.recordingQueueCapacity());
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kairo-recording-uploader");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        executor.scheduleWithFixedDelay(this::flushSafely,
                config.recordingFlushIntervalMillis(),
                config.recordingFlushIntervalMillis(),
                TimeUnit.MILLISECONDS);
        runtime.recordEvent("recording.uploader.started", "agent", null, null,
                DiagnosticEvent.format("recording.uploader.started",
                        "queueCapacity", config.recordingQueueCapacity(),
                        "batchSize", config.recordingBatchSize(),
                        "flushIntervalMs", config.recordingFlushIntervalMillis()));
    }

    @Override
    public void accept(RecordedInvocation event) {
        if (!queue.offer(event)) {
            runtime.recordEvent("recording.event.dropped", "agent", null, event.sessionId(),
                    DiagnosticEvent.format("recording.event.dropped",
                            "reason", "queue-full", "queueSize", queue.size(),
                            "queueCapacity", config.recordingQueueCapacity()));
        }
    }

    private void flushSafely() {
        try {
            flush();
        } catch (Exception e) {
            runtime.recordEvent("recording.upload.failed", "agent", null, null,
                    DiagnosticEvent.format("recording.upload.failed",
                            "queuedEvents", queue.size(),
                            "failure", DiagnosticEvent.failureSummary(e),
                            "failureStack", DiagnosticEvent.stackSummary(e)));
        }
    }

    private void flush() throws Exception {
        List<RecordedInvocation> drained = new ArrayList<>(config.recordingBatchSize());
        queue.drainTo(drained, config.recordingBatchSize());
        if (drained.isEmpty()) {
            return;
        }
        Map<String, List<RecordedInvocation>> bySession = new LinkedHashMap<>();
        drained.forEach(event -> bySession.computeIfAbsent(event.sessionId(), ignored -> new ArrayList<>())
                .add(event));
        List<Map.Entry<String, List<RecordedInvocation>>> batches = new ArrayList<>(bySession.entrySet());
        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            Map.Entry<String, List<RecordedInvocation>> entry = batches.get(batchIndex);
            try {
                upload(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                // drainTo removed every session from the queue. If one session fails, restore the
                // failed batch and every not-yet-attempted batch; otherwise later sessions vanish.
                RequeueResult result = requeuePending(batches, batchIndex, queue);
                runtime.recordEvent("recording.upload.requeued", "agent", null, entry.getKey(),
                        DiagnosticEvent.format("recording.upload.requeued",
                                "failedBatchEvents", entry.getValue().size(),
                                "pendingBatches", batches.size() - batchIndex,
                                "pendingEvents", result.pendingEvents(),
                                "requeuedEvents", result.requeuedEvents(),
                                "droppedEvents", result.droppedEvents(), "queueSize", queue.size()));
                throw e;
            }
        }
    }

    static RequeueResult requeuePending(List<Map.Entry<String, List<RecordedInvocation>>> batches,
                                        int startIndex,
                                        ArrayBlockingQueue<RecordedInvocation> queue) {
        int requeued = 0;
        int pending = 0;
        for (int pendingBatch = startIndex; pendingBatch < batches.size(); pendingBatch++) {
            for (RecordedInvocation event : batches.get(pendingBatch).getValue()) {
                pending++;
                if (queue.offer(event)) {
                    requeued++;
                }
            }
        }
        return new RequeueResult(pending, requeued, pending - requeued);
    }

    record RequeueResult(int pendingEvents, int requeuedEvents, int droppedEvents) {
    }

    private void upload(String sessionId, List<RecordedInvocation> events) throws Exception {
        long started = System.nanoTime();
        String batchId = config.platformAgentId() + "-" + sessionId + "-" + UUID.randomUUID();
        List<Map<String, Object>> payloadEvents = events.stream().map(this::payload).toList();
        Map<String, Object> body = Map.of(
                "batchId", batchId,
                "events", payloadEvents
        );
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                        config.platformUrl() + "/api/v1/recording-sessions/" + sessionId + "/events"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("X-Actor", config.platformAgentId())
                .header("X-Identity-Source", "agent")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (!config.platformToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.platformToken());
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Platform recording API returned status="
                    + response.statusCode() + " batchId=" + batchId);
        }
        runtime.recordEvent("recording.upload.completed", "agent", null, sessionId,
                DiagnosticEvent.format("recording.upload.completed", "batchId", batchId,
                        "eventCount", events.size(), "httpStatus", response.statusCode(),
                        "durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
    }

    private Map<String, Object> payload(RecordedInvocation event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", event.id());
        payload.put("traceId", event.traceId());
        payload.put("protocol", event.protocol());
        payload.put("eventTime", event.eventTime().toString());
        payload.put("metadata", event.metadata());
        payload.put("arguments", event.arguments());
        if (event.result() != null) {
            payload.put("result", event.result());
        }
        if (!event.error().isEmpty()) {
            payload.put("error", event.error());
        }
        return payload;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            flush();
        } catch (Exception e) {
            runtime.recordEvent("recording.upload.close_failed", "agent", null, null,
                    DiagnosticEvent.format("recording.upload.close_failed",
                            "queuedEvents", queue.size(),
                            "failure", DiagnosticEvent.failureSummary(e),
                            "failureStack", DiagnosticEvent.stackSummary(e)));
        }
        executor.shutdownNow();
    }
}
