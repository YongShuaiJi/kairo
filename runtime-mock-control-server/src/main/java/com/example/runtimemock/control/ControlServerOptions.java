package com.example.runtimemock.control;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public record ControlServerOptions(
        String host,
        int port,
        URI defaultAgent,
        String defaultToken
) {
    public static ControlServerOptions parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + arg);
            }
            values.put(arg.substring(2), args[++i]);
        }
        return new ControlServerOptions(
                values.getOrDefault("host", "127.0.0.1"),
                Integer.parseInt(values.getOrDefault("port", "18180")),
                URI.create(values.getOrDefault("agent", "http://127.0.0.1:18080")),
                values.getOrDefault("token", "")
        );
    }
}
