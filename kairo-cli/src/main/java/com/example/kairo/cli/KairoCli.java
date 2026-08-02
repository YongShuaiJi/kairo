package com.example.kairo.cli;

import com.example.kairo.api.automation.AutomationSession;
import com.example.kairo.api.error.ApiError;
import com.example.kairo.api.support.SupportBundleWriter;
import com.example.kairo.cli.bundle.DiagnoseSupportBundle;
import com.example.kairo.sdk.KairoApiException;
import com.example.kairo.sdk.KairoClient;
import com.example.kairo.sdk.KairoClientConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line client for the Kairo Platform V1 API (V1.6 §5.4).
 *
 * <p>Covers the full AI/automation lifecycle: login/config, discover, validate,
 * preview, trial, promote, observe and revert. Uses the official {@link KairoClient}
 * SDK — never bypasses the Platform API.
 */
public class KairoCli {

    private final PrintStream out;
    private final PrintStream err;
    private final ObjectMapper mapper;
    private final Path credentialsPath;

    public KairoCli(PrintStream out, PrintStream err) {
        this(out, err, Path.of(System.getProperty("user.home"), ".kairo", "credentials"));
    }

    KairoCli(PrintStream out, PrintStream err, Path credentialsPath) {
        this.out = out;
        this.err = err;
        this.credentialsPath = credentialsPath;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public static void main(String[] args) {
        int code = new KairoCli(System.out, System.err).run(args);
        System.exit(code);
    }

    public int run(String[] args) {
        if (args.length == 0) {
            printError("MISSING_COMMAND", "No command provided");
            return 1;
        }
        String cmd = args[0];
        ParsedArgs pa = parseArgs(args, 1);
        try {
            return dispatch(cmd, pa);
        } catch (KairoApiException e) {
            ApiError error = e.error();
            if (error == null) {
                error = ApiError.of(e.code(), e.getMessage(),
                        com.example.kairo.api.error.ErrorCategory.INTERNAL, false);
            }
            try {
                err.println(mapper.writeValueAsString(error));
            } catch (IOException io) {
                err.println("{\"code\":\"" + e.code() + "\",\"message\":\""
                        + e.getMessage().replace("\"", "\\\"") + "\"}");
            }
            return 1;
        } catch (Exception e) {
            printError("CLI_ERROR", e.getMessage());
            return 1;
        }
    }

    private void printError(String code, String message) {
        err.println("{\"code\":\"" + code + "\",\"message\":\""
                + (message == null ? "" : message.replace("\"", "\\\"")) + "\"}");
    }

    private int dispatch(String cmd, ParsedArgs pa) throws Exception {
        switch (cmd) {
            case "login" -> {
                String baseUrl = requireFlag(pa, "--base-url");
                String token = requireFlag(pa, "--token");
                Files.createDirectories(credentialsPath.getParent());
                Map<String, String> creds = Map.of("baseUrl", baseUrl, "token", token);
                Files.writeString(credentialsPath, mapper.writeValueAsString(creds));
                out.println(mapper.writeValueAsString(Map.of("ok", true)));
                return 0;
            }
            case "whoami" -> {
                KairoClient client = createClient(pa);
                out.println(mapper.writeValueAsString(client.whoAmI()));
                return 0;
            }
            case "discover" -> {
                KairoClient client = createClient(pa);
                String app = requireFlag(pa, "--app");
                String env = requireFlag(pa, "--env");
                String query = pa.flags.get("--query");
                out.println(mapper.writeValueAsString(client.searchTargets(app, env, query)));
                return 0;
            }
            case "session" -> {
                String sub = pa.positional.isEmpty() ? "" : pa.positional.get(0);
                if (!"create".equals(sub)) {
                    printError("UNKNOWN_SUBCOMMAND", "Unknown subcommand: " + sub);
                    return 1;
                }
                KairoClient client = createClient(pa);
                String app = requireFlag(pa, "--app");
                String env = requireFlag(pa, "--env");
                String profile = pa.flags.getOrDefault("--profile", "SAFE");
                long ttl = parseLong(pa.flags.getOrDefault("--ttl-ms", "600000"), "--ttl-ms");
                Map<String, Object> me = client.whoAmI();
                String caller = String.valueOf(me.getOrDefault("subject", "cli"));
                long epochMinute = Instant.now().getEpochSecond() / 60;
                String idem = "cli-session:" + caller + ":" + app + ":" + env + ":" + epochMinute;
                AutomationSession session = client.createAutomationSession(
                        caller, "cli", app, env, profile, ttl, idem);
                out.println(mapper.writeValueAsString(session));
                return 0;
            }
            case "resolve" -> {
                KairoClient client = createClient(pa);
                String sessionId = requireFlag(pa, "--session");
                String query = pa.flags.get("--query");
                out.println(mapper.writeValueAsString(client.resolveTargets(sessionId, query, null)));
                return 0;
            }
            case "validate" -> {
                KairoClient client = createClient(pa);
                String sessionId = requireFlag(pa, "--session");
                String script = readFileOrStdin(requireFlag(pa, "--script"));
                out.println(mapper.writeValueAsString(client.validateScript(sessionId, script)));
                return 0;
            }
            case "preview" -> {
                KairoClient client = createClient(pa);
                String sessionId = requireFlag(pa, "--session");
                String targetJson = readFileOrStdin(requireFlag(pa, "--target"));
                @SuppressWarnings("unchecked")
                Map<String, Object> target = mapper.readValue(targetJson, Map.class);
                out.println(mapper.writeValueAsString(client.preview(sessionId, target)));
                return 0;
            }
            case "trial" -> {
                KairoClient client = createClient(pa);
                String sessionId = requireFlag(pa, "--session");
                String targetJson = readFileOrStdin(requireFlag(pa, "--target"));
                String script = readFileOrStdin(requireFlag(pa, "--script"));
                String profile = pa.flags.getOrDefault("--profile", "SAFE");
                long ttl = parseLong(pa.flags.getOrDefault("--ttl-ms", "600000"), "--ttl-ms");
                long maxHits = parseLong(pa.flags.getOrDefault("--max-hits", "1000"), "--max-hits");
                @SuppressWarnings("unchecked")
                Map<String, Object> target = mapper.readValue(targetJson, Map.class);
                out.println(mapper.writeValueAsString(
                        client.trial(sessionId, target, script, profile, ttl, maxHits, null)));
                return 0;
            }
            case "promote" -> {
                KairoClient client = createClient(pa);
                String sessionId = requireFlag(pa, "--session");
                String scriptSessionId = requireFlag(pa, "--script-session");
                out.println(mapper.writeValueAsString(client.promote(sessionId, scriptSessionId)));
                return 0;
            }
            case "revert" -> {
                KairoClient client = createClient(pa);
                String sessionId = requireFlag(pa, "--session");
                out.println(mapper.writeValueAsString(client.revertSession(sessionId)));
                return 0;
            }
            case "observe" -> {
                KairoClient client = createClient(pa);
                String opId = requireFlag(pa, "--operation");
                out.println(mapper.writeValueAsString(client.getOperation(opId)));
                return 0;
            }
            case "operations" -> {
                KairoClient client = createClient(pa);
                String status = pa.flags.get("--status");
                int limit = (int) parseLong(pa.flags.getOrDefault("--limit", "20"), "--limit");
                out.println(mapper.writeValueAsString(client.listOperations(status, limit)));
                return 0;
            }
            case "events" -> {
                KairoClient client = createClient(pa);
                String sessionId = requireFlag(pa, "--session");
                out.println(mapper.writeValueAsString(client.sessionEvents(sessionId)));
                return 0;
            }
            case "diagnose" -> {
                // V1.7 M4-C §11.3: bounded read-only support bundle. Validate args up front (positive,
                // capped at the 20 MiB hard maximum) and emit only fixed error code + message on failure.
                try {
                    Path output = Path.of(requireFlag(pa, "--output"));
                    long timeoutMs = parsePositiveLong(pa.flags.getOrDefault("--timeout-ms", "30000"), "--timeout-ms");
                    long maxBytes = Math.min(parsePositiveLong(pa.flags.getOrDefault("--max-size-bytes",
                            String.valueOf(SupportBundleWriter.DEFAULT_SIZE_BUDGET_BYTES)), "--max-size-bytes"),
                            SupportBundleWriter.DEFAULT_SIZE_BUDGET_BYTES);
                    KairoClient client = createClient(pa);
                    return new DiagnoseSupportBundle(client, mapper, out, err, output, timeoutMs, maxBytes).run();
                } catch (IllegalArgumentException e) {
                    printError("INVALID_ARGUMENT", "invalid command arguments");
                    return 64;
                } catch (Exception e) {
                    printError("BUNDLE_FAILED", "diagnose failed; no bundle written");
                    return 70;
                }
            }
            default -> {
                printError("UNKNOWN_COMMAND", "Unknown command: " + cmd);
                return 1;
            }
        }
    }

    private KairoClient createClient(ParsedArgs pa) throws IOException {
        String baseUrl = pa.flags.get("--base-url");
        String token = pa.flags.get("--token");
        if (baseUrl == null) {
            baseUrl = System.getenv("KAIRO_PLATFORM_URL");
        }
        if (token == null) {
            token = System.getenv("KAIRO_TOKEN");
        }
        if (baseUrl == null || token == null) {
            if (Files.exists(credentialsPath)) {
                String json = Files.readString(credentialsPath);
                @SuppressWarnings("unchecked")
                Map<String, Object> creds = mapper.readValue(json, Map.class);
                if (baseUrl == null) {
                    baseUrl = String.valueOf(creds.get("baseUrl"));
                }
                if (token == null) {
                    token = String.valueOf(creds.get("token"));
                }
            }
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "base-url is required (flag, env KAIRO_PLATFORM_URL, or ~/.kairo/credentials)");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "token is required (flag, env KAIRO_TOKEN, or ~/.kairo/credentials)");
        }
        return new KairoClient(new KairoClientConfig(baseUrl, token).source("cli"));
    }

    private String readFileOrStdin(String path) throws IOException {
        if ("-".equals(path)) {
            return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return Files.readString(Path.of(path));
    }

    private long parseLong(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number: " + value);
        }
    }

    private long parsePositiveLong(String value, String name) {
        long parsed = parseLong(value, name);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private String requireFlag(ParsedArgs pa, String name) {
        String value = pa.flags.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required flag: " + name);
        }
        return value;
    }

    static ParsedArgs parseArgs(String[] args, int start) {
        List<String> positional = new ArrayList<>();
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = start; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    flags.put(arg, args[++i]);
                } else {
                    flags.put(arg, "true");
                }
            } else if (arg.startsWith("-")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    flags.put(arg, args[++i]);
                } else {
                    flags.put(arg, "true");
                }
            } else {
                positional.add(arg);
            }
        }
        return new ParsedArgs(positional, flags);
    }

    record ParsedArgs(List<String> positional, Map<String, String> flags) {}
}
