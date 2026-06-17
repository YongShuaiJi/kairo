package com.example.runtimemock.ops;

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

    private void validate() {
        switch (command) {
            case "status" -> {
            }
            case "disable-rule", "remove-rule" -> require("rule-id");
            case "reset-class" -> {
                require("class-id");
                require("reason");
            }
            case "disable-all", "reset-all", "shutdown-agent" -> require("reason");
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
