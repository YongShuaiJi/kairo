package com.example.runtimemock.agent.core;

import com.example.runtimemock.api.MethodMetadata;
import com.example.runtimemock.core.InvocationObserver;
import com.example.runtimemock.core.MethodKey;

import java.lang.reflect.Array;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class RecordingInvocationObserver implements InvocationObserver {

    private static final int MAX_DEPTH = 4;
    private static final int MAX_ITEMS = 100;
    private static final int MAX_STRING_LENGTH = 4096;

    private final ConcurrentHashMap<MethodKey, ConcurrentHashMap<String, RecordingRegistration>> sessions =
            new ConcurrentHashMap<>();
    private volatile RecordingEventSink sink = RecordingEventSink.NOOP;

    void sink(RecordingEventSink sink) {
        this.sink = sink == null ? RecordingEventSink.NOOP : sink;
    }

    void start(MethodKey methodKey, RecordingRegistration registration) {
        sessions.computeIfAbsent(methodKey, ignored -> new ConcurrentHashMap<>())
                .put(registration.sessionId(), registration);
    }

    void stop(MethodKey methodKey, String sessionId) {
        sessions.computeIfPresent(methodKey, (ignored, active) -> {
            active.remove(sessionId);
            return active.isEmpty() ? null : active;
        });
    }

    void clear() {
        sessions.clear();
    }

    boolean isRecording(MethodKey methodKey) {
        Map<String, RecordingRegistration> active = sessions.get(methodKey);
        return active != null && !active.isEmpty();
    }

    @Override
    public Object onEnter(MethodKey methodKey, MethodMetadata method, Object target, Object[] arguments) {
        Map<String, RecordingRegistration> active = sessions.get(methodKey);
        if (active == null || active.isEmpty()) {
            return null;
        }
        List<Object> argumentSnapshot = new ArrayList<>();
        if (arguments != null) {
            IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
            for (Object argument : arguments) {
                argumentSnapshot.add(snapshot(argument, 0, visited));
            }
        }
        return new RecordingToken(
                List.copyOf(active.values()),
                methodKey,
                List.copyOf(argumentSnapshot),
                System.nanoTime(),
                UUID.randomUUID().toString()
        );
    }

    @Override
    public void onExit(Object token, Object returnValue, Throwable throwable) {
        if (!(token instanceof RecordingToken recordingToken)) {
            return;
        }
        long durationNanos = Math.max(0, System.nanoTime() - recordingToken.startedNanos());
        Object result = throwable == null
                ? snapshot(returnValue, 0, new IdentityHashMap<>())
                : null;
        Map<String, Object> error = throwable == null
                ? Map.of()
                : throwableSnapshot(throwable);
        for (RecordingRegistration registration : recordingToken.registrations()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("className", recordingToken.methodKey().className());
            metadata.put("methodName", recordingToken.methodKey().methodName());
            metadata.put("methodDescriptor", recordingToken.methodKey().methodDescriptor());
            metadata.put("durationNanos", durationNanos);
            sink.accept(new RecordedInvocation(
                    registration.sessionId(),
                    "recording-event-" + UUID.randomUUID(),
                    recordingToken.traceId(),
                    "JAVA_METHOD",
                    Instant.now(),
                    Map.copyOf(metadata),
                    recordingToken.arguments(),
                    result,
                    error
            ));
        }
    }

    private Object snapshot(Object value, int depth, IdentityHashMap<Object, Boolean> visited) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence sequence) {
            return truncate(sequence.toString());
        }
        if (value instanceof Character || value instanceof Enum<?> || value instanceof Class<?>) {
            return truncate(String.valueOf(value));
        }
        if (value instanceof Throwable throwable) {
            return throwableSnapshot(throwable);
        }
        if (depth >= MAX_DEPTH) {
            return Map.of("type", value.getClass().getName(), "truncated", true);
        }
        if (visited.put(value, Boolean.TRUE) != null) {
            return Map.of("type", value.getClass().getName(), "cycle", true);
        }
        try {
            if (value.getClass().isArray()) {
                int length = Math.min(Array.getLength(value), MAX_ITEMS);
                List<Object> items = new ArrayList<>(length);
                for (int index = 0; index < length; index++) {
                    items.add(snapshot(Array.get(value, index), depth + 1, visited));
                }
                return items;
            }
            if (value instanceof Collection<?> collection) {
                List<Object> items = new ArrayList<>(Math.min(collection.size(), MAX_ITEMS));
                int count = 0;
                for (Object item : collection) {
                    if (count++ >= MAX_ITEMS) {
                        break;
                    }
                    items.add(snapshot(item, depth + 1, visited));
                }
                return items;
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> items = new LinkedHashMap<>();
                int count = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (count++ >= MAX_ITEMS) {
                        break;
                    }
                    items.put(truncate(String.valueOf(entry.getKey())),
                            snapshot(entry.getValue(), depth + 1, visited));
                }
                return items;
            }
            return Map.of(
                    "type", value.getClass().getName(),
                    "value", truncate(safeToString(value))
            );
        } finally {
            visited.remove(value);
        }
    }

    private Map<String, Object> throwableSnapshot(Throwable throwable) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", throwable.getClass().getName());
        error.put("message", truncate(String.valueOf(throwable.getMessage())));
        return error;
    }

    private String safeToString(Object value) {
        try {
            return String.valueOf(value);
        } catch (Throwable ignored) {
            return "<toString failed>";
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_STRING_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_STRING_LENGTH) + "…";
    }

    private record RecordingToken(
            List<RecordingRegistration> registrations,
            MethodKey methodKey,
            List<Object> arguments,
            long startedNanos,
            String traceId
    ) {
    }
}
