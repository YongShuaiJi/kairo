package com.example.runtimemock.agent.server;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentLaunchConfig {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Map<String, String> values;
    private final String token;

    private AgentLaunchConfig(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(values);
        this.token = stringValue("token", generateToken());
    }

    public static AgentLaunchConfig parse(String args) {
        Map<String, String> values = new LinkedHashMap<>();
        if (args != null && !args.isBlank()) {
            for (String part : args.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                int separator = part.indexOf('=');
                if (separator < 0) {
                    values.put(part.trim(), "true");
                } else {
                    values.put(part.substring(0, separator).trim(), part.substring(separator + 1).trim());
                }
            }
        }
        return new AgentLaunchConfig(values);
    }

    public String host() {
        String host = stringValue("host", "127.0.0.1");
        if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("Agent host must be loopback");
        }
        return host;
    }

    public int port() {
        return intValue("port", 18080);
    }

    public String token() {
        return token;
    }

    public Duration tokenTtl() {
        return Duration.ofMinutes(longValue("tokenTtlMinutes", 15L));
    }

    public Path registrationDir() {
        return pathValue("registrationDir");
    }

    public Path tokenFile() {
        return pathValue("tokenFile");
    }

    public boolean platformPollingEnabled() {
        return platformUrl() != null && platformAgentId() != null;
    }

    public boolean platformRegistrationEnabled() {
        return platformUrl() != null
                && (platformApplicationId() != null
                || (platformProjectName() != null && platformApplicationName() != null));
    }

    public String platformUrl() {
        String value = stringValue("platformUrl", null);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public String platformAgentId() {
        return stringValue("platformAgentId", null);
    }

    public String platformApplicationId() {
        return stringValue("platformApplicationId", null);
    }

    public String platformProjectName() {
        return stringValue("platformProjectName", null);
    }

    public String platformApplicationName() {
        return stringValue("platformApplicationName", null);
    }

    public String platformEnvironmentId() {
        return stringValue("platformEnvironmentId", null);
    }

    public String platformToken() {
        return stringValue("platformToken", "");
    }

    public long platformPollIntervalMillis() {
        return longValue("platformPollIntervalMillis", 1000L);
    }

    public long platformCommandLeaseSeconds() {
        return longValue("platformCommandLeaseSeconds", 30L);
    }

    public int recordingQueueCapacity() {
        return intValue("recordingQueueCapacity", 10_000);
    }

    public int recordingBatchSize() {
        return intValue("recordingBatchSize", 100);
    }

    public long recordingFlushIntervalMillis() {
        return longValue("recordingFlushIntervalMillis", 500L);
    }

    public AgentLaunchConfig withPlatformAgentId(String agentId) {
        Map<String, String> next = new LinkedHashMap<>(values);
        next.put("platformAgentId", agentId);
        return new AgentLaunchConfig(next);
    }

    private String stringValue(String name, String defaultValue) {
        String value = values.get(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private int intValue(String name, int defaultValue) {
        String value = values.get(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private long longValue(String name, long defaultValue) {
        String value = values.get(name);
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value);
    }

    private Path pathValue(String name) {
        String value = values.get(name);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
