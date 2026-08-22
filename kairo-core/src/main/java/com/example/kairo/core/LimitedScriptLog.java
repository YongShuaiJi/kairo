package com.example.kairo.core;

import com.example.kairo.api.ScriptLog;
import com.example.kairo.api.diagnostics.DiagnosticEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class LimitedScriptLog implements ScriptLog {

    private final int maxEntries;
    private final int maxLength;
    private final ArrayDeque<String> entries = new ArrayDeque<>();

    public LimitedScriptLog() {
        this(200, 1000);
    }

    public LimitedScriptLog(int maxEntries, int maxLength) {
        this.maxEntries = maxEntries;
        this.maxLength = maxLength;
    }

    @Override
    public void debug(String message) {
        add("DEBUG", message, null);
    }

    @Override
    public void info(String message) {
        add("INFO", message, null);
    }

    @Override
    public void warn(String message) {
        add("WARN", message, null);
    }

    @Override
    public void error(String message, Throwable throwable) {
        add("ERROR", message, throwable);
    }

    public synchronized List<String> snapshot() {
        return new ArrayList<>(entries);
    }

    private synchronized void add(String level, String message, Throwable throwable) {
        String text = level + " " + truncate(DiagnosticEvent.sanitizeLogLine(message))
                + (throwable == null ? "" : " :: " + truncate(DiagnosticEvent.failureSummary(throwable)));
        entries.addLast(text);
        while (entries.size() > maxEntries) {
            entries.removeFirst();
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= maxLength ? message : message.substring(0, maxLength) + "...";
    }
}
