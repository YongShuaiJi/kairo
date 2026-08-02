package com.example.kairo.ops;

import com.example.kairo.api.support.SupportBundleWriter;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OpsOptions {

    private final String command;
    private final Map<String, String> values;

    private OpsOptions(String command, Map<String, String> values) {
        this.command = command;
        this.values = Map.copyOf(values);
    }

    public static OpsOptions parse(String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException("Command is required");
        }
        String command = args[0];
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            if (key.isBlank()) {
                throw new IllegalArgumentException("Blank option name");
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                values.put(key, "true");
            } else {
                values.put(key, args[++i]);
            }
        }
        OpsOptions options = new OpsOptions(command, values);
        options.validate();
        return options;
    }

    public String command() {
        return command;
    }

    public URI baseUrl() {
        String value = values.getOrDefault("url", "http://127.0.0.1:18080");
        return URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
    }

    public String token() {
        return values.get("token");
    }

    public String ruleId() {
        return values.get("rule-id");
    }

    public String classId() {
        return values.get("class-id");
    }

    public String reason() {
        return values.get("reason");
    }

    public String eventId() {
        return values.get("event");
    }

    /** V1.7 M4-C &sect;11.3: output path for {@code support-bundle}. */
    public String output() {
        return values.get("output");
    }

    /** V1.7 M4-C &sect;11.3: whole-operation timeout (ms), default 30 s. Must be positive. */
    public long timeoutMs() {
        return parsePositiveLong(values.getOrDefault("timeout-ms", "30000"), "timeout-ms");
    }

    /** V1.7 M4-C &sect;11.3: archive size budget (bytes), capped at the 20 MiB hard maximum. Must be positive. */
    public long maxSizeBytes() {
        long parsed = parsePositiveLong(values.getOrDefault("max-size-bytes",
                String.valueOf(SupportBundleWriter.DEFAULT_SIZE_BUDGET_BYTES)), "max-size-bytes");
        return Math.min(parsed, SupportBundleWriter.DEFAULT_SIZE_BUDGET_BYTES);
    }

    private long parseLong(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required for " + command);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a number");
        }
    }

    private long parsePositiveLong(String value, String name) {
        long parsed = parseLong(value, name);
        if (parsed <= 0) {
            throw new IllegalArgumentException("--" + name + " must be positive");
        }
        return parsed;
    }

    private void validate() {
        switch (command) {
            case "status" -> {
            }
            case "disable-rule", "remove-rule" -> {
                require("rule-id");
                require("reason");
                require("event");
            }
            case "reset-class" -> {
                require("class-id");
                require("reason");
                require("event");
            }
            case "disable-all", "enable-all", "reset-all", "shutdown-agent" -> {
                require("reason");
                require("event");
            }
            // V1.7 M4-C §11.3: read-only local diagnostic collection; no mutation, no reason/event.
            case "support-bundle" -> {
                require("output");
                // Validate timeout/max-size up front (positive); max-size is capped at access time.
                timeoutMs();
                maxSizeBytes();
            }
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private void require(String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + key + " is required for " + command);
        }
    }
}
