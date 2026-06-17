package com.example.runtimemock.ops;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class OpsCommand {

    private OpsCommand() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int execute(String[] args) throws IOException, InterruptedException {
        try {
            OpsOptions options = OpsOptions.parse(args);
            HttpResponse<String> response = send(options);
            System.out.println(response.body());
            return response.statusCode() >= 200 && response.statusCode() < 300 ? 0 : 2;
        } catch (IllegalArgumentException e) {
            System.err.println("{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return 64;
        }
    }

    private static HttpResponse<String> send(OpsOptions options) throws IOException, InterruptedException {
        RequestSpec requestSpec = requestSpec(options);
        HttpRequest.Builder builder = HttpRequest.newBuilder(options.baseUrl().resolve(requestSpec.path()))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("X-Actor", "runtime-mock-ops");
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

    private record RequestSpec(String method, String path, String body) {
    }
}
