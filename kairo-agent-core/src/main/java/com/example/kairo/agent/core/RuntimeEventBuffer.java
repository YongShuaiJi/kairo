package com.example.kairo.agent.core;

import com.example.kairo.api.ScriptLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class RuntimeEventBuffer implements ScriptLog {

    private final int maxEvents;
    private final int maxMessageLength;
    private final ArrayDeque<RuntimeEvent> events = new ArrayDeque<>();

    public RuntimeEventBuffer() {
        this(1000, 1000);
    }

    public RuntimeEventBuffer(int maxEvents, int maxMessageLength) {
        this.maxEvents = maxEvents;
        this.maxMessageLength = maxMessageLength;
    }

    public void record(String type, String actor, String ruleId, String target, String message) {
        RuntimeEvent event = new RuntimeEvent(
                System.currentTimeMillis(),
                type,
                actor == null ? "system" : actor,
                ruleId,
                target,
                truncate(message)
        );
        synchronized (events) {
            events.addLast(event);
            while (events.size() > maxEvents) {
                events.removeFirst();
            }
        }
    }

    public List<RuntimeEvent> snapshot() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    @Override
    public void debug(String message) {
        record("script.debug", "script", null, null, message);
    }

    @Override
    public void info(String message) {
        record("script.info", "script", null, null, message);
    }

    @Override
    public void warn(String message) {
        record("script.warn", "script", null, null, message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        String suffix = throwable == null ? "" : " :: " + throwable.getClass().getName() + ": " + throwable.getMessage();
        record("script.error", "script", null, null, message + suffix);
    }

    private String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= maxMessageLength ? message : message.substring(0, maxMessageLength) + "...";
    }
}
