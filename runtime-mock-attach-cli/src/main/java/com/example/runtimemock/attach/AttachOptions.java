package com.example.runtimemock.attach;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public record AttachOptions(
        String pid,
        Path agentJar,
        String host,
        int port,
        String token,
        Path coreJar,
        Path bootstrapJar,
        String platformUrl,
        String platformAgentId,
        String platformToken
) {
    public String agentArgs() {
        StringBuilder builder = new StringBuilder("attach=true,host=")
                .append(host)
                .append(",port=")
                .append(port)
                .append(",token=")
                .append(token);
        appendPath(builder, "coreJar", coreJar);
        appendPath(builder, "bootstrapJar", bootstrapJar);
        appendValue(builder, "platformUrl", platformUrl);
        appendValue(builder, "platformAgentId", platformAgentId);
        appendValue(builder, "platformToken", platformToken);
        return builder.toString();
    }

    static AttachOptions parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for --" + key);
            }
            values.put(key, args[++i]);
        }
        String pid = required(values, "pid");
        Path agentJar = Path.of(required(values, "agent")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(agentJar)) {
            throw new IllegalArgumentException("Agent jar does not exist: " + agentJar);
        }
        String host = values.getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(values.getOrDefault("port", "18080"));
        String token = values.getOrDefault("token", "");
        Path coreJar = optionalFile(values, "core-jar", "coreJar");
        Path bootstrapJar = optionalFile(values, "bootstrap-jar", "bootstrapJar");
        return new AttachOptions(pid, agentJar, host, port, token,
                coreJar,
                bootstrapJar,
                firstPresent(values, "platform-url", "platformUrl"),
                firstPresent(values, "platform-agent-id", "platformAgentId"),
                firstPresent(values, "platform-token", "platformToken"));
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + key + " is required");
        }
        return value;
    }

    private static Path optionalFile(Map<String, String> values, String dashedKey, String camelKey) {
        String value = firstPresent(values, dashedKey, camelKey);
        if (value == null || value.isBlank()) {
            return null;
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Jar does not exist: " + path);
        }
        return path;
    }

    private static String firstPresent(Map<String, String> values, String dashedKey, String camelKey) {
        String value = values.get(dashedKey);
        return value == null ? values.get(camelKey) : value;
    }

    private static void appendPath(StringBuilder builder, String key, Path path) {
        if (path != null) {
            appendValue(builder, key, path.toString());
        }
    }

    private static void appendValue(StringBuilder builder, String key, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(',').append(key).append('=').append(value);
        }
    }
}
