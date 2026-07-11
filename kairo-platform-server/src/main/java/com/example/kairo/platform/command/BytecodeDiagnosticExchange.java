package com.example.kairo.platform.command;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Collections;

/** In-memory, request-scoped bridge for diagnostic bytes that must never enter persistence. */
@Component
public class BytecodeDiagnosticExchange {
    private static final int MAX_INPUT = 1024 * 1024;
    private static final int MAX_OUTPUT = 8 * 1024 * 1024;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> completedEarly = new ConcurrentHashMap<>();

    public synchronized void register(String commandId, byte[] previewInput) {
        if (previewInput != null && (previewInput.length == 0 || previewInput.length > MAX_INPUT)) {
            throw new IllegalArgumentException("preview input must be 1..1048576 bytes");
        }
        Pending value = new Pending(previewInput == null ? null : previewInput.clone(), new CompletableFuture<>());
        pending.put(commandId, value);
        Map<String, Object> early = completedEarly.remove(commandId);
        if (early != null) value.result.complete(early);
    }

    public Map<String, Object> enrichPayload(String commandId, Map<String, Object> persistedPayload) {
        Pending value = pending.get(commandId);
        if (value == null || value.previewInput == null) return persistedPayload;
        Map<String, Object> enriched = new LinkedHashMap<>(persistedPayload);
        enriched.put("bytecodeBase64Url", java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.previewInput));
        return enriched;
    }

    public synchronized void complete(String commandId, Map<String, Object> result) {
        Pending value = pending.get(commandId);
        Map<String, Object> copy = Collections.unmodifiableMap(new LinkedHashMap<>(result));
        if (value != null) value.result.complete(copy);
        else {
            if (completedEarly.size() >= 256) completedEarly.clear();
            completedEarly.put(commandId, copy);
        }
    }

    public synchronized void fail(String commandId, String message) {
        Pending value = pending.get(commandId);
        if (value != null) value.result.completeExceptionally(new IllegalStateException(message));
    }

    public Map<String, Object> await(String commandId, Duration timeout) {
        Pending value = pending.get(commandId);
        if (value == null) throw new IllegalStateException("diagnostic exchange is not registered");
        try {
            Map<String, Object> result = value.result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            Object encoded = result.get("bytecodeBase64Url");
            if (encoded instanceof String text) {
                byte[] bytes = java.util.Base64.getUrlDecoder().decode(text);
                if (bytes.length > MAX_OUTPUT) throw new IllegalStateException("diagnostic output exceeds 8 MiB");
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("diagnostic command did not complete", e);
        } finally {
            remove(commandId);
        }
    }

    public Map<String, Object> sanitizeForPersistence(Map<String, Object> result) {
        return sanitizeMap(result);
    }

    private Map<String, Object> sanitizeMap(Map<?, ?> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String name = String.valueOf(key);
            if ("bytecodeBase64Url".equals(name) || "sourceCode".equals(name)) return;
            if (value instanceof Map<?, ?> nested) safe.put(name, sanitizeMap(nested));
            else if (value instanceof java.util.List<?> list) safe.put(name, list.stream()
                    .map(item -> item instanceof Map<?, ?> nested ? sanitizeMap(nested) : item).toList());
            else safe.put(name, value);
        });
        return safe;
    }

    public void remove(String commandId) {
        Pending removed = pending.remove(commandId);
        if (removed != null && removed.previewInput != null) {
            java.util.Arrays.fill(removed.previewInput, (byte) 0);
        }
    }

    public int pendingCount() { return pending.size(); }

    private record Pending(byte[] previewInput, CompletableFuture<Map<String, Object>> result) { }
}
