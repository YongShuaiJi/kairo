package com.example.kairo.ops;

import com.example.kairo.api.build.KairoBuildVersion;
import com.example.kairo.ops.bundle.OpsSupportBundle;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class OpsCommand {

    private OpsCommand() {
    }

    public static void main(String[] args) {
        int exitCode = execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int execute(String[] args) {
        return execute(args, System.out, System.err);
    }

    /** Testable entry point that writes status/errors to the given streams. */
    public static int execute(String[] args, PrintStream out, PrintStream err) {
        // V1.7 M5-A §12.1: stable --version surface that works without credentials or network.
        if (args.length > 0 && "--version".equals(args[0])) {
            out.println("kairo-ops " + KairoBuildVersion.resolve());
            return 0;
        }
        OpsOptions options;
        try {
            options = OpsOptions.parse(args);
        } catch (IllegalArgumentException e) {
            // Fixed code + message only; never echo raw input (a misplaced secret could appear as a value).
            err.println("{\"error\":\"INVALID_ARGUMENT\",\"message\":\"invalid command arguments\"}");
            return 64;
        }
        // V1.7 M4-C §11.3: support-bundle is a read-only diagnostic collector, not a mutation;
        // it does not go through the mutation send/audit path.
        if ("support-bundle".equals(options.command())) {
            try {
                return new OpsSupportBundle(options, out, err).run();
            } catch (Exception e) {
                err.println("{\"error\":\"BUNDLE_FAILED\",\"message\":\"support bundle failed; no bundle written\"}");
                return 70;
            }
        }
        try {
            HttpResponse<String> response = send(options);
            out.println(response.body());
            appendAudit(options, response.statusCode(), response.body());
            return response.statusCode() >= 200 && response.statusCode() < 300 ? 0 : 2;
        } catch (IllegalArgumentException e) {
            err.println("{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return 64;
        } catch (IOException e) {
            err.println("{\"error\":\"NETWORK_ERROR\",\"message\":\"" + escape(e.getMessage()) + "\"}");
            return 69;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("{\"error\":\"INTERRUPTED\"}");
            return 130;
        }
    }

    private static HttpResponse<String> send(OpsOptions options) throws IOException, InterruptedException {
        RequestSpec requestSpec = requestSpec(options);
        HttpRequest.Builder builder = HttpRequest.newBuilder(options.baseUrl().resolve(requestSpec.path()))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("X-Actor", "kairo-ops");
        if (options.token() != null && !options.token().isBlank()) {
            builder.header("X-Agent-Token", options.token());
        }
        if (requestSpec.body().isBlank()) {
            builder.method(requestSpec.method(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(requestSpec.method(),
                    HttpRequest.BodyPublishers.ofString(requestSpec.body(), StandardCharsets.UTF_8));
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static RequestSpec requestSpec(OpsOptions options) {
        return switch (options.command()) {
            case "status" -> new RequestSpec("GET", "/v1/status", "");
            case "disable-rule" -> new RequestSpec("POST", "/v1/rules/" + encode(options.ruleId()) + "/disable",
                    body(options));
            case "disable-all" -> new RequestSpec("POST", "/v1/agent/disable-all", body(options));
            case "enable-all" -> new RequestSpec("POST", "/v1/agent/enable-all", body(options));
            case "remove-rule" -> new RequestSpec("DELETE", "/v1/rules/" + encode(options.ruleId()), body(options));
            case "reset-class" -> new RequestSpec("POST", "/v1/agent/reset-class",
                    "{\"classId\":\"" + escape(options.classId()) + "\",\"reason\":\""
                            + escape(options.reason()) + "\",\"eventId\":\"" + escape(options.eventId()) + "\"}");
            case "reset-all" -> new RequestSpec("POST", "/v1/agent/reset-all", body(options));
            case "shutdown-agent" -> new RequestSpec("POST", "/v1/agent/shutdown", body(options));
            default -> throw new IllegalArgumentException("Unknown command: " + options.command());
        };
    }

    private static String body(OpsOptions options) {
        return "{\"reason\":\"" + escape(options.reason()) + "\",\"eventId\":\""
                + escape(options.eventId()) + "\"}";
    }

    private static String encode(String value) {
        return value.replace("/", "%2F");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void appendAudit(OpsOptions options, int status, String response) throws IOException {
        Path audit = auditPath();
        Path parent = audit.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String line = "{\"timestamp\":\"" + Instant.now() + "\",\"command\":\""
                + escape(options.command()) + "\",\"eventId\":\"" + escape(options.eventId())
                + "\",\"reason\":\"" + escape(options.reason()) + "\",\"status\":" + status
                + ",\"response\":\"" + escape(response) + "\"}" + System.lineSeparator();
        Files.writeString(audit, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * The local audit file. Defaults to {@code ~/.kairo/ops-audit.jsonl}; overridable with the
     * {@code kairo.ops.audit.path} system property or {@code KAIRO_OPS_AUDIT_PATH} environment
     * variable so deployments (and tests) can redirect the audit without changing the home dir.
     */
    static Path auditPath() {
        String override = System.getProperty("kairo.ops.audit.path");
        if (override == null || override.isBlank()) {
            override = System.getenv("KAIRO_OPS_AUDIT_PATH");
        }
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".kairo", "ops-audit.jsonl");
    }

    private record RequestSpec(String method, String path, String body) {
    }
}
