package com.example.runtimemock.agent.bootstrap;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentArguments {

    private final Map<String, String> values;

    private AgentArguments(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    public static AgentArguments parse(String args) {
        Map<String, String> values = new LinkedHashMap<>();
        if (args == null || args.trim().isEmpty()) {
            return new AgentArguments(values);
        }
        for (String part : args.split(",")) {
            if (part.trim().isEmpty()) {
                continue;
            }
            int separator = part.indexOf('=');
            if (separator < 0) {
                values.put(part.trim(), "true");
            } else {
                values.put(part.substring(0, separator).trim(), part.substring(separator + 1).trim());
            }
        }
        return new AgentArguments(values);
    }

    public String get(String name) {
        return values.get(name);
    }

    public int intValue(String name, int defaultValue) {
        String value = values.get(name);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    public String stringValue(String name, String defaultValue) {
        String value = values.get(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    public Path pathValue(String name) {
        String value = values.get(name);
        return value == null || value.trim().isEmpty() ? null : Paths.get(value);
    }

    public Path bootstrapJar() {
        return pathValue("bootstrapJar");
    }
}
