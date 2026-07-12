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
 * In-memory bridge for the script-session command channel, mirroring {@link BytecodeDiagnosticExchange}.
 *
 * <p>Two concerns are handled here:
 * <ul>
 *   <li><b>Transient script source.</b> The trial script body is never persisted &mdash; the
 *       {@code script_session} row and the {@code agent_command.payload_json} store only the script
 *       hash. The source is registered against the command id and spliced into the payload only at
 *       poll time via {@link #enrichPayload}, so a server restart drops it (the caller times out and
 *       the session stays in its pre-command state) but no durable store ever sees it.</li>
 *   <li><b>Synchronous ack.</b> A script-session API request blocks on {@link #await} until the
 *       agent acks; {@code AgentCommandService.ack} invokes {@link #complete} or {@link #fail}.</li>
 * </ul>
 */
@Component
public class ScriptSessionExchange {

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> completedEarly = new ConcurrentHashMap<>();

    public synchronized void register(String commandId, String scriptSource) {
        Pending value = new Pending(scriptSource, new CompletableFuture<>());
        pending.put(commandId, value);
        Map<String, Object> early = completedEarly.remove(commandId);
        if (early != null) {
            value.result().complete(early);
        }
    }

    /** Splice the transient script source into a copy of the persisted payload for the agent. */
    public Map<String, Object> enrichPayload(String commandId, Map<String, Object> persistedPayload) {
        Pending value = pending.get(commandId);
        if (value == null || value.scriptSource() == null) {
            return persistedPayload;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(persistedPayload);
        enriched.put("script", value.scriptSource());
        return enriched;
    }

    public synchronized void complete(String commandId, Map<String, Object> result) {
        Pending value = pending.get(commandId);
        Map<String, Object> copy = Collections.unmodifiableMap(new LinkedHashMap<>(result));
        if (value != null) {
            value.result().complete(copy);
        } else if (completedEarly.size() < 256) {
            completedEarly.put(commandId, copy);
        }
    }

    public synchronized void fail(String commandId, String message, Map<String, Object> result) {
        Pending value = pending.get(commandId);
        if (value != null) {
            value.result().completeExceptionally(new ScriptCommandFailure(message, result));
        }
    }

    public Map<String, Object> await(String commandId, Duration timeout) {
        Pending value = pending.get(commandId);
        if (value == null) {
            throw new IllegalStateException("script session exchange is not registered for " + commandId);
        }
        try {
            return value.result().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ScriptCommandTimeoutException(timeout);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("script session command did not complete", cause);
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

    private record Pending(String scriptSource, CompletableFuture<Map<String, Object>> result) { }
}
