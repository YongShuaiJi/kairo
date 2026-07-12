package com.example.kairo.platform.command;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * In-memory bridge for the {@code RESOLVE_TARGET} command channel, mirroring
 * {@link BytecodeDiagnosticExchange} and {@link ScriptSessionExchange}.
 *
 * <p>Save-time target resolution (V1.3 §3.5) is synchronous: the rule-save request blocks on
 * {@link #await} until the agent acks with a {@link com.example.kairo.api.TargetMatchResult};
 * {@code AgentCommandService.ack} invokes {@link #complete} or {@link #fail}. There is no
 * transient payload to splice (unlike bytecode preview or script source), so the durable
 * {@code agent_command.payload_json} carries the full resolution request.
 */
@Component
public class TargetResolutionExchange {

    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> completedEarly = new ConcurrentHashMap<>();

    public synchronized void register(String commandId) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(commandId, future);
        Map<String, Object> early = completedEarly.remove(commandId);
        if (early != null) {
            future.complete(early);
        }
    }

    public synchronized void complete(String commandId, Map<String, Object> result) {
        CompletableFuture<Map<String, Object>> future = pending.get(commandId);
        Map<String, Object> copy = Collections.unmodifiableMap(new LinkedHashMap<>(result));
        if (future != null) {
            future.complete(copy);
        } else if (completedEarly.size() < 256) {
            completedEarly.put(commandId, copy);
        }
    }

    public synchronized void fail(String commandId, String message, Map<String, Object> result) {
        CompletableFuture<Map<String, Object>> future = pending.get(commandId);
        if (future != null) {
            future.completeExceptionally(new TargetResolutionFailure(message, result));
        }
    }

    public Map<String, Object> await(String commandId, Duration timeout) {
        CompletableFuture<Map<String, Object>> future = pending.get(commandId);
        if (future == null) {
            throw new IllegalStateException("target resolution exchange is not registered for " + commandId);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new TargetResolutionTimeoutException(timeout);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("target resolution command did not complete", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("await interrupted", e);
        } finally {
            remove(commandId);
        }
    }

    public void remove(String commandId) {
        pending.remove(commandId);
        completedEarly.remove(commandId);
    }

    public int pendingCount() {
        return pending.size();
    }
}
