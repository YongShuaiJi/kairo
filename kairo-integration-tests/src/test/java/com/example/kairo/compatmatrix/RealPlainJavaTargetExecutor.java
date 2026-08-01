package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The real M3-B plain-Java target executor. Launches a <strong>genuinely
 * independent</strong> target JVM, drives the real Kairo agent load path
 * (premain for C01, the repository's {@code kairo-attach-cli} external
 * attach/agentmain entry for C02/C09), and proves enhance/invoke/update/unload
 * against the agent's real loopback HTTP API.
 *
 * <p>Nothing here is hard-coded: the child PID, target JDK, launch/attach
 * commands and the {@code RESULT} values all come from the real target
 * process. Assertions are derived from target behavior. The executor always
 * cleans up the child and bounds every wait.
 *
 * <p>The launch always supplies both the isolated core jar and the bootstrap API
 * jar. The latter is required for transformed application classes to resolve the
 * stable {@code KairoBridge}; omitting it would allow a rule to publish without
 * proving that transformed behavior can actually execute.
 */
final class RealPlainJavaTargetExecutor implements PlainJavaTargetExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public PlainJavaExecutionOutcome execute(CompatibilityScenario scenario, RealExecEnv env, Path targetJdkHome) {
        Path runDir = env.workDir.resolve("run-" + scenario.id() + "-" + env.runnerPid);
        Path stdoutArtifact = runDir.resolve("target.stdout");
        Path stderrArtifact = runDir.resolve("target.stderr");
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        String[] failure = {""};  // mutable holder for the failure reason
        String[] targetJdk = {""};
        int[] childPid = {0};
        String launchCommand = "";
        String attachCommand = "";
        Process target = null;
        OutputPump stdoutPump = null;
        Thread stderrThread = null;
        Writer targetStdin = null;
        try {
            Files.createDirectories(runDir);
            // Materialize both evidence files even when the target emits no output on
            // one stream. A path alone must never masquerade as durable evidence.
            Files.writeString(stdoutArtifact, "", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(stderrArtifact, "", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            PlainJavaFixtureSource fixture = new PlainJavaFixtureSource(runDir);
            Path sourceFile = fixture.writeSource();
            Path classesDir = fixture.classDirectory();
            Files.createDirectories(classesDir);

            // 1. Compile the fixture with the selected target JDK javac.
            Path targetJavac = targetJdkHome.resolve("bin").resolve("javac");
            if (!Files.isExecutable(targetJavac)) {
                failure[0] = "target javac not executable: " + targetJavac;
                return outcome(scenario, false, 0, env, "", "", "", stdoutArtifact,
                        stderrArtifact, assertions, failure[0]);
            }
            List<String> javacCmd = List.of(targetJavac.toString(), "-d", classesDir.toString(),
                    sourceFile.toString());
            Path javacArtifact = runDir.resolve("javac.output");
            ProcessBuilder javacPb = new ProcessBuilder(javacCmd)
                    .redirectErrorStream(true)
                    .redirectOutput(javacArtifact.toFile());
            Process javac = javacPb.start();
            boolean javacDone = javac.waitFor(env.startupTimeoutMillis, TimeUnit.MILLISECONDS);
            if (!javacDone) {
                javac.destroyForcibly();
                javac.waitFor(env.operationTimeoutMillis, TimeUnit.MILLISECONDS);
            }
            String javacOut = Files.isRegularFile(javacArtifact)
                    ? Files.readString(javacArtifact, StandardCharsets.UTF_8) : "";
            boolean javacOk = javacDone && javac.exitValue() == 0;
            if (!javacOk) {
                failure[0] = "fixture compile failed (exit=" + (javac.isAlive() ? "timeout" : javac.exitValue())
                        + "): " + javacOut;
                return outcome(scenario, false, 0, env, "", "", "", stdoutArtifact,
                        stderrArtifact, assertions, failure[0]);
            }

            // 2. Allocate a loopback ephemeral port for the agent HTTP server.
            int port = allocateLoopbackPort();

            // 3. Launch the independent target JVM.
            Path targetJava = targetJdkHome.resolve("bin").resolve("java");
            String token = "kairo-compat-" + scenario.id() + "-" + env.runnerPid + "-" + port;
            List<String> targetCmd = new ArrayList<>();
            targetCmd.add(targetJava.toString());
            if (scenario.loadMode() == LoadMode.PREMAIN) {
                // C01: real -javaagent premain. coreJar is resolved explicitly so the
                // isolated bootstrap ClassLoader finds the modern core.
                String agentArgs = "coreJar=" + env.coreJar
                        + ",bootstrapJar=" + env.bootstrapApiJar
                        + ",host=127.0.0.1,port=" + port + ",token=" + token;
                targetCmd.add("-javaagent:" + env.bootstrapJar + "=" + agentArgs);
            }
            targetCmd.add("-cp");
            targetCmd.add(classesDir.toString());
            targetCmd.add(PlainJavaFixtureSource.CLASS_NAME);
            launchCommand = String.join(" ", targetCmd);
            ProcessBuilder targetPb = new ProcessBuilder(targetCmd);
            target = targetPb.start();
            long launchedPid = target.pid();
            if (launchedPid <= 0 || launchedPid > Integer.MAX_VALUE) {
                failure[0] = "launched target PID is outside the row schema range: " + launchedPid;
                return outcome(scenario, false, 0, env, "", launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            childPid[0] = (int) launchedPid;
            targetStdin = new OutputStreamWriter(target.getOutputStream(), StandardCharsets.UTF_8);
            stdoutPump = new OutputPump(target.getInputStream(), stdoutArtifact);
            stdoutPump.start();
            stderrThread = pumpToFile(target.getErrorStream(), stderrArtifact);
            stderrThread.setDaemon(true);
            stderrThread.start();

            // 4. Read READY pid=<pid> jdk=<ver> (bounded).
            String ready = stdoutPump.nextLine(env.startupTimeoutMillis, TimeUnit.MILLISECONDS);
            if (ready == null || !ready.startsWith("READY")) {
                failure[0] = "target did not print READY within "
                        + env.startupTimeoutMillis + "ms (last='" + safe(ready) + "')";
                return outcome(scenario, false, 0, env, "", "", "", stdoutArtifact,
                        stderrArtifact, assertions, failure[0]);
            }
            Map<String, String> readyFields = parseReady(ready);
            int reportedPid = parseIntOr(readyFields.get("pid"), 0);
            targetJdk[0] = readyFields.getOrDefault("jdk", "");
            int pid = childPid[0];
            boolean independent = pid != env.runnerPid;
            if (reportedPid <= 0) {
                failure[0] = "target READY carried no pid: " + ready;
                assertions.add(assertion("harness.pid", false, failure[0]));
                return outcome(scenario, true, pid, env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            if (reportedPid != pid) {
                failure[0] = "target READY pid " + reportedPid
                        + " does not match Process.pid() " + pid;
                assertions.add(assertion("harness.pid", false, failure[0]));
                return outcome(scenario, true, pid, env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            if (!independent) {
                failure[0] = "target pid " + pid + " equals runner pid " + env.runnerPid
                        + ": not an independent process";
                return outcome(scenario, false, pid, env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            int targetJdkMajor = PlatformNormals.majorJdk(targetJdk[0]);
            if (!scenario.targetJdks().contains(targetJdkMajor)) {
                failure[0] = "target READY JDK '" + targetJdk[0]
                        + "' does not match catalog JDKs " + scenario.targetJdks();
                assertions.add(assertion("harness.targetJdk", false, failure[0]));
                return outcome(scenario, true, pid, env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }

            // 5. Attach (C02/C09) via the real kairo-attach-cli entry.
            String attachExitDetail = "premain (no attach)";
            if (scenario.loadMode() != LoadMode.PREMAIN) {
                Path runnerJava = Path.of(System.getProperty("java.home")).resolve("bin").resolve("java");
                List<String> attachCmd = List.of(runnerJava.toString(), "-jar", env.attachJar.toString(),
                        "--pid", Integer.toString(pid),
                        "--agent", env.bootstrapJar.toString(),
                        "--core-jar", env.coreJar.toString(),
                        "--bootstrap-jar", env.bootstrapApiJar.toString(),
                        "--token", token,
                        "--port", Integer.toString(port));
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
                attachExitDetail = "attach exit=" + attachExit;
                boolean attachOk = attachDone && attachExit == 0;
                assertions.add(assertion("真实 attach".equals(scenario.requiredBehaviors().get(0))
                                ? "真实 attach" : "attach", attachOk,
                        attachExitDetail + "; cmd=" + attachCommand));
                if (!attachOk) {
                    failure[0] = "external attach failed (" + attachExitDetail + ")";
                    return outcome(scenario, true, pid, env, targetJdk[0], launchCommand, attachCommand,
                            stdoutArtifact, stderrArtifact, assertions, failure[0]);
                }
            }

            // 6. Poll /health until UP (bounded).
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            long deadline = System.currentTimeMillis() + env.startupTimeoutMillis;
            boolean healthUp = false;
            while (System.currentTimeMillis() < deadline) {
                try {
                    HttpResponse<String> h = httpGet(http, port, token, "/health");
                    if (h.statusCode() == 200 && h.body().contains("UP")) {
                        healthUp = true;
                        break;
                    }
                } catch (Exception ignored) {
                    // not up yet
                }
                Thread.sleep(200);
            }
            if (scenario.loadMode() != LoadMode.PREMAIN) {
                // replace the provisional attach assertion with one that also confirms health
                assertions.set(assertions.size() - 1,
                        assertion(assertions.get(assertions.size() - 1).name(), healthUp,
                                attachExitDetail + "; /health=" + (healthUp ? "UP" : "DOWN") + "; cmd=" + attachCommand));
            }
            if (!healthUp) {
                failure[0] = "agent /health never came UP within " + env.startupTimeoutMillis + "ms";
                return outcome(scenario, true, pid, env, targetJdk[0], launchCommand, attachCommand,
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }

            // 7. Resolve the fixture class + score() method via the real HTTP API.
            TargetMethod tm = resolveTargetMethod(http, port, token, env.operationTimeoutMillis);
            if (tm == null) {
                failure[0] = "could not resolve PlainJavaTarget.score() via /classes + /methods";
                return outcome(scenario, true, pid, env, targetJdk[0], launchCommand, attachCommand,
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }

            // 8. Baseline invoke: real method invocation, behavior from the target.
            Integer baseline = invokeAndRead(stdoutPump, targetStdin, env.operationTimeoutMillis);
            boolean invokeOk = baseline != null && baseline == PlainJavaFixtureSource.BASELINE_SCORE;
            assertions.add(assertion(behaviorName(scenario, "invoke"), invokeOk,
                    "INVOKE -> RESULT " + (baseline == null ? "<none>" : baseline)
                            + " (expected " + PlainJavaFixtureSource.BASELINE_SCORE + ")"));

            // 9. Enhance: POST /rules (real rule), then prove the changed return value.
            String enhanceScript = "return mock.returnValue(" + PlainJavaFixtureSource.ENHANCED_SCORE + ")";
            HttpOutcome enhanceResp = httpPost(http, port, token, "/rules",
                    ruleJson("compat-enhance", tm, enhanceScript), env.operationTimeoutMillis);
            Integer enhanced = invokeAndRead(stdoutPump, targetStdin, env.operationTimeoutMillis);
            boolean enhanceOk = enhanceResp.statusCode() == 201
                    && enhanced != null && enhanced == PlainJavaFixtureSource.ENHANCED_SCORE;
            assertions.add(assertion(behaviorName(scenario, "enhance"), enhanceOk,
                    "POST /rules -> " + enhanceResp.statusCode() + " "
                            + truncate(enhanceResp.body()) + "; INVOKE -> RESULT "
                            + (enhanced == null ? "<none>" : enhanced)
                            + " (expected " + PlainJavaFixtureSource.ENHANCED_SCORE + ")"));
            if (!enhanceOk && failure[0].isEmpty()) {
                failure[0] = "enhance did not take effect: POST " + enhanceResp.statusCode()
                        + ", INVOKE=" + enhanced;
            }

            // 10. Update (C01 only): PUT /rules/{id} with a new return value.
            if (scenario.id().equals("C01")) {
                String updateScript = "return mock.returnValue(" + PlainJavaFixtureSource.UPDATED_SCORE + ")";
                HttpOutcome updateResp = httpPut(http, port, token, "/rules/compat-enhance",
                        ruleJson("compat-enhance", tm, updateScript), env.operationTimeoutMillis);
                Integer updated = invokeAndRead(stdoutPump, targetStdin, env.operationTimeoutMillis);
                boolean updateOk = updateResp.statusCode() == 200
                        && updated != null && updated == PlainJavaFixtureSource.UPDATED_SCORE;
                assertions.add(assertion("更新", updateOk,
                        "PUT /rules/compat-enhance -> " + updateResp.statusCode()
                                + "; INVOKE -> RESULT " + (updated == null ? "<none>" : updated)
                                + " (expected " + PlainJavaFixtureSource.UPDATED_SCORE + ")"));
                if (!updateOk && failure[0].isEmpty()) {
                    failure[0] = "update did not take effect: PUT " + updateResp.statusCode() + ", INVOKE=" + updated;
                }
            }

            // 11. Unload: DELETE /rules/{id}, then invoke to prove restored behavior.
            HttpOutcome deleteResp = httpDelete(http, port, token, "/rules/compat-enhance",
                    env.operationTimeoutMillis);
            Integer restored = invokeAndRead(stdoutPump, targetStdin, env.operationTimeoutMillis);
            boolean unloadOk = deleteResp.statusCode() == 200
                    && restored != null && restored == PlainJavaFixtureSource.BASELINE_SCORE;
            assertions.add(assertion(behaviorName(scenario, "unload"), unloadOk,
                    "DELETE /rules/compat-enhance -> " + deleteResp.statusCode()
                            + "; INVOKE -> RESULT " + (restored == null ? "<none>" : restored)
                            + " (expected restored " + PlainJavaFixtureSource.BASELINE_SCORE + ")"));
            if (!unloadOk && failure[0].isEmpty()) {
                failure[0] = "unload did not restore behavior: DELETE " + deleteResp.statusCode()
                        + ", INVOKE=" + restored;
            }

            // 12. Shutdown. C02 requires it as a behavior; C09 does not (it only cleans up).
            // /agent/shutdown stops the agent HTTP server asynchronously but does NOT exit the
            // target JVM, so the target is exited cleanly via the fixture's SHUTDOWN command.
            if (scenario.id().equals("C02")) {
                HttpOutcome shutdownResp = httpPost(http, port, token, "/agent/shutdown", "{}",
                        env.operationTimeoutMillis);
                boolean healthDown = awaitAgentDown(http, port, token, 3000L);
                targetStdin.write("SHUTDOWN\n");
                targetStdin.flush();
                boolean exited = target.waitFor(env.operationTimeoutMillis, TimeUnit.MILLISECONDS) && !target.isAlive();
                boolean shutdownOk = shutdownResp.statusCode() == 200 && healthDown && exited;
                assertions.add(assertion("shutdown", shutdownOk,
                        "POST /agent/shutdown -> " + shutdownResp.statusCode()
                                + "; /health " + (healthDown ? "DOWN" : "still UP")
                                + "; target " + (exited ? "exited" : "still alive")));
                if (!shutdownOk && failure[0].isEmpty()) {
                    failure[0] = "shutdown did not complete cleanly: POST " + shutdownResp.statusCode()
                            + ", healthDown=" + healthDown + ", exited=" + exited;
                }
            } else if (scenario.id().equals("C09")) {
                // C09 has no shutdown required behavior; exit the target cleanly for cleanup.
                targetStdin.write("SHUTDOWN\n");
                targetStdin.flush();
                target.waitFor(env.operationTimeoutMillis, TimeUnit.MILLISECONDS);
            }

            // Evidence assertions (extra; carry the exact commands + artifact paths).
            assertions.add(assertion("evidence.launchCommand", true, launchCommand));
            if (!attachCommand.isEmpty()) {
                assertions.add(assertion("evidence.attachCommand", true, attachCommand));
            }
            assertions.add(assertion("evidence.stdoutArtifact", Files.isRegularFile(stdoutArtifact),
                    stdoutArtifact.toString()));
            assertions.add(assertion("evidence.stderrArtifact", Files.isRegularFile(stderrArtifact),
                    stderrArtifact.toString()));

            boolean allPassed = assertions.stream().allMatch(a -> a.passed());
            String status = allPassed && failure[0].isEmpty() ? "PASSED" : "FAILED";
            if (status.equals("PASSED")) {
                failure[0] = "";
            } else if (failure[0].isEmpty()) {
                failure[0] = "one or more behavior assertions failed";
            }
            return outcome(scenario, true, pid, env, targetJdk[0], launchCommand, attachCommand,
                    stdoutArtifact, stderrArtifact, assertions, status.equals("PASSED") ? "" : failure[0]);
        } catch (Exception e) {
            if (failure[0].isEmpty()) {
                failure[0] = "executor error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            return outcome(scenario, childPid[0] > 0, childPid[0], env, targetJdk[0],
                    launchCommand, attachCommand, stdoutArtifact, stderrArtifact, assertions, failure[0]);
        } finally {
            // Always clean up: close stdin, stop pumps, destroy the child.
            closeQuiet(targetStdin);
            if (stdoutPump != null) {
                stdoutPump.stop();
            }
            if (target != null && target.isAlive()) {
                target.destroyForcibly();
                try {
                    target.waitFor(env.operationTimeoutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (stderrThread != null) {
                stderrThread.interrupt();
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private static PlainJavaExecutionOutcome outcome(CompatibilityScenario scenario, boolean started,
                                                     int childPid, RealExecEnv env, String targetJdk,
                                                     String launchCommand, String attachCommand,
                                                     Path stdoutArtifact, Path stderrArtifact,
                                                     List<CompatibilityRowRunner.Assertion> assertions,
                                                     String failureReason) {
        boolean independent = childPid > 0 && childPid != env.runnerPid;
        return new PlainJavaExecutionOutcome(started, childPid, independent, targetJdk,
                launchCommand, attachCommand, stdoutArtifact, stderrArtifact,
                new ArrayList<>(assertions), failureReason);
    }

    /** Allocates a free loopback port by briefly binding and closing a socket. */
    private static int allocateLoopbackPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("could not allocate a loopback port", e);
        }
    }

    private static Integer invokeAndRead(OutputPump pump, Writer stdin, long timeoutMillis) throws Exception {
        stdin.write("INVOKE\n");
        stdin.flush();
        String line = pump.nextLine(timeoutMillis, TimeUnit.MILLISECONDS);
        if (line == null || !line.startsWith("RESULT ")) {
            return null;
        }
        try {
            return Integer.parseInt(line.substring("RESULT ".length()).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String behaviorName(CompatibilityScenario scenario, String kind) {
        // Map the generic behavior to the catalog's verbatim required-behavior token.
        return switch (scenario.id()) {
            case "C01" -> switch (kind) {
                case "enhance" -> "增强";
                case "invoke" -> "调用";
                case "unload" -> "卸载";
                default -> kind;
            };
            case "C02" -> switch (kind) {
                case "enhance" -> "增强";
                case "unload" -> "卸载";
                default -> kind;
            };
            case "C09" -> switch (kind) {
                case "enhance" -> "增强";
                case "unload" -> "卸载";
                default -> kind;
            };
            default -> kind;
        };
    }

    private record TargetMethod(String classId, String classLoaderId, String descriptor) {
    }

    /** Resolves PlainJavaTarget.score() via GET /classes + GET /classes/{id}/methods. */
    private static TargetMethod resolveTargetMethod(HttpClient http, int port, String token, long timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> resp = httpGet(http, port, token, "/classes?keyword=PlainJavaTarget");
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
                if (!"PlainJavaTarget".equals(c.path("className").asText())) {
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
                    if ("score".equals(mm.path("name").asText()) && "()I".equals(mm.path("descriptor").asText())) {
                        return new TargetMethod(classId, classLoaderId, "()I");
                    }
                }
            }
            break;
        }
        return null;
    }

    private static String ruleJson(String id, TargetMethod tm, String script) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", id);
        rule.put("name", id);
        rule.put("classId", tm.classId());
        rule.put("className", PlainJavaFixtureSource.CLASS_NAME);
        rule.put("classLoaderId", tm.classLoaderId());
        rule.put("methodName", "score");
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

    /** Polls /health until the agent is no longer healthy (down) or the deadline passes. */
    private static boolean awaitAgentDown(HttpClient http, int port, String token, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> h = httpGet(http, port, token, "/health");
                if (h.statusCode() != 200 || !h.body().contains("UP")) {
                    return true;
                }
            } catch (Exception e) {
                // Connection refused / reset = the agent HTTP server has stopped.
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static HttpOutcome httpPost(HttpClient http, int port, String token, String path,
                                        String body, long timeoutMillis) {
        return httpSend(http, port, token, path, "POST", body, timeoutMillis);
    }

    private static HttpOutcome httpPut(HttpClient http, int port, String token, String path,
                                       String body, long timeoutMillis) {
        return httpSend(http, port, token, path, "PUT", body, timeoutMillis);
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

    private static Map<String, String> parseReady(String ready) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String tok : ready.split("\\s+")) {
            int eq = tok.indexOf('=');
            if (eq > 0) {
                map.put(tok.substring(0, eq), tok.substring(eq + 1));
            }
        }
        return map;
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String safe(String s) {
        return s == null ? "<null>" : s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 160 ? s : s.substring(0, 160) + "...";
    }

    private static void closeQuiet(Writer w) {
        if (w != null) {
            try {
                w.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private static Thread pumpToFile(InputStream in, Path file) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                }
            } catch (Exception ignored) {
                // process closed
            }
        }, "kairo-compat-stderr");
    }

    /**
     * Pumps the target stdout line-by-line into an artifact file AND a queue, so
     * the runner can read READY/RESULT/BYE while the full output is mirrored to
     * disk. No stdout is lost to a redirect.
     */
    private static final class OutputPump {
        private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private final Thread thread;
        private volatile boolean stopped;

        OutputPump(InputStream in, Path artifact) {
            thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while (!stopped && (line = reader.readLine()) != null) {
                        queue.offer(line);
                        Files.writeString(artifact, line + System.lineSeparator(), StandardCharsets.UTF_8,
                                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    }
                } catch (Exception ignored) {
                    // process closed
                }
            }, "kairo-compat-stdout");
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        String nextLine(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }

        void stop() {
            stopped = true;
            thread.interrupt();
        }
    }
}
