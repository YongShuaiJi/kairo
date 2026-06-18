package com.example.runtimemock.control;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public record ControlServerOptions(
        String host,
        int port,
        URI defaultAgent,
        String defaultToken,
        String controlToken
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
        if (values.containsKey("token")) {
            throw new IllegalArgumentException("--token is not allowed; use --token-file or RUNTIME_MOCK_AGENT_TOKEN");
        }
        String host = values.getOrDefault("host", "127.0.0.1");
        if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("Control server must bind to loopback");
        }
        URI agent = URI.create(values.getOrDefault("agent", "http://127.0.0.1:18080"));
        if (!isLoopback(agent)) {
            throw new IllegalArgumentException("Default agent URI must use loopback HTTP");
        }
        return new ControlServerOptions(
                host,
                Integer.parseInt(values.getOrDefault("port", "18180")),
                agent,
                token(values),
                randomToken()
        );
    }

    private static String token(Map<String, String> values) {
        String tokenFile = values.get("token-file");
        if (tokenFile != null) {
            try {
                return Files.readString(Path.of(tokenFile)).trim();
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot read --token-file", e);
            }
        }
        return System.getenv().getOrDefault("RUNTIME_MOCK_AGENT_TOKEN", "");
    }

    private static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        return "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host));
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
