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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * The real M3-E target executor. Launches a <strong>genuinely independent</strong> target
 * JVM (premain for C08/C10), drives the real Kairo agent load path, and proves the C08/C10
 * fixed behaviors against the agent's real loopback HTTP API plus - for C08 - real
 * {@link java.lang.instrument.Instrumentation} {@code redefineClasses}/
 * {@code retransformClasses} performed through the harness agent.
 *
 * <p>Nothing here is hard-coded: the child PID, target JDK, launch/attach commands, hashes
 * and the printed RESULT values all come from the real target process. Assertions are
 * derived from target behavior. The executor always cleans up the child and bounds every wait.
 *
 * <ul>
 *   <li>C08: a {@code Premain-Class} harness agent captures the real Instrumentation and
 *   performs genuine {@code redefineClasses}/{@code retransformClasses}. Proves safe
 *   reconciliation continues (hot update + retransform on a non-drifted class) and that an
 *   external redefine lands precisely on TARGET_DRIFTED (a {@code target.drifted} event)
 *   without silently overwriting drift, then precise unload. RESET_ALL is never used.</li>
 *   <li>C10: a single in-repo controlled Byte Buddy Agent (loaded AHEAD of Kairo)
 *   transforms {@code CoexistTarget.tag()} to return {@code "BB"}. Proves Kairo
 *   enhance/update/unload preserves the Byte Buddy transform (behavior AND bytecode hash)
 *   while taking effect on {@code score()}.</li>
 * </ul>
 */
final class RealM3ETargetExecutor implements M3ETargetExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public M3EExecutionOutcome execute(CompatibilityScenario scenario, RealExecEnv env,
                                      Path targetJdkHome, M3EAuxJars auxJars) {
        try {
            return switch (scenario.id()) {
                case "C08" -> executeC08(scenario, env, targetJdkHome);
                case "C10" -> executeC10(scenario, env, targetJdkHome, auxJars);
                default -> new M3EExecutionOutcome(false, 0, false, "", "", "", null, null,
                        List.of(), "M3-E has no executor for scenario " + scenario.id());
            };
        } catch (Exception e) {
            return new M3EExecutionOutcome(false, 0, false, "", "", "", null, null,
                    List.of(), "executor error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ============================================================ C08: redefine/retransform/drift
    private M3EExecutionOutcome executeC08(CompatibilityScenario scenario, RealExecEnv env,
                                           Path targetJdkHome) throws Exception {
        Path runDir = env.workDir.resolve("run-C08-" + env.runnerPid);
        Files.createDirectories(runDir);
        Path stdoutArtifact = runDir.resolve("target.stdout");
        Path stderrArtifact = runDir.resolve("target.stderr");
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        String[] failure = {""};
        String[] targetJdk = {""};
        int[] childPid = {0};
        String launchCommand = "";
        TargetHandle target = null;
        try {
            M3EFixtureSources fixtures = new M3EFixtureSources(runDir);
            // v1 target + harness sources.
            Path targetSrc = fixtures.writeSource(M3EFixtureSources.C08_TARGET_CLASS,
                    M3EFixtureSources.C08_TARGET_SOURCE);
            Path harnessSrc = fixtures.writeSource(M3EFixtureSources.C08_HARNESS_CLASS,
                    M3EFixtureSources.C08_HARNESS_SOURCE);
            Path classesDir = fixtures.classDirectory();
            Files.createDirectories(classesDir);
            if (!compileFixture(env, targetJdkHome, List.of(targetSrc, harnessSrc), classesDir, List.of(),
                    runDir, failure)) {
                return outcome(false, 0, env, "", "", "", stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            // Package the harness as a Premain-Class agent jar (captures real Instrumentation).
            Path manifestFile = fixtures.writeManifest("DriftHarness.mf", M3EFixtureSources.C08_HARNESS_MANIFEST);
            Path harnessJar = runDir.resolve("c08-driftharness.jar");
            if (!packageAgentJar(harnessJar, manifestFile,
                    List.of(classesDir.resolve(M3EFixtureSources.C08_HARNESS_CLASS + ".class")), failure)) {
                return outcome(false, 0, env, "", "", "", stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            // v2 target source (different method body) compiled to a separate v2 dir for the redefine.
            Path v2Dir = runDir.resolve("v2classes");
            Files.createDirectories(v2Dir);
            Path v2Src = fixtures.writeSource(M3EFixtureSources.C08_TARGET_CLASS,
                    M3EFixtureSources.C08_TARGET_V2_SOURCE);
            if (!compileFixture(env, targetJdkHome, List.of(v2Src), v2Dir, List.of(), runDir, failure)) {
                return outcome(false, 0, env, "", "", "", stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            // Launch with BOTH the Kairo agent and the harness agent (Kairo first, harness second;
            // the harness only captures Instrumentation and does not transform classes).
            String cp = classesDir.toString() + ":" + harnessJar.toString();
            target = launch(scenario, env, targetJdkHome, List.of(),
                    List.of(new AgentSpec(harnessJar, "")), cp,
                    "-Dclasses.dir=" + classesDir.toString(),
                    M3EFixtureSources.C08_HARNESS_CLASS, runDir, stdoutArtifact, stderrArtifact,
                    failure, targetJdk, childPid);
            if (target == null) {
                return outcome(childPid[0] > 0, childPid[0], env, targetJdk[0],
                        launchCommand, "", stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            launchCommand = target.launchCommand;
            if (!readReady(target, env, failure, targetJdk, childPid)) {
                return outcome(true, childPid[0], env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            // Prove the real Instrumentation capability was captured (a mock Instrumentation
            // reports canRedefine=false / canRetransform=false). This is the section-10.4.5
            // negative case: mock Instrumentation cannot pass C08.
            String caps = commandAndRead(target, "CAPABILITIES", "RESULT CAPABILITIES", env);
            boolean instReal = caps != null
                    && caps.contains("canRedefine=true")
                    && caps.contains("canRetransform=true")
                    && caps.contains("inst=true");
            assertions.add(assertion("instrumentation.real", instReal,
                    "real Instrumentation capabilities: " + safe(caps)));
            if (!instReal) {
                failure[0] = "harness Instrumentation is not real/redefine/retransform-capable";
                return finish(true, childPid[0], env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            if (!awaitHealth(target.http, target.port, target.token, env)) {
                failure[0] = "agent /health never came UP";
                return outcome(true, childPid[0], env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }

            List<String> order = new ArrayList<>();
            ResolvedClass driftTarget = resolveClassByExactName(target.http, target.port, target.token,
                    M3EFixtureSources.C08_TARGET_CLASS, env, "C08 discover");
            if (driftTarget == null) {
                failure[0] = "could not resolve DriftTarget via /classes";
                assertions.add(assertion("enhance.real", false, failure[0]));
                return finish(true, childPid[0], env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            ResolvedMethod score = resolveMethod(target.http, target.port, target.token,
                    driftTarget.classId, "score", "(I)I", env);
            if (score == null) {
                failure[0] = "could not resolve DriftTarget.score(int)";
                assertions.add(assertion("enhance.real", false, failure[0]));
                return finish(true, childPid[0], env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }

            // Step 1: baseline behavior + baseline applied hash.
            Integer baselineScore = invokeScore(target, 5, env);
            String baselineHash = captureAppliedHash(target.http, target.port, target.token,
                    driftTarget.classId, env);
            boolean baselineOk = baselineScore != null && baselineScore == M3EFixtureSources.C08_BASELINE;
            assertions.add(assertion("baseline.hash", baselineOk && baselineHash != null,
                    "score(5)=" + baselineScore + " (expected " + M3EFixtureSources.C08_BASELINE
                            + "); baseline appliedHash=" + baselineHash));
            order.add("baseline:score=" + baselineScore + ",hash=" + shortHash(baselineHash));

            // Step 2: real enhance (POST /rules). Hash must change; behavior must update.
            HttpOutcome enhResp = httpPost(target.http, target.port, target.token, "/rules",
                    ruleJson("c08-rule", driftTarget, score, "score",
                            "return mock.returnValue(" + M3EFixtureSources.C08_ENHANCED_1 + ")"),
                    env.operationTimeoutMillis);
            Integer enhScore = invokeScore(target, 5, env);
            String enhancedHash = captureAppliedHash(target.http, target.port, target.token,
                    driftTarget.classId, env);
            boolean enhanceOk = enhResp.statusCode() == 201
                    && enhScore != null && enhScore == M3EFixtureSources.C08_ENHANCED_1
                    && enhancedHash != null && !enhancedHash.equals(baselineHash);
            assertions.add(assertion("enhance.real", enhanceOk,
                    "POST /rules -> " + enhResp.statusCode() + "; score " + baselineScore + " -> " + enhScore
                            + "; hash " + shortHash(baselineHash) + " -> " + shortHash(enhancedHash)));
            order.add("enhance:score=" + enhScore + ",hash=" + shortHash(enhancedHash));

            // Step 3: hot update (PUT /rules/c08-rule) on a NON-drifted class -> safe
            // reconciliation continues (re-applies the new script; no TARGET_DRIFTED).
            HttpOutcome updResp = httpPut(target.http, target.port, target.token, "/rules/c08-rule",
                    ruleJson("c08-rule", driftTarget, score, "score",
                            "return mock.returnValue(" + M3EFixtureSources.C08_ENHANCED_2 + ")"),
                    env.operationTimeoutMillis);
            Integer updScore = invokeScore(target, 5, env);
            String updHash = captureAppliedHash(target.http, target.port, target.token,
                    driftTarget.classId, env);
            boolean hotUpdateSafe = updResp.statusCode() == 200
                    && updScore != null && updScore == M3EFixtureSources.C08_ENHANCED_2
                    && updHash != null;
            assertions.add(assertion("hotupdate.safe.reconciled", hotUpdateSafe,
                    "PUT /rules -> " + updResp.statusCode() + "; score " + enhScore + " -> " + updScore
                            + " (safe reconciliation; no TARGET_DRIFTED); hash=" + shortHash(updHash)));
            order.add("hotupdate-safe:score=" + updScore + ",hash=" + shortHash(updHash));

            // Step 4: real retransform (instrumentation.retransformClasses). The rule continues
            // to apply; no drift asserted (Kairo's own retransform is Mode.RETRANSFORM, gated
            // out of the drift listener). Behavior stays at the enhanced value.
            String retransformed = commandAndRead(target, "RETRANSFORM", "RESULT RETRANSFORM", env);
            Integer afterRetransform = invokeScore(target, 5, env);
            String retransformHash = captureAppliedHash(target.http, target.port, target.token,
                    driftTarget.classId, env);
            boolean retransformReal = retransformed != null && retransformed.contains("ok")
                    && afterRetransform != null && afterRetransform == M3EFixtureSources.C08_ENHANCED_2
                    && retransformHash != null;
            assertions.add(assertion("retransform.real", retransformReal,
                    "retransformClasses=" + safe(retransformed) + "; score after=" + afterRetransform
                            + " (expected " + M3EFixtureSources.C08_ENHANCED_2 + "); hash=" + shortHash(retransformHash)));
            order.add("retransform:score=" + afterRetransform + ",hash=" + shortHash(retransformHash));

            // Step 5: real redefine (instrumentation.redefineClasses with v2 bytes). The agent's
            // redefine listener hashes the changed input and lands on TARGET_DRIFTED.
            String redefineCmd = "REDEFINE " + v2Dir.toString();
            String redefined = commandAndRead(target, redefineCmd, "RESULT REDEFINE", env);
            boolean redefineReal = redefined != null && redefined.contains("ok");
            // Allow the asynchronous redefine listener a bounded window to flag drift.
            boolean drifted = false;
            long driftDeadline = System.currentTimeMillis() + env.operationTimeoutMillis;
            List<RuntimeEvent> driftEvents = List.of();
            while (System.currentTimeMillis() < driftDeadline) {
                driftEvents = eventsOfType(target.http, target.port, target.token, "target.drifted", env);
                drifted = driftEvents.stream().anyMatch(e ->
                        M3EFixtureSources.C08_TARGET_CLASS.equals(e.target)
                                || (e.message != null && e.message.contains(M3EFixtureSources.C08_TARGET_CLASS)));
                if (drifted) {
                    break;
                }
                Thread.sleep(100);
            }
            String redefineHash = captureAppliedHash(target.http, target.port, target.token,
                    driftTarget.classId, env);
            Integer afterRedefine = invokeScore(target, 5, env);
            redefineReal = redefineReal && redefineHash != null
                    && !redefineHash.equals(retransformHash);
            assertions.add(assertion("redefine.real", redefineReal,
                    "redefineClasses=" + safe(redefined) + "; score after=" + afterRedefine
                            + "; hash after=" + shortHash(redefineHash)));
            assertions.add(assertion("target.drifted", drifted,
                    drifted ? ("target.drifted event present: " + firstDriftMessage(driftEvents))
                            : "no target.drifted event after real redefine"));
            order.add("redefine:drifted=" + drifted + ",score=" + afterRedefine + ",hash=" + shortHash(redefineHash));

            // Step 6: never silently overwrite drift. The unsafe hot update must be rejected by
            // the real Agent API with the exact TARGET_DRIFTED token, leave behavior unchanged,
            // and preserve the active drift evidence. Merely finding an old append-only event is
            // insufficient: that was the defect which originally let this fixture pass while
            // publishLocked silently re-anchored the changed bytes.
            HttpOutcome driftUpdResp = httpPut(target.http, target.port, target.token, "/rules/c08-rule",
                    ruleJson("c08-rule", driftTarget, score, "score",
                            "return mock.returnValue(99)"),
                    env.operationTimeoutMillis);
            Integer afterRejectedUpdate = invokeScore(target, 5, env);
            String afterRejectedUpdateHash = captureAppliedHash(target.http, target.port, target.token,
                    driftTarget.classId, env);
            List<RuntimeEvent> driftEventsAfter = eventsOfType(target.http, target.port, target.token,
                    "target.drifted", env);
            boolean driftEvidencePersists = driftEventsAfter.stream().anyMatch(e ->
                    M3EFixtureSources.C08_TARGET_CLASS.equals(e.target)
                            || (e.message != null && e.message.contains(M3EFixtureSources.C08_TARGET_CLASS)));
            boolean noResetAllUsed = !launchCommand.contains("reset-all") && !launchCommand.contains("reset-class");
            boolean exactTargetDrifted = driftUpdResp.statusCode() == 400
                    && driftUpdResp.body() != null
                    && driftUpdResp.body().contains("TARGET_DRIFTED");
            boolean behaviorNotOverwritten = afterRejectedUpdate != null
                    && afterRejectedUpdate.equals(afterRedefine)
                    && afterRejectedUpdate != 99;
            assertions.add(assertion("drift.not.silently.overwritten",
                    driftEvidencePersists && noResetAllUsed && exactTargetDrifted && behaviorNotOverwritten,
                    "drift event persists after hot-update=" + driftEvidencePersists
                            + "; no RESET_ALL used=" + noResetAllUsed
                            + "; drift hot-update PUT -> " + driftUpdResp.statusCode()
                            + "; exact TARGET_DRIFTED=" + exactTargetDrifted
                            + "; behavior " + afterRedefine + " -> " + afterRejectedUpdate
                            + "; hash=" + shortHash(afterRejectedUpdateHash)));
            order.add("drift-rejected:status=" + driftUpdResp.statusCode()
                    + ",TARGET_DRIFTED=" + exactTargetDrifted
                    + ",score=" + afterRejectedUpdate + ",hash=" + shortHash(afterRejectedUpdateHash));

            // Step 7: precise unload (DELETE /rules/c08-rule) - never RESET_ALL. Record the
            // post-unload behavior so it can be verified (the requirement's "卸载后行为").
            HttpOutcome del = httpDelete(target.http, target.port, target.token, "/rules/c08-rule",
                    env.operationTimeoutMillis);
            Integer afterUnload = invokeScore(target, 5, env);
            String unloadHash = captureAppliedHash(target.http, target.port, target.token,
                    driftTarget.classId, env);
            boolean unloadOk = del.statusCode() == 200
                    && afterUnload != null && afterUnload == M3EFixtureSources.C08_V2_SCORE
                    && unloadHash != null;
            assertions.add(assertion("unload.behavior", unloadOk,
                    "DELETE /rules -> " + del.statusCode() + "; post-unload score=" + afterUnload
                            + "; post-unload hash=" + shortHash(unloadHash) + " (precise DELETE, not RESET_ALL)"));
            order.add("unload:score=" + afterUnload + ",hash=" + shortHash(unloadHash));

            // Execution order + the catalog aggregate behavior.
            assertions.add(assertion("execution.order", true, String.join(" | ", order)));
            boolean aggregate = instReal && baselineOk && enhanceOk && hotUpdateSafe && retransformReal
                    && redefineReal && drifted && driftEvidencePersists && noResetAllUsed
                    && exactTargetDrifted && behaviorNotOverwritten && unloadOk;
            assertions.add(assertion("成功对账或明确 TARGET_DRIFTED", aggregate,
                    "safe-reconciled=" + (hotUpdateSafe && retransformReal) + "; TARGET_DRIFTED-evidenced="
                            + drifted + "; exact-TARGET_DRIFTED=" + exactTargetDrifted
                            + "; not-silently-overwritten=" + behaviorNotOverwritten
                            + "; precise-unload=" + unloadOk));
            if (!aggregate && failure[0].isEmpty()) {
                failure[0] = "C08 redefine/retransform/hot-update drift assertion failed";
            }
            String reason = aggregate ? "" : failure[0];
            return finish(true, childPid[0], env, targetJdk[0], launchCommand, "",
                    stdoutArtifact, stderrArtifact, assertions, reason);
        } finally {
            if (target != null) {
                target.close(env);
            }
        }
    }

    // ============================================================ C10: controlled BB-agent coexistence
    private M3EExecutionOutcome executeC10(CompatibilityScenario scenario, RealExecEnv env,
                                           Path targetJdkHome, M3EAuxJars auxJars) throws Exception {
        Path runDir = env.workDir.resolve("run-C10-" + env.runnerPid);
        Files.createDirectories(runDir);
        Path stdoutArtifact = runDir.resolve("target.stdout");
        Path stderrArtifact = runDir.resolve("target.stderr");
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        String[] failure = {""};
        String[] targetJdk = {""};
        int[] childPid = {0};
        String launchCommand = "";
        TargetHandle target = null;
        try {
            M3EFixtureSources fixtures = new M3EFixtureSources(runDir);
            Path targetSrc = fixtures.writeSource(M3EFixtureSources.C10_TARGET_CLASS,
                    M3EFixtureSources.C10_TARGET_SOURCE);
            Path harnessSrc = fixtures.writeSource(M3EFixtureSources.C10_HARNESS_CLASS,
                    M3EFixtureSources.C10_HARNESS_SOURCE);
            Path bbAgentSrc = fixtures.writeSource(M3EFixtureSources.C10_BB_AGENT_CLASS,
                    M3EFixtureSources.C10_BB_AGENT_SOURCE);
            Path adviceSrc = fixtures.writeSource(M3EFixtureSources.C10_BB_ADVICE_CLASS,
                    M3EFixtureSources.C10_BB_ADVICE_SOURCE);
            Path classesDir = fixtures.classDirectory();
            Files.createDirectories(classesDir);
            if (!compileFixture(env, targetJdkHome, List.of(targetSrc, harnessSrc), classesDir, List.of(),
                    runDir, failure)) {
                return outcome(false, 0, env, "", "", "", stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            // Compile the controlled BB Agent + Advice with byte-buddy on the classpath.
            Path bbClassesDir = runDir.resolve("bbclasses");
            Files.createDirectories(bbClassesDir);
            if (!compileFixture(env, targetJdkHome, List.of(bbAgentSrc, adviceSrc), bbClassesDir,
                    List.of(auxJars.byteBuddyJar), runDir, failure)) {
                return outcome(false, 0, env, "", "", "", stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            Path bbManifest = fixtures.writeManifest("ByteBuddyCoexistAgent.mf",
                    M3EFixtureSources.C10_BB_AGENT_MANIFEST);
            Path bbAgentJar = runDir.resolve("c10-bytebuddy-agent.jar");
            if (!packageAgentJar(bbAgentJar, bbManifest,
                    List.of(bbClassesDir.resolve(M3EFixtureSources.C10_BB_AGENT_CLASS + ".class"),
                            bbClassesDir.resolve(M3EFixtureSources.C10_BB_ADVICE_CLASS + ".class")), failure)) {
                return outcome(false, 0, env, "", "", "", stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            // Launch with the BB Agent AHEAD of Kairo (the meaningful coexistence case).
            String cp = classesDir.toString() + ":" + auxJars.byteBuddyJar.toString();
            target = launch(scenario, env, targetJdkHome,
                    List.of(new AgentSpec(bbAgentJar, "")), List.of(), cp,
                    "-Dclasses.dir=" + classesDir.toString(),
                    M3EFixtureSources.C10_HARNESS_CLASS, runDir, stdoutArtifact, stderrArtifact,
                    failure, targetJdk, childPid);
            if (target == null) {
                return outcome(childPid[0] > 0, childPid[0], env, targetJdk[0],
                        launchCommand, "", stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            launchCommand = target.launchCommand;
            if (!readReady(target, env, failure, targetJdk, childPid)) {
                return outcome(true, childPid[0], env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            if (!awaitHealth(target.http, target.port, target.token, env)) {
                failure[0] = "agent /health never came UP";
                return outcome(true, childPid[0], env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            // Prove the controlled BB Agent was actually loaded (the section-10.4.5 negative
            // case: an absent third-party transformation cannot pass C10). The BB Agent prints
            // BB_AGENT_INSTALLED at premain; it must appear in the durable stdout artifact.
            boolean bbInstalled = readArtifactContains(stdoutArtifact, "BB_AGENT_INSTALLED");
            assertions.add(assertion("foreign.transform.real", bbInstalled,
                    "controlled Byte Buddy Agent installed: " + bbInstalled
                            + " (BB_AGENT_INSTALLED in stdout)"));

            List<String> order = new ArrayList<>();
            ResolvedClass coexistTarget = resolveClassByExactName(target.http, target.port, target.token,
                    M3EFixtureSources.C10_TARGET_CLASS, env, "C10 discover");
            if (coexistTarget == null) {
                failure[0] = "could not resolve CoexistTarget via /classes";
                assertions.add(assertion("enhance.real", false, failure[0]));
                return finish(true, childPid[0], env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            ResolvedMethod score = resolveMethod(target.http, target.port, target.token,
                    coexistTarget.classId, "score", "(I)I", env);
            if (score == null) {
                failure[0] = "could not resolve CoexistTarget.score(int)";
                assertions.add(assertion("enhance.real", false, failure[0]));
                return finish(true, childPid[0], env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }

            // Step 1: baseline. The BB transform must already be present (tag="BB"); score is
            // the original body (Kairo has no rule yet). Capture the baseline-with-BB hash.
            Integer baselineScore = invokeScore(target, 5, env);
            String baselineTag = invokeTag(target, env);
            String baselineHash = captureAppliedHash(target.http, target.port, target.token,
                    coexistTarget.classId, env);
            boolean baselineForeignPresent = bbInstalled
                    && baselineScore != null && baselineScore == M3EFixtureSources.C10_BASELINE
                    && M3EFixtureSources.C10_TAG_BB.equals(baselineTag)
                    && baselineHash != null;
            assertions.add(assertion("baseline.foreign.present", baselineForeignPresent,
                    "score(5)=" + baselineScore + " (expected " + M3EFixtureSources.C10_BASELINE
                            + "); tag=" + safe(baselineTag) + " (expected " + M3EFixtureSources.C10_TAG_BB
                            + "); baseline-with-BB hash=" + shortHash(baselineHash)));
            order.add("baseline:score=" + baselineScore + ",tag=" + baselineTag + ",hash=" + shortHash(baselineHash));

            // Step 2: Kairo enhance score(). Behavior must update; the BB transform (tag) must
            // be preserved and the hash must change (Kairo advice composed onto BB-woven bytes).
            HttpOutcome enhResp = httpPost(target.http, target.port, target.token, "/rules",
                    ruleJson("c10-rule", coexistTarget, score, "score",
                            "return mock.returnValue(" + M3EFixtureSources.C10_ENHANCED_1 + ")"),
                    env.operationTimeoutMillis);
            Integer enhScore = invokeScore(target, 5, env);
            String enhTag = invokeTag(target, env);
            String enhHash = captureAppliedHash(target.http, target.port, target.token,
                    coexistTarget.classId, env);
            boolean enhanceOk = enhResp.statusCode() == 201
                    && enhScore != null && enhScore == M3EFixtureSources.C10_ENHANCED_1
                    && enhHash != null && !enhHash.equals(baselineHash);
            boolean enhancePreserves = M3EFixtureSources.C10_TAG_BB.equals(enhTag);
            assertions.add(assertion("enhance.real", enhanceOk,
                    "POST /rules -> " + enhResp.statusCode() + "; score " + baselineScore + " -> " + enhScore
                            + "; hash " + shortHash(baselineHash) + " -> " + shortHash(enhHash)));
            assertions.add(assertion("enhance.preserves.foreign", enhancePreserves,
                    "tag during enhance=" + safe(enhTag) + " (expected " + M3EFixtureSources.C10_TAG_BB + ")"));
            order.add("enhance:score=" + enhScore + ",tag=" + enhTag + ",hash=" + shortHash(enhHash));

            // Step 3: Kairo update score(). Behavior must update; BB transform preserved.
            HttpOutcome updResp = httpPut(target.http, target.port, target.token, "/rules/c10-rule",
                    ruleJson("c10-rule", coexistTarget, score, "score",
                            "return mock.returnValue(" + M3EFixtureSources.C10_ENHANCED_2 + ")"),
                    env.operationTimeoutMillis);
            Integer updScore = invokeScore(target, 5, env);
            String updTag = invokeTag(target, env);
            String updHash = captureAppliedHash(target.http, target.port, target.token,
                    coexistTarget.classId, env);
            boolean updatePreserves = updResp.statusCode() == 200
                    && updScore != null && updScore == M3EFixtureSources.C10_ENHANCED_2
                    && M3EFixtureSources.C10_TAG_BB.equals(updTag)
                    && updHash != null;
            assertions.add(assertion("update.preserves.foreign", updatePreserves,
                    "PUT /rules -> " + updResp.statusCode() + "; score=" + updScore + "; tag=" + safe(updTag)
                            + " (expected " + M3EFixtureSources.C10_TAG_BB + "); hash=" + shortHash(updHash)));
            order.add("update:score=" + updScore + ",tag=" + updTag + ",hash=" + shortHash(updHash));

            // Step 4: Kairo unload (precise DELETE). score must restore to the original body
            // (Kairo advice removed) while the BB transform must STILL be active (tag="BB",
            // not the original ""). The BB transform being still observable after Kairo's
            // enhance/update/unload is the section-10.4.5 proof that Kairo does not remove or
            // break the other agent's transformation. The transformation hashes are recorded
            // as evidence; byte-exact equality is NOT asserted because ByteBuddy's Advice
            // re-weaves non-identically on each retransform (retransform re-runs transformers
            // on the original bytes), which is a ByteBuddy characteristic, not a Kairo guarantee.
            HttpOutcome del = httpDelete(target.http, target.port, target.token, "/rules/c10-rule",
                    env.operationTimeoutMillis);
            Integer restoredScore = invokeScore(target, 5, env);
            String restoredTag = invokeTag(target, env);
            String restoredHash = captureAppliedHash(target.http, target.port, target.token,
                    coexistTarget.classId, env);
            boolean behaviorPreserved = del.statusCode() == 200
                    && restoredScore != null && restoredScore == M3EFixtureSources.C10_BASELINE
                    && M3EFixtureSources.C10_TAG_BB.equals(restoredTag);
            boolean transformationPreserved = restoredTag != null
                    && M3EFixtureSources.C10_TAG_BB.equals(restoredTag)
                    && !M3EFixtureSources.C10_TAG_ORIGINAL.equals(restoredTag)
                    && restoredHash != null;
            assertions.add(assertion("unload.preserves.foreign.behavior", behaviorPreserved,
                    "DELETE -> " + del.statusCode() + "; score restored=" + restoredScore
                            + " (expected " + M3EFixtureSources.C10_BASELINE + ", Kairo advice removed); tag="
                            + safe(restoredTag) + " (expected " + M3EFixtureSources.C10_TAG_BB + ")"));
            assertions.add(assertion("unload.preserves.foreign.transformation", transformationPreserved,
                    "BB transform still active after unload: tag=" + safe(restoredTag) + " (expected "
                            + M3EFixtureSources.C10_TAG_BB + " != original \"" + M3EFixtureSources.C10_TAG_ORIGINAL
                            + "\"); transformation hashes baseline=" + shortHash(baselineHash)
                            + " enhance=" + shortHash(enhHash) + " update=" + shortHash(updHash)
                            + " unload=" + shortHash(restoredHash)));
            order.add("unload:score=" + restoredScore + ",tag=" + restoredTag + ",hash=" + shortHash(restoredHash));

            assertions.add(assertion("execution.order", true, String.join(" | ", order)));
            boolean aggregate = bbInstalled && baselineForeignPresent && enhanceOk && enhancePreserves
                    && updatePreserves && behaviorPreserved && transformationPreserved;
            assertions.add(assertion("Kairo 卸载不破坏对方变换", aggregate,
                    "bbInstalled=" + bbInstalled + "; baselineForeign=" + baselineForeignPresent
                            + "; enhancePreserves=" + enhancePreserves + "; updatePreserves=" + updatePreserves
                            + "; unloadBehaviorPreserved=" + behaviorPreserved
                            + "; unloadTransformPreserved=" + transformationPreserved));
            if (!aggregate && failure[0].isEmpty()) {
                failure[0] = "C10 coexistence: Kairo enhance/update/unload did not preserve the BB transform";
            }
            String reason = aggregate ? "" : failure[0];
            return finish(true, childPid[0], env, targetJdk[0], launchCommand, "",
                    stdoutArtifact, stderrArtifact, assertions, reason);
        } finally {
            if (target != null) {
                target.close(env);
            }
        }
    }

    // ============================================================ shared launch
    private TargetHandle launch(CompatibilityScenario scenario, RealExecEnv env, Path jdkHome,
                                List<AgentSpec> beforeKairo, List<AgentSpec> afterKairo,
                                String classpath, String extraProperty,
                                String mainClass, Path runDir, Path stdoutArtifact, Path stderrArtifact,
                                String[] failure, String[] targetJdk, int[] childPid) throws Exception {
        Files.writeString(stdoutArtifact, "", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(stderrArtifact, "", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        Path targetJava = jdkHome.resolve("bin").resolve("java");
        if (!Files.isExecutable(targetJava)) {
            failure[0] = "target java not executable: " + targetJava;
            return null;
        }
        int port = allocateLoopbackPort();
        String token = "kairo-compat-" + scenario.id() + "-" + env.runnerPid + "-" + port;
        // The Kairo agent is always present; its port+token are allocated here and shared with
        // the HTTP client below so the two never disagree.
        String kairoArgs = "coreJar=" + env.coreJar
                + ",bootstrapJar=" + env.bootstrapApiJar
                + ",host=127.0.0.1,port=" + port + ",token=" + token;
        List<AgentSpec> agents = new ArrayList<>();
        agents.addAll(beforeKairo);
        agents.add(new AgentSpec(env.bootstrapJar, kairoArgs));
        agents.addAll(afterKairo);
        List<String> cmd = new ArrayList<>();
        cmd.add(targetJava.toString());
        for (AgentSpec a : agents) {
            String arg = a.args == null || a.args.isEmpty() ? "" : "=" + a.args;
            cmd.add("-javaagent:" + a.jar + arg);
        }
        if (extraProperty != null && !extraProperty.isEmpty()) {
            cmd.add(extraProperty);
        }
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(mainClass);
        String launchCommand = String.join(" ", cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process proc = pb.start();
        long pid = proc.pid();
        if (pid <= 0 || pid > Integer.MAX_VALUE) {
            failure[0] = "launched target PID outside row schema range: " + pid;
            return null;
        }
        childPid[0] = (int) pid;
        Writer stdin = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8);
        OutputPump stdout = new OutputPump(proc.getInputStream(), stdoutArtifact);
        stdout.start();
        Thread stderr = pumpToFile(proc.getErrorStream(), stderrArtifact);
        stderr.setDaemon(true);
        stderr.start();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // The C08 harness has TWO premain agents (kairo + harness); the harness prints
        // HARNESS_AGENT_INSTALLED before READY. The C10 target prints BB_AGENT_INSTALLED.
        // readReady skips non-READY lines up to the deadline so those markers do not block.
        return new TargetHandle(proc, stdout, stderr, stdin, http, port, token, launchCommand, stderrArtifact);
    }

    private boolean readReady(TargetHandle t, RealExecEnv env, String[] failure,
                              String[] targetJdk, int[] childPid) throws Exception {
        String ready = null;
        long deadline = System.currentTimeMillis() + env.startupTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            ready = t.stdout.nextLine(500, TimeUnit.MILLISECONDS);
            if (ready != null && ready.startsWith("READY")) {
                break;
            }
            if (!t.process.isAlive()) {
                failure[0] = "target exited before READY (stderr=" + readShort(t.stderrArtifact) + ")";
                return false;
            }
            ready = null;
        }
        if (ready == null || !ready.startsWith("READY")) {
            failure[0] = "target did not print READY within " + env.startupTimeoutMillis + "ms";
            return false;
        }
        Map<String, String> fields = parseReady(ready);
        int reportedPid = parseIntOr(fields.get("pid"), 0);
        targetJdk[0] = fields.getOrDefault("jdk", "");
        if (reportedPid <= 0 || reportedPid != childPid[0]) {
            failure[0] = "target READY pid " + reportedPid + " does not match Process.pid() " + childPid[0];
            return false;
        }
        if (childPid[0] == env.runnerPid) {
            failure[0] = "target pid " + childPid[0] + " equals runner pid " + env.runnerPid;
            return false;
        }
        return true;
    }

    private boolean compileFixture(RealExecEnv env, Path jdkHome, List<Path> sources,
                                   Path classesDir, List<Path> classpathJars, Path runDir,
                                   String[] failure) throws Exception {
        Path javac = jdkHome.resolve("bin").resolve("javac");
        if (!Files.isExecutable(javac)) {
            failure[0] = "target javac not executable: " + javac;
            return false;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(javac.toString());
        cmd.add("-d");
        cmd.add(classesDir.toString());
        String cp = joinPaths(classpathJars);
        if (!cp.isEmpty()) {
            cmd.add("-cp");
            cmd.add(cp);
        }
        for (Path s : sources) {
            cmd.add(s.toString());
        }
        Path out = runDir.resolve("javac.output");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile()).start();
        boolean done = p.waitFor(env.startupTimeoutMillis, TimeUnit.MILLISECONDS);
        if (!done) {
            p.destroyForcibly();
            p.waitFor(env.operationTimeoutMillis, TimeUnit.MILLISECONDS);
        }
        String log = Files.isRegularFile(out) ? Files.readString(out, StandardCharsets.UTF_8) : "";
        boolean ok = done && p.exitValue() == 0;
        if (!ok) {
            failure[0] = "fixture compile failed (exit="
                    + (p.isAlive() ? "timeout" : p.exitValue()) + "): " + log;
        }
        return ok;
    }

    /** Packages a Premain-Class agent jar from a manifest and the compiled .class files. */
    private boolean packageAgentJar(Path jar, Path manifestFile, List<Path> classFiles,
                                    String[] failure) {
        try (JarOutputStream jos = new JarOutputStream(
                Files.newOutputStream(jar), new Manifest(Files.newInputStream(manifestFile)))) {
            for (Path classFile : classFiles) {
                String entry = classFile.getFileName().toString();
                JarEntry je = new JarEntry(entry);
                jos.putNextEntry(je);
                Files.copy(classFile, jos);
                jos.closeEntry();
            }
            return true;
        } catch (Exception e) {
            failure[0] = "agent jar packaging failed for " + jar + ": " + e.getClass().getSimpleName()
                    + ": " + e.getMessage();
            return false;
        }
    }

    // --------------------------------------------------------------- http helpers
    private boolean awaitHealth(HttpClient http, int port, String token, RealExecEnv env) throws Exception {
        long deadline = System.currentTimeMillis() + env.startupTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> h = httpGet(http, port, token, "/health");
                if (h.statusCode() == 200 && h.body().contains("UP")) {
                    return true;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            Thread.sleep(200);
        }
        return false;
    }

    private ResolvedClass resolveClassByExactName(HttpClient http, int port, String token,
                                                   String exactClassName, RealExecEnv env, String label) throws Exception {
        long deadline = System.currentTimeMillis() + env.operationTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> resp = httpGet(http, port, token, "/classes?keyword=" + exactClassName);
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
                if (exactClassName.equals(c.path("className").asText())) {
                    return new ResolvedClass(c.path("classId").asText(),
                            c.path("classLoaderId").asText(), exactClassName);
                }
            }
            break;
        }
        return null;
    }

    private List<MethodEntry> listMethods(HttpClient http, int port, String token, String classId,
                                          RealExecEnv env) throws Exception {
        HttpResponse<String> resp = httpGet(http, port, token, "/classes/" + classId + "/methods");
        List<MethodEntry> out = new ArrayList<>();
        if (resp.statusCode() != 200) {
            return out;
        }
        JsonNode arr = MAPPER.readTree(resp.body());
        if (arr.isArray()) {
            for (JsonNode m : arr) {
                out.add(new MethodEntry(m.path("name").asText(), m.path("descriptor").asText()));
            }
        }
        return out;
    }

    private ResolvedMethod resolveMethod(HttpClient http, int port, String token, String classId,
                                         String name, String descriptor, RealExecEnv env) throws Exception {
        for (MethodEntry m : listMethods(http, port, token, classId, env)) {
            if (m.name.equals(name) && m.descriptor.equals(descriptor)) {
                return new ResolvedMethod(classId, name, descriptor);
            }
        }
        return null;
    }

    private String captureAppliedHash(HttpClient http, int port, String token, String classId,
                                      RealExecEnv env) {
        try {
            HttpOutcome resp = httpPost(http, port, token, "/classes/" + classId + "/capture",
                    "", env.operationTimeoutMillis);
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonNode node = MAPPER.readTree(resp.body());
            String hash = node.path("appliedHash").asText("");
            return node.path("captured").asBoolean(false) && !hash.isBlank() ? hash : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads /events and returns the events whose type equals {@code type}. */
    private List<RuntimeEvent> eventsOfType(HttpClient http, int port, String token, String type,
                                            RealExecEnv env) {
        List<RuntimeEvent> out = new ArrayList<>();
        try {
            HttpResponse<String> resp = httpGet(http, port, token, "/events");
            if (resp.statusCode() != 200) {
                return out;
            }
            JsonNode arr = MAPPER.readTree(resp.body());
            if (arr.isArray()) {
                for (JsonNode e : arr) {
                    if (type.equals(e.path("type").asText())) {
                        out.add(new RuntimeEvent(e.path("type").asText(), e.path("target").asText(null),
                                e.path("message").asText(null)));
                    }
                }
            }
        } catch (Exception ignored) {
            // best effort
        }
        return out;
    }

    private String ruleJson(String id, ResolvedClass cls, ResolvedMethod method, String methodName, String script) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", id);
        rule.put("name", id);
        rule.put("classId", cls.classId);
        rule.put("className", cls.className);
        rule.put("classLoaderId", cls.classLoaderId);
        rule.put("methodName", methodName);
        rule.put("methodDescriptor", method.descriptor);
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

    private Integer invokeScore(TargetHandle t, int x, RealExecEnv env) throws Exception {
        String line = commandAndRead(t, "INVOKE SCORE " + x, "RESULT SCORE", env);
        if (line == null) {
            return null;
        }
        try {
            return Integer.parseInt(line.substring("RESULT SCORE".length()).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String invokeTag(TargetHandle t, RealExecEnv env) throws Exception {
        String line = commandAndRead(t, "INVOKE TAG", "RESULT TAG", env);
        if (line == null) {
            return null;
        }
        return line.substring("RESULT TAG".length()).trim();
    }

    private String commandAndRead(TargetHandle t, String command, String prefix, RealExecEnv env) throws Exception {
        t.stdin.write(command + "\n");
        t.stdin.flush();
        String line = t.stdout.nextLine(env.operationTimeoutMillis, TimeUnit.MILLISECONDS);
        if (line == null || !line.startsWith(prefix)) {
            return null;
        }
        return line;
    }

    // --------------------------------------------------------------- outcome helpers
    private M3EExecutionOutcome outcome(boolean started, int childPid, RealExecEnv env, String targetJdk,
                                        String launchCommand, String attachCommand, Path stdout, Path stderr,
                                        List<CompatibilityRowRunner.Assertion> assertions, String failureReason) {
        boolean independent = childPid > 0 && childPid != env.runnerPid;
        return new M3EExecutionOutcome(started, childPid, independent, targetJdk,
                launchCommand, attachCommand, stdout, stderr, new ArrayList<>(assertions), failureReason);
    }

    private M3EExecutionOutcome finish(boolean started, int childPid, RealExecEnv env, String targetJdk,
                                       String launchCommand, String attachCommand, Path stdout, Path stderr,
                                       List<CompatibilityRowRunner.Assertion> assertions, String failureReason) {
        if (childPid > 0) {
            assertions.add(assertion("evidence.launchCommand", true, launchCommand));
            assertions.add(assertion("evidence.stdoutArtifact", Files.isRegularFile(stdout), stdout.toString()));
            assertions.add(assertion("evidence.stderrArtifact", Files.isRegularFile(stderr), stderr.toString()));
        }
        return outcome(started, childPid, env, targetJdk, launchCommand, attachCommand,
                stdout, stderr, assertions, failureReason);
    }

    // --------------------------------------------------------------- misc helpers
    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "<none>";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private static String firstDriftMessage(List<RuntimeEvent> events) {
        return events.stream().findFirst().map(e -> safe(e.message)).orElse("<no message>");
    }

    private static boolean readArtifactContains(Path file, String needle) {
        try {
            return Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).contains(needle);
        } catch (Exception e) {
            return false;
        }
    }

    private static String joinPaths(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Path p : paths) {
            if (p == null) {
                continue;
            }
            sb.append(":").append(p.toString());
        }
        return sb.toString();
    }

    private static String readShort(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static int allocateLoopbackPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("could not allocate a loopback port", e);
        }
    }

    private static CompatibilityRowRunner.Assertion assertion(String name, boolean passed, String detail) {
        return new CompatibilityRowRunner.Assertion(name, passed, detail);
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
        }, "kairo-compat-m3e-stderr");
    }

    // --------------------------------------------------------------- inner types
    private record AgentSpec(Path jar, String args) {
    }

    private record ResolvedClass(String classId, String classLoaderId, String className) {
    }

    private record ResolvedMethod(String classId, String name, String descriptor) {
    }

    private record MethodEntry(String name, String descriptor) {
    }

    private record HttpOutcome(int statusCode, String body) {
    }

    private record RuntimeEvent(String type, String target, String message) {
    }

    /** A live target process + its IO pumps + agent HTTP client. */
    private static final class TargetHandle {
        final Process process;
        final OutputPump stdout;
        final Thread stderrThread;
        final Writer stdin;
        final HttpClient http;
        final int port;
        final String token;
        final String launchCommand;
        final Path stderrArtifact;

        TargetHandle(Process process, OutputPump stdout, Thread stderrThread, Writer stdin,
                     HttpClient http, int port, String token, String launchCommand, Path stderrArtifact) {
            this.process = process;
            this.stdout = stdout;
            this.stderrThread = stderrThread;
            this.stdin = stdin;
            this.http = http;
            this.port = port;
            this.token = token;
            this.launchCommand = launchCommand;
            this.stderrArtifact = stderrArtifact;
        }

        void close(RealExecEnv env) {
            try {
                stdin.close();
            } catch (Exception ignored) {
                // best effort
            }
            stdout.stop();
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(env.operationTimeoutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (stderrThread != null) {
                stderrThread.interrupt();
            }
        }
    }

    /** Pumps target stdout line-by-line into an artifact file AND a queue. */
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
            }, "kairo-compat-m3e-stdout");
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
