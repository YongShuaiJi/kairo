package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The real M3-C Spring Boot target executor. Launches a <strong>genuinely
 * independent</strong> Spring Boot 3 executable-jar target JVM, drives the real Kairo
 * agent load path (premain for C03, the repository {@code kairo-attach-cli} external
 * attach entry for C04), and proves registration/publication/invocation/unload against
 * the agent's real loopback HTTP API and the application's real HTTP endpoint.
 *
 * <p>Nothing here is hard-coded: the child PID is cross-checked against the target's
 * own self-reported PID (from {@code GET /jvm}), the target JDK comes from the agent
 * running in the target JVM, the launch/attach commands and the application {@code score}
 * values all come from the real target process, and assertions are derived from target
 * behavior. The executor always cleans up the child and bounds every wait.
 *
 * <p>Mirrors {@link RealPlainJavaTargetExecutor}; kept separate so the accepted M3-B
 * executor stays untouched. The two share no private helpers - duplication is bounded
 * to this one M3-C work package.
 */
final class RealSpringBootTargetExecutor implements SpringBootTargetExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public SpringBootExecutionOutcome execute(CompatibilityScenario scenario, RealExecEnv env,
                                             Path targetJdkHome, Path execJar) {
        Path runDir = env.workDir.resolve("run-" + scenario.id() + "-" + env.runnerPid);
        Path stdoutArtifact = runDir.resolve("target.stdout");
        Path stderrArtifact = runDir.resolve("target.stderr");
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        String[] failure = {""};   // mutable holder for the failure reason
        String[] targetJdk = {""};
        int[] childPid = {0};
        String launchCommand = "";
        String attachCommand = "";
        Process target = null;
        Thread stdoutThread = null;
        Thread stderrThread = null;
        try {
            Files.createDirectories(runDir);
            // Materialize both evidence files even when the target emits no output on one
            // stream. A path alone must never masquerade as durable evidence.
            Files.writeString(stdoutArtifact, "", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(stderrArtifact, "", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

            // 1. Allocate two distinct loopback ports while both sockets are held open.
            // Two independent bind/close calls can legally receive the same ephemeral
            // port and make the fixture fail intermittently before either server starts.
            int[] ports = allocateDistinctLoopbackPorts();
            int agentPort = ports[0];
            int appPort = ports[1];
            String token = "kairo-compat-" + scenario.id() + "-" + env.runnerPid + "-" + agentPort;

            // 2. Build the independent target launch command.
            Path targetJava = targetJdkHome.resolve("bin").resolve("java");
            if (!Files.isExecutable(targetJava)) {
                failure[0] = "target java not executable: " + targetJava;
                return outcome(false, 0, env, "", "", "", stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            List<String> targetCmd = new ArrayList<>();
            targetCmd.add(targetJava.toString());
            if (scenario.loadMode() == LoadMode.PREMAIN) {
                // C03: real -javaagent premain. coreJar is resolved explicitly so the
                // isolated bootstrap ClassLoader finds the modern core; bootstrapApiJar so
                // transformed application classes resolve the stable KairoBridge.
                String agentArgs = "coreJar=" + env.coreJar
                        + ",bootstrapJar=" + env.bootstrapApiJar
                        + ",host=127.0.0.1,port=" + agentPort + ",token=" + token;
                targetCmd.add("-javaagent:" + env.bootstrapJar + "=" + agentArgs);
            }
            targetCmd.add("-jar");
            targetCmd.add(execJar.toString());
            targetCmd.add("--server.port=" + appPort);
            launchCommand = String.join(" ", targetCmd);

            // 3. Launch the independent target JVM.
            ProcessBuilder targetPb = new ProcessBuilder(targetCmd);
            target = targetPb.start();
            long launchedPid = target.pid();
            if (launchedPid <= 0 || launchedPid > Integer.MAX_VALUE) {
                failure[0] = "launched target PID is outside the row schema range: " + launchedPid;
                return outcome(false, 0, env, "", launchCommand, "", stdoutArtifact,
                        stderrArtifact, assertions, failure[0]);
            }
            childPid[0] = (int) launchedPid;
            stdoutThread = pumpToFile(target.getInputStream(), stdoutArtifact, "kairo-sb-stdout");
            stdoutThread.setDaemon(true);
            stdoutThread.start();
            stderrThread = pumpToFile(target.getErrorStream(), stderrArtifact, "kairo-sb-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

            // 4. C04 (attach): wait for the application to come up, then attach via the real
            //    kairo-attach-cli entry. C03 (premain) waits for the agent first instead.
            if (scenario.loadMode() != LoadMode.PREMAIN) {
                boolean appUpBeforeAttach = awaitAppUp(http, appPort, env.startupTimeoutMillis);
                if (!appUpBeforeAttach) {
                    failure[0] = "Spring Boot application did not come up before attach within "
                            + env.startupTimeoutMillis + "ms";
                    return outcome(true, childPid[0], env, "", launchCommand, "",
                            stdoutArtifact, stderrArtifact, assertions, failure[0]);
                }
                Path runnerJava = Path.of(System.getProperty("java.home")).resolve("bin").resolve("java");
                List<String> attachCmd = List.of(runnerJava.toString(), "-jar", env.attachJar.toString(),
                        "--pid", Integer.toString(childPid[0]),
                        "--agent", env.bootstrapJar.toString(),
                        "--core-jar", env.coreJar.toString(),
                        "--bootstrap-jar", env.bootstrapApiJar.toString(),
                        "--token", token,
                        "--port", Integer.toString(agentPort));
                attachCommand = String.join(" ", attachCmd);
                ProcessBuilder attachPb = new ProcessBuilder(attachCmd)
                        .redirectOutput(runDir.resolve("attach.stdout").toFile())
                        .redirectError(runDir.resolve("attach.stderr").toFile());
                Process attach = attachPb.start();
                boolean attachDone = attach.waitFor(env.startupTimeoutMillis, TimeUnit.MILLISECONDS);
                int attachExit = attachDone ? attach.exitValue() : -1;
                if (!attachDone) {
                    attach.destroyForcibly();
                }
                String attachDetail = "attach exit=" + attachExit + "; cmd=" + attachCommand;
                boolean attachOk = attachDone && attachExit == 0;
                assertions.add(assertion("attach", attachOk, attachDetail));
                if (!attachOk) {
                    failure[0] = "external attach failed (" + attachDetail + ")";
                    return outcome(true, childPid[0], env, "", launchCommand, attachCommand,
                            stdoutArtifact, stderrArtifact, assertions, failure[0]);
                }
            } else {
                // C03: the agent loads during premain (before the app boots). Wait for it.
                boolean agentUp = awaitAgentHealthUp(http, agentPort, token, env.startupTimeoutMillis);
                if (!agentUp) {
                    failure[0] = "agent /health never came UP within " + env.startupTimeoutMillis + "ms";
                    return outcome(true, childPid[0], env, "", launchCommand, "",
                            stdoutArtifact, stderrArtifact, assertions, failure[0]);
                }
            }

            // 5. For C04 the agent is up after attach; for C03 it was waited above.
            if (scenario.loadMode() != LoadMode.PREMAIN) {
                boolean agentUp = awaitAgentHealthUp(http, agentPort, token, env.startupTimeoutMillis);
                if (!agentUp) {
                    failure[0] = "agent /health never came UP after attach within "
                            + env.startupTimeoutMillis + "ms";
                    return outcome(true, childPid[0], env, "", launchCommand, attachCommand,
                            stdoutArtifact, stderrArtifact, assertions, failure[0]);
                }
            }

            // 6. Cross-check the target's self-reported PID + JDK via GET /jvm. The agent
            //    runs in the target JVM, so this is the truthful target identity.
            JvmIdentity jvm = readJvm(http, agentPort, token);
            if (jvm == null || jvm.pid <= 0) {
                failure[0] = "could not read target JVM identity via /jvm";
                assertions.add(assertion("harness.pid", false, failure[0]));
                return outcome(true, childPid[0], env, "", launchCommand, attachCommand,
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            targetJdk[0] = jvm.javaVersion;
            if (jvm.pid != childPid[0]) {
                failure[0] = "target /jvm pid " + jvm.pid + " does not match launched pid " + childPid[0];
                assertions.add(assertion("harness.pid", false, failure[0]));
                return outcome(true, childPid[0], env, targetJdk[0], launchCommand, attachCommand,
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }

            // 7. Wait for the Spring Boot application HTTP endpoint (bounded). For C04 the
            //    app came up before attach; this returns immediately.
            boolean appUp = awaitAppUp(http, appPort, env.startupTimeoutMillis);
            if (!appUp) {
                failure[0] = "Spring Boot application HTTP endpoint never came up within "
                        + env.startupTimeoutMillis + "ms";
                return outcome(true, childPid[0], env, targetJdk[0], launchCommand, attachCommand,
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }

            // 8. Baseline invoke: real application HTTP call. The response carries the live
            //    baseline score (base*2), captured - not hard-coded.
            Integer baseline = getAppScore(http, appPort, env.operationTimeoutMillis);
            if (baseline == null) {
                failure[0] = "could not read baseline score from " + SpringBootFixtureTarget.APP_PATH;
                return outcome(true, childPid[0], env, targetJdk[0], launchCommand, attachCommand,
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }

            // 9. Resolve the target Spring bean method via the real agent API. For C03 this
            //    is the registration/discovery behavior; for C04 attach already proved load.
            TargetMethod tm = resolveTargetMethod(http, agentPort, token, env.operationTimeoutMillis);
            if (scenario.id().equals("C03")) {
                boolean regOk = tm != null;
                assertions.add(assertion("注册", regOk,
                        tm == null ? "OrderService not discoverable via /classes"
                                : "resolved OrderService.calculateScore via /classes + /methods"));
                if (!regOk && failure[0].isEmpty()) {
                    failure[0] = "registration/discovery failed: OrderService not found via /classes";
                }
            }

            // 10. 发布 (publish): POST /rules mocking calculateScore -> ENHANCED. For C03 the
            //     调用 behavior proves the real invocation changed; for C04 publication is
            //     proved by an actual application invocation change (folded into 发布).
            if (tm == null) {
                if (failure[0].isEmpty()) {
                    failure[0] = "could not resolve OrderService.calculateScore via /classes + /methods";
                }
                assertions.add(assertion("发布", false, failure[0]));
                assertions.add(assertion(scenario.id().equals("C04") ? "发布" : "调用", false, "no target method"));
                assertions.add(assertion("卸载", false, "no target method"));
                return outcome(true, childPid[0], env, targetJdk[0], launchCommand, attachCommand,
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            String enhanceScript = "return mock.returnValue(" + SpringBootFixtureTarget.ENHANCED_SCORE + ")";
            HttpOutcome enhanceResp = httpPost(http, agentPort, token, "/rules",
                    ruleJson(tm, enhanceScript), env.operationTimeoutMillis);
            Integer enhanced = getAppScore(http, appPort, env.operationTimeoutMillis);
            boolean enhancedOk = enhanced != null
                    && enhanced == SpringBootFixtureTarget.ENHANCED_SCORE
                    && !enhanced.equals(baseline);
            if (scenario.id().equals("C03")) {
                assertions.add(assertion("发布", enhanceResp.statusCode() == 201,
                        "POST /rules -> " + enhanceResp.statusCode() + " " + truncate(enhanceResp.body())));
                assertions.add(assertion("调用", enhancedOk,
                        "GET " + SpringBootFixtureTarget.APP_PATH + " -> score="
                                + (enhanced == null ? "<none>" : enhanced)
                                + " (expected " + SpringBootFixtureTarget.ENHANCED_SCORE + ")"));
                if (enhanceResp.statusCode() != 201 && failure[0].isEmpty()) {
                    failure[0] = "publish failed: POST /rules -> " + enhanceResp.statusCode();
                }
                if (!enhancedOk && failure[0].isEmpty()) {
                    failure[0] = "invoke did not change behavior: score=" + enhanced;
                }
            } else {
                boolean pubOk = enhanceResp.statusCode() == 201 && enhancedOk;
                assertions.add(assertion("发布", pubOk,
                        "POST /rules -> " + enhanceResp.statusCode() + "; GET "
                                + SpringBootFixtureTarget.APP_PATH + " -> score="
                                + (enhanced == null ? "<none>" : enhanced)
                                + " (expected " + SpringBootFixtureTarget.ENHANCED_SCORE + ")"));
                if (!pubOk && failure[0].isEmpty()) {
                    failure[0] = "publish+invoke failed: POST " + enhanceResp.statusCode() + ", score=" + enhanced;
                }
            }

            // 11. 卸载 (unload): DELETE /rules/{id}, then prove the application invocation
            //     result returns to the real baseline (precise unload / restoration).
            HttpOutcome deleteResp = httpDelete(http, agentPort, token, "/rules/compat-enhance",
                    env.operationTimeoutMillis);
            Integer restored = getAppScore(http, appPort, env.operationTimeoutMillis);
            boolean unloadOk = deleteResp.statusCode() == 200
                    && restored != null && restored.equals(baseline);
            assertions.add(assertion("卸载", unloadOk,
                    "DELETE /rules/compat-enhance -> " + deleteResp.statusCode() + "; GET "
                            + SpringBootFixtureTarget.APP_PATH + " -> score="
                            + (restored == null ? "<none>" : restored) + " (expected restored " + baseline + ")"));
            if (!unloadOk && failure[0].isEmpty()) {
                failure[0] = "unload did not restore baseline: DELETE " + deleteResp.statusCode()
                        + ", score=" + restored + " (expected " + baseline + ")";
            }

            // 12. Evidence assertions (carry the exact commands + artifact paths).
            assertions.add(assertion("evidence.launchCommand", true, launchCommand));
            if (!attachCommand.isEmpty()) {
                assertions.add(assertion("evidence.attachCommand", true, attachCommand));
            }
            assertions.add(assertion("evidence.stdoutArtifact", Files.isRegularFile(stdoutArtifact),
                    stdoutArtifact.toString()));
            assertions.add(assertion("evidence.stderrArtifact", Files.isRegularFile(stderrArtifact),
                    stderrArtifact.toString()));

            boolean allPassed = assertions.stream().allMatch(CompatibilityRowRunner.Assertion::passed)
                    && failure[0].isBlank();
            return outcome(true, childPid[0], env, targetJdk[0], launchCommand, attachCommand,
                    stdoutArtifact, stderrArtifact, assertions, allPassed ? "" : failure[0]);
        } catch (Exception e) {
            if (failure[0].isBlank()) {
                failure[0] = "executor error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            return outcome(childPid[0] > 0, childPid[0], env, targetJdk[0],
                    launchCommand, attachCommand, stdoutArtifact, stderrArtifact, assertions, failure[0]);
        } finally {
            // Always clean up: stop pumps, destroy the child. The Spring Boot app does not
            // self-exit, so destroyForcibly is required; the agent dies with the JVM.
            if (target != null && target.isAlive()) {
                target.destroyForcibly();
                try {
                    target.waitFor(env.operationTimeoutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (stdoutThread != null) {
                stdoutThread.interrupt();
            }
            if (stderrThread != null) {
                stderrThread.interrupt();
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private static SpringBootExecutionOutcome outcome(boolean started, int childPid, RealExecEnv env,
                                                     String targetJdk, String launchCommand, String attachCommand,
                                                     Path stdoutArtifact, Path stderrArtifact,
                                                     List<CompatibilityRowRunner.Assertion> assertions,
                                                     String failureReason) {
        boolean independent = childPid > 0 && childPid != env.runnerPid;
        return new SpringBootExecutionOutcome(started, childPid, independent, targetJdk,
                launchCommand, attachCommand, stdoutArtifact, stderrArtifact,
                new ArrayList<>(assertions), failureReason);
    }

    /** Allocates two distinct free ports by holding both bindings simultaneously. */
    private static int[] allocateDistinctLoopbackPorts() {
        try (ServerSocket first = new ServerSocket(0);
             ServerSocket second = new ServerSocket(0)) {
            return new int[]{first.getLocalPort(), second.getLocalPort()};
        } catch (Exception e) {
            throw new IllegalStateException("could not allocate distinct loopback ports", e);
        }
    }

    /** Reads the application score from GET /demo/score?base=10, with bounded retry. */
    private static Integer getAppScore(HttpClient http, int appPort, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Integer score = getAppScoreOnce(http, appPort);
            if (score != null) {
                return score;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** Single GET /demo/score?base=10 attempt; returns the score int or null. */
    private static Integer getAppScoreOnce(HttpClient http, int appPort) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + appPort + SpringBootFixtureTarget.APP_PATH))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode n = MAPPER.readTree(resp.body());
                if (n.has("score") && n.get("score").isInt()) {
                    return n.get("score").asInt();
                }
            }
        } catch (Exception ignored) {
            // not up yet / not parseable
        }
        return null;
    }

    /** Polls GET /demo/score until the application is serving (bounded). */
    private static boolean awaitAppUp(HttpClient http, int appPort, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (getAppScoreOnce(http, appPort) != null) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Polls /health until the agent loopback server is UP (bounded). */
    private static boolean awaitAgentHealthUp(HttpClient http, int port, String token, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> h = httpGet(http, port, token, "/health");
                if (h.statusCode() == 200 && h.body().contains("UP")) {
                    return true;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Reads the target JVM identity (pid + javaVersion) from GET /jvm. */
    private static JvmIdentity readJvm(HttpClient http, int port, String token) {
        try {
            HttpResponse<String> resp = httpGet(http, port, token, "/jvm");
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonNode n = MAPPER.readTree(resp.body());
            return new JvmIdentity(n.path("pid").asLong(0L), n.path("javaVersion").asText(""));
        } catch (Exception e) {
            return null;
        }
    }

    private record TargetMethod(String classId, String classLoaderId, String descriptor) {
    }

    private record JvmIdentity(long pid, String javaVersion) {
    }

    /** Resolves OrderService.calculateScore(I)I via GET /classes + GET /classes/{id}/methods. */
    private static TargetMethod resolveTargetMethod(HttpClient http, int port, String token, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> resp = httpGet(http, port, token, "/classes?keyword=OrderService");
                if (resp.statusCode() != 200) {
                    Thread.sleep(200);
                    continue;
                }
                JsonNode arr = MAPPER.readTree(resp.body());
                if (!arr.isArray()) {
                    Thread.sleep(200);
                    continue;
                }
                for (JsonNode c : arr) {
                    if (!SpringBootFixtureTarget.TARGET_CLASS_NAME.equals(c.path("className").asText())) {
                        continue;
                    }
                    String classId = c.path("classId").asText();
                    String classLoaderId = c.path("classLoaderId").asText();
                    HttpResponse<String> m = httpGet(http, port, token, "/classes/" + classId + "/methods");
                    if (m.statusCode() != 200) {
                        break;
                    }
                    JsonNode methods = MAPPER.readTree(m.body());
                    for (JsonNode mm : methods) {
                        if (SpringBootFixtureTarget.TARGET_METHOD_NAME.equals(mm.path("name").asText())
                                && SpringBootFixtureTarget.TARGET_METHOD_DESCRIPTOR.equals(
                                        mm.path("descriptor").asText())) {
                            return new TargetMethod(classId, classLoaderId,
                                    SpringBootFixtureTarget.TARGET_METHOD_DESCRIPTOR);
                        }
                    }
                }
                break;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // retry
            }
        }
        return null;
    }

    private static String ruleJson(TargetMethod tm, String script) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", "compat-enhance");
        rule.put("name", "compat-enhance");
        rule.put("classId", tm.classId());
        rule.put("className", SpringBootFixtureTarget.TARGET_CLASS_NAME);
        rule.put("classLoaderId", tm.classLoaderId());
        rule.put("methodName", SpringBootFixtureTarget.TARGET_METHOD_NAME);
        rule.put("methodDescriptor", tm.descriptor());
        rule.put("phase", "BEFORE");
        rule.put("script", script);
        rule.put("priority", 100);
        rule.put("percentage", 100);
        rule.put("failOpen", true);
        rule.put("enabled", true);
        try {
            return MAPPER.writeValueAsString(rule);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record HttpOutcome(int statusCode, String body) {
    }

    private static HttpResponse<String> httpGet(HttpClient http, int port, String token, String path)
            throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("X-Agent-Token", token)
                .GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpOutcome httpPost(HttpClient http, int port, String token, String path,
                                        String body, long timeoutMillis) {
        return httpSend(http, port, token, path, "POST", body, timeoutMillis);
    }

    private static HttpOutcome httpDelete(HttpClient http, int port, String token, String path,
                                          long timeoutMillis) {
        return httpSend(http, port, token, path, "DELETE", null, timeoutMillis);
    }

    private static HttpOutcome httpSend(HttpClient http, int port, String token, String path,
                                       String method, String body, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("X-Agent-Token", token);
                HttpRequest req;
                if (body != null) {
                    b.header("Content-Type", "application/json");
                    b.method(method, HttpRequest.BodyPublishers.ofString(body));
                } else {
                    b.method(method, HttpRequest.BodyPublishers.noBody());
                }
                req = b.build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                return new HttpOutcome(resp.statusCode(), resp.body());
            } catch (Exception e) {
                last = e;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return new HttpOutcome(-1, "send-failed: " + (last == null ? "timeout" : last.getMessage()));
    }

    private static CompatibilityRowRunner.Assertion assertion(String name, boolean passed, String detail) {
        return new CompatibilityRowRunner.Assertion(name, passed, detail);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 160 ? s : s.substring(0, 160) + "...";
    }

    private static Thread pumpToFile(InputStream in, Path file, String name) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                }
            } catch (Exception ignored) {
                // process closed
            }
        }, name);
    }
}
