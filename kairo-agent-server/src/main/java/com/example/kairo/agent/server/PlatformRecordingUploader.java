package com.example.kairo.agent.server;

import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.agent.core.RecordedInvocation;
import com.example.kairo.agent.core.RecordingEventSink;
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
    }

    @Override
    public void accept(RecordedInvocation event) {
        if (!queue.offer(event)) {
            runtime.recordEvent("recording.event.dropped", "agent", null, event.sessionId(),
                    "Recording upload queue is full");
        }
    }

    private void flushSafely() {
        try {
            flush();
        } catch (Exception e) {
            runtime.recordEvent("recording.upload.failed", "agent", null, null,
                    e.getClass().getName() + ": " + e.getMessage());
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
        for (Map.Entry<String, List<RecordedInvocation>> entry : bySession.entrySet()) {
            try {
                upload(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                entry.getValue().forEach(queue::offer);
                throw e;
            }
        }
    }

    private void upload(String sessionId, List<RecordedInvocation> events) throws Exception {
        List<Map<String, Object>> payloadEvents = events.stream().map(this::payload).toList();
        Map<String, Object> body = Map.of(
                "batchId", config.platformAgentId() + "-" + sessionId + "-" + UUID.randomUUID(),
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
            throw new IllegalStateException("Platform recording API returned "
                    + response.statusCode() + ": " + response.body());
        }
        runtime.recordEvent("recording.upload", "agent", null, sessionId,
                "Uploaded " + events.size() + " invocation events");
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
            runtime.recordEvent("recording.upload.close_failed", "agent", null, null, e.getMessage());
        }
        executor.shutdownNow();
    }
}
