package com.example.kairo.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * MCP server configuration (V1.6 §5.4). Loaded from environment variables
 * {@code KAIRO_PLATFORM_URL} and {@code KAIRO_TOKEN}, falling back to
 * {@code ~/.kairo/credentials}.
 */
public record KairoMcpConfig(String baseUrl, String token) {

    public static KairoMcpConfig load() {
        String baseUrl = System.getenv("KAIRO_PLATFORM_URL");
        String token = System.getenv("KAIRO_TOKEN");

        if (baseUrl == null || token == null) {
            Path creds = Path.of(System.getProperty("user.home"), ".kairo", "credentials");
            if (Files.exists(creds)) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> json = mapper.readValue(Files.readString(creds), Map.class);
                    if (baseUrl == null) {
                        Object bu = json.get("baseUrl");
                        baseUrl = bu == null ? null : bu.toString();
                    }
                    if (token == null) {
                        Object t = json.get("token");
                        token = t == null ? null : t.toString();
                    }
                } catch (IOException e) {
                    // ignore, fall through
                }
            }
        }

        return new KairoMcpConfig(
                baseUrl == null ? "" : baseUrl,
                token == null ? "" : token
        );
    }
}
