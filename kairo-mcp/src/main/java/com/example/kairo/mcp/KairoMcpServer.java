package com.example.kairo.mcp;

import com.example.kairo.api.build.KairoBuildVersion;
import com.example.kairo.sdk.KairoClient;
import com.example.kairo.sdk.KairoClientConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kairo MCP server (V1.6 §5.4). Speaks JSON-RPC 2.0 over stdio and exposes
 * Platform automation tools via {@link KairoClient}.
 *
 * <p>Main entry point: {@link #main(String[])}.
 * Test entry point: {@link #handle(String)}.
 */
public class KairoMcpServer {

    private final KairoClient client;
    private final ObjectMapper mapper;

    public KairoMcpServer(KairoClient client) {
        this.client = client;
        this.mapper = new ObjectMapper();
    }

    /** V1.7 M5-A §12.1: the {@code --version} banner line, sourced from the shared build resolver. */
    static String versionBanner() {
        return "kairo-mcp " + KairoBuildVersion.resolve();
    }

    public static void main(String[] args) {
        // V1.7 M5-A §12.1: stable --version surface that works without credentials or network.
        if (args.length > 0 && "--version".equals(args[0])) {
            System.out.println(versionBanner());
            return;
        }
        KairoMcpConfig config = KairoMcpConfig.load();
        if (config.token() == null || config.token().isBlank()) {
            System.err.println("ERROR: KAIRO_TOKEN is not set. Please set the KAIRO_TOKEN environment variable or add token to ~/.kairo/credentials.");
            System.exit(1);
        }
        KairoClient client = new KairoClient(
                new KairoClientConfig(config.baseUrl(), config.token())
                        .source("mcp")
        );
        KairoMcpServer server = new KairoMcpServer(client);
        server.runStdio();
    }

    public void runStdio() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        reader.lines().forEach(line -> {
            if (line.isBlank()) {
                return;
            }
            try {
                String response = handle(line);
                if (response != null) {
                    System.out.println(response);
                    System.out.flush();
                }
            } catch (Exception e) {
                System.err.println("Handler error: " + e.getMessage());
            }
        });
    }

    public String handle(String jsonRequest) {
        Map<String, Object> req;
        try {
            req = mapper.readValue(jsonRequest, Map.class);
        } catch (JsonProcessingException e) {
            return error(null, -32700, "Parse error");
        }

        Object id = req.get("id");
        String method = (String) req.get("method");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = req.get("params") instanceof Map
                ? (Map<String, Object>) req.get("params")
                : Map.of();

        try {
            if (method == null) {
                return error(id, -32600, "Invalid request");
            }
            switch (method) {
                case "initialize" -> {
                    return success(id, initialize());
                }
                case "tools/list" -> {
                    return success(id, listTools());
                }
                case "tools/call" -> {
                    return success(id, callTool(params));
                }
                default -> {
                    return error(id, -32601, "Method not found: " + method);
                }
            }
        } catch (Exception e) {
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private Map<String, Object> initialize() {
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "kairo-mcp");
        serverInfo.put("version", KairoBuildVersion.resolve());

        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("tools", Map.of());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("serverInfo", serverInfo);
        result.put("capabilities", caps);
        return result;
    }

    private Map<String, Object> listTools() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(tool("whoami", "Get the current authenticated user identity.",
                List.of(), Map.of()));

        tools.add(tool("discover_targets", "Search for enhancement targets by application, environment and query.",
                List.of("applicationId", "environmentId", "query"),
                Map.of(
                        "applicationId", prop("string", "Application ID"),
                        "environmentId", prop("string", "Environment ID"),
                        "query", prop("string", "Search query")
                )));

        tools.add(tool("resolve_targets", "Resolve targets for a session returning a compact context bundle.",
                List.of("sessionId", "query"),
                Map.of(
                        "sessionId", prop("string", "Automation session ID"),
                        "query", prop("string", "Query to resolve targets"),
                        "environmentId", prop("string", "Optional environment ID")
                )));

        tools.add(tool("validate_script", "Validate a script within an automation session.",
                List.of("sessionId", "script"),
                Map.of(
                        "sessionId", prop("string", "Automation session ID"),
                        "script", prop("string", "Script source to validate")
                )));

        tools.add(tool("preview_enhancement", "Preview the impact of enhancing a target (preview-first tool).",
                List.of("sessionId", "target"),
                Map.of(
                        "sessionId", prop("string", "Automation session ID"),
                        "target", prop("object", "Target object to preview")
                )));

        tools.add(tool("trial_enhancement", "Run a trial enhancement. Requires a preview token and preview revision from preview_enhancement.",
                List.of("sessionId", "target", "script", "previewToken", "previewRevision"),
                Map.of(
                        "sessionId", prop("string", "Automation session ID"),
                        "target", prop("object", "Target object"),
                        "script", prop("string", "Script source"),
                        "capabilityProfile", prop("string", "Optional capability profile"),
                        "ttlMillis", prop("integer", "Optional TTL in milliseconds"),
                        "maxHits", prop("integer", "Optional max hits"),
                        "previewToken", prop("string", "Preview token from preview_enhancement"),
                        "previewRevision", prop("integer", "Preview revision from preview_enhancement")
                )));

        tools.add(tool("promote_trial", "Promote a trial script session to production.",
                List.of("sessionId", "scriptSessionId"),
                Map.of(
                        "sessionId", prop("string", "Automation session ID"),
                        "scriptSessionId", prop("string", "Script session ID to promote")
                )));

        tools.add(tool("revert_session", "One-click revert an automation session.",
                List.of("sessionId"),
                Map.of(
                        "sessionId", prop("string", "Automation session ID to revert")
                )));

        tools.add(tool("observe_operation", "Get operation details by ID.",
                List.of("operationId"),
                Map.of(
                        "operationId", prop("string", "Operation ID")
                )));

        tools.add(tool("session_events", "Get events for an automation session.",
                List.of("sessionId"),
                Map.of(
                        "sessionId", prop("string", "Automation session ID")
                )));

        return Map.of("tools", tools);
    }

    private Map<String, Object> callTool(Map<String, Object> params) throws Exception {
        String name = (String) params.get("name");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = params.get("arguments") instanceof Map
                ? (Map<String, Object>) params.get("arguments")
                : Map.of();

        Object result;
        switch (name) {
            case "whoami" -> result = client.whoAmI();
            case "discover_targets" -> result = client.searchTargets(
                    str(args, "applicationId"),
                    str(args, "environmentId"),
                    str(args, "query"));
            case "resolve_targets" -> result = client.resolveTargets(
                    str(args, "sessionId"),
                    str(args, "query"),
                    strOrNull(args, "environmentId"));
            case "validate_script" -> result = client.validateScript(
                    str(args, "sessionId"),
                    str(args, "script"));
            case "preview_enhancement" -> result = client.preview(
                    str(args, "sessionId"),
                    obj(args, "target"));
            case "trial_enhancement" -> {
                String previewToken = str(args, "previewToken");
                if (previewToken == null || previewToken.isBlank()) {
                    return toolError("PREVIEW_REQUIRED", "trial_enhancement requires previewToken and previewRevision. Call preview_enhancement first.");
                }
                Object previewRevisionObj = args.get("previewRevision");
                if (previewRevisionObj == null) {
                    return toolError("PREVIEW_REQUIRED", "trial_enhancement requires previewToken and previewRevision. Call preview_enhancement first.");
                }
                long previewRevision = previewRevisionObj instanceof Number n
                        ? n.longValue()
                        : Long.parseLong(previewRevisionObj.toString());
                Map<String, Object> trialResult = client.trial(
                        str(args, "sessionId"),
                        obj(args, "target"),
                        str(args, "script"),
                        strOrNull(args, "capabilityProfile"),
                        longOrDefault(args, "ttlMillis", 600_000L),
                        longOrDefault(args, "maxHits", 100L),
                        UUID.randomUUID().toString());
                trialResult = new LinkedHashMap<>(trialResult);
                trialResult.put("previewToken", previewToken);
                trialResult.put("previewRevision", previewRevision);
                result = trialResult;
            }
            case "promote_trial" -> result = client.promote(
                    str(args, "sessionId"),
                    str(args, "scriptSessionId"));
            case "revert_session" -> result = client.revertSession(str(args, "sessionId"));
            case "observe_operation" -> result = client.getOperation(str(args, "operationId"));
            case "session_events" -> result = client.sessionEvents(str(args, "sessionId"));
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        }

        String text = mapper.writeValueAsString(result);
        return Map.of("content", List.of(Map.of("type", "text", "text", text)));
    }

    private Map<String, Object> toolError(String code, String message) {
        String text;
        try {
            text = mapper.writeValueAsString(Map.of("error", code, "message", message));
        } catch (JsonProcessingException e) {
            text = "{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}";
        }
        return Map.of("content", List.of(Map.of("type", "text", "text", text)));
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static String strOrNull(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        return Map.of();
    }

    private static long longOrDefault(Map<String, Object> map, String key, long defaultValue) {
        Object v = map.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String success(Object id, Object result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        try {
            return mapper.writeValueAsString(resp);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String error(Object id, int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        resp.put("error", err);
        try {
            return mapper.writeValueAsString(resp);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Map<String, Object> tool(String name, String description, List<String> required,
                                      Map<String, Map<String, Object>> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", schema);
        return t;
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }
}
