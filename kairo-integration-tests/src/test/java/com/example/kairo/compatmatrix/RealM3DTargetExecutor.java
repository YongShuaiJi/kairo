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
 * The real M3-D target executor. Launches a <strong>genuinely independent</strong>
 * target JVM (premain for C05/C06/C07), drives the real Kairo agent load path, and
 * proves the C05/C06/C07 fixed behaviors against the agent's real loopback HTTP API.
 *
 * <p>Nothing here is hard-coded: the child PID, target JDK, launch/attach commands and
 * the printed RESULT values all come from the real target process. Assertions are
 * derived from target behavior. The executor always cleans up the child(ren) and
 * bounds every wait.
 *
 * <ul>
 *   <li>C05: two same-name loaders (parent + child-first); proves only the designated
 *       loader is enhanced while the sibling's behavior AND applied bytecode hash stay
 *       unchanged, then precise unload/restore.</li>
 *   <li>C06: genuine JDK Proxy, genuine CGLIB subclass and genuine Byte Buddy subclass,
 *       each delegating to a real ProxyTarget; proves target resolution, real
 *       invocation/enhancement through every proxy, and precise unload/restore.</li>
 *   <li>C07: a lambda path, a compiler bridge and a synthetic method; proves the
 *   discovery policy (synthetic/bridge hidden from /methods), enhances the stable
 *   concrete method, invokes through the lambda/bridge path, and precisely unloads.
 *   Runs on BOTH JDK 17 and JDK 21 (two independent target JVMs); the higher JDK is
 *   recorded as the row's primary target JVM, the other carried in assertion detail.</li>
 * </ul>
 */
final class RealM3DTargetExecutor implements M3DTargetExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public M3DExecutionOutcome execute(CompatibilityScenario scenario, RealExecEnv env,
                                      Path targetJdkHome, M3DAuxJars auxJars) {
        try {
            return switch (scenario.id()) {
                case "C05" -> executeC05(scenario, env, targetJdkHome);
                case "C06" -> executeC06(scenario, env, targetJdkHome, auxJars);
                case "C07" -> executeC07(scenario, env);
                default -> new M3DExecutionOutcome(false, 0, false, "", "", "", null, null,
                        List.of(), "M3-D has no executor for scenario " + scenario.id());
            };
        } catch (Exception e) {
            return new M3DExecutionOutcome(false, 0, false, "", "", "", null, null,
                    List.of(), "executor error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ============================================================ C05: loaders
    private M3DExecutionOutcome executeC05(CompatibilityScenario scenario, RealExecEnv env,
                                           Path targetJdkHome) throws Exception {
        Path runDir = env.workDir.resolve("run-C05-" + env.runnerPid);
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
            M3DFixtureSources fixtures = new M3DFixtureSources(runDir);
            Path targetSrc = fixtures.writeSource(M3DFixtureSources.C05_TARGET_CLASS,
                    M3DFixtureSources.C05_TARGET_SOURCE);
            Path harnessSrc = fixtures.writeSource(M3DFixtureSources.C05_HARNESS_CLASS,
                    M3DFixtureSources.C05_HARNESS_SOURCE);
            Path classesDir = fixtures.classDirectory();
            Files.createDirectories(classesDir);
            if (!compileFixture(env, targetJdkHome, List.of(targetSrc, harnessSrc), classesDir, List.of(),
                    runDir, failure)) {
                return outcome(false, 0, env, "", "", "", stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            target = launch(scenario, env, targetJdkHome, classesDir, List.of(),
                    M3DFixtureSources.C05_HARNESS_CLASS, runDir, stdoutArtifact, stderrArtifact,
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

            // Discover both same-name classes via the real API.
            ResolvedClass classA = resolveClassByExactName(target.http, target.port, target.token,
                    M3DFixtureSources.C05_TARGET_CLASS, env, "C05 discover A");
            ResolvedClass classB = null;
            if (classA != null) {
                // The second entry with the same className but a different classLoaderId.
                classB = resolveSecondLoaderClass(target.http, target.port, target.token,
                        M3DFixtureSources.C05_TARGET_CLASS, classA.classLoaderId, env);
            }
            boolean discoveredBoth = classA != null && classB != null
                    && !classA.classLoaderId.equals(classB.classLoaderId);
            assertions.add(assertion("发现.A", classA != null,
                    classA == null ? "LoaderTarget not found in /classes" : "classId=" + classA.classId));
            assertions.add(assertion("发现.B", classB != null,
                    classB == null ? "second loader's LoaderTarget not found" : "classId=" + classB.classId));
            if (!discoveredBoth) {
                failure[0] = "could not discover two distinct same-name loaders";
                assertions.add(assertion("只增强指定 loader", false, failure[0]));
                return finish(true, childPid[0], env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }

            // Baseline behavior of both loaders (harness INVOKE A = parent, INVOKE B = child;
            // their classId ordering is unspecified, so the proof below does NOT assume which
            // harness label maps to which classId).
            Integer aBase = invokeAndRead(target, "INVOKE A", "RESULT A", env);
            Integer bBase = invokeAndRead(target, "INVOKE B", "RESULT B", env);
            boolean baseOk = aBase != null && aBase == M3DFixtureSources.C05_BASELINE
                    && bBase != null && bBase == M3DFixtureSources.C05_BASELINE;
            assertions.add(assertion("baseline.both", baseOk,
                    "A=" + aBase + " B=" + bBase + " (expected " + M3DFixtureSources.C05_BASELINE + ")"));

            // Capture the actual bytes before enhancement. A journal count alone is not
            // sufficient evidence: a failed diagnostics request used to look identical to
            // an unchanged zero-entry journal. The applied hashes make the C05 isolation
            // claim directly falsifiable.
            String designatedHashBefore = captureAppliedHash(target.http, target.port, target.token,
                    classA.classId, env);
            String siblingHashBefore = captureAppliedHash(target.http, target.port, target.token,
                    classB.classId, env);

            // Enhance ONLY the designated loader (classA's classId). Prove that exactly ONE
            // loader's behavior changed to ENHANCED while the OTHER (the sibling, classB's
            // classId) stayed at BASELINE - i.e. the agent isolated the enhancement to the
            // designated classId regardless of the harness's A/B invocation labels.
            ResolvedMethod aScore = resolveMethod(target.http, target.port, target.token,
                    classA.classId, "score", "()I", env);
            boolean enhanceOk = false;
            Integer aDuring = null;
            Integer bDuring = null;
            if (aScore != null) {
                HttpOutcome resp = httpPost(target.http, target.port, target.token, "/rules",
                        ruleJson("c05-enhance", classA, aScore, "score",
                                "return mock.returnValue(" + M3DFixtureSources.C05_ENHANCED + ")"),
                        env.operationTimeoutMillis);
                aDuring = invokeAndRead(target, "INVOKE A", "RESULT A", env);
                bDuring = invokeAndRead(target, "INVOKE B", "RESULT B", env);
                boolean aEnh = aDuring != null && aDuring == M3DFixtureSources.C05_ENHANCED;
                boolean bEnh = bDuring != null && bDuring == M3DFixtureSources.C05_ENHANCED;
                boolean aBase2 = aDuring != null && aDuring == M3DFixtureSources.C05_BASELINE;
                boolean bBase2 = bDuring != null && bDuring == M3DFixtureSources.C05_BASELINE;
                // Exactly one loader enhanced; the other (sibling) unchanged at baseline.
                enhanceOk = resp.statusCode() == 201
                        && ((aEnh && bBase2) || (bEnh && aBase2));
                assertions.add(assertion("enhance.designated", enhanceOk,
                        "POST /rules -> " + resp.statusCode() + " (targeted classId "
                                + classA.classLoaderId + "); A=" + aDuring + " B=" + bDuring
                                + " (expected exactly one " + M3DFixtureSources.C05_ENHANCED
                                + ", one " + M3DFixtureSources.C05_BASELINE + ")"));
            } else {
                assertions.add(assertion("enhance.designated", false, "could not resolve designated score()"));
            }

            // Capture both actual byte arrays after enhancement. The sibling hash must
            // remain byte-for-byte identical and the designated class hash must change.
            String designatedHashAfter = captureAppliedHash(target.http, target.port, target.token,
                    classA.classId, env);
            String siblingHashAfter = captureAppliedHash(target.http, target.port, target.token,
                    classB.classId, env);
            boolean siblingBytecodeUnchanged = siblingHashBefore != null
                    && siblingHashBefore.equals(siblingHashAfter);
            boolean designatedBytecodeChanged = designatedHashBefore != null
                    && designatedHashAfter != null
                    && !designatedHashBefore.equals(designatedHashAfter);
            boolean siblingBehaviorUnchanged = aDuring != null && bDuring != null
                    && ((aDuring == M3DFixtureSources.C05_BASELINE) ^ (bDuring == M3DFixtureSources.C05_BASELINE))
                    && ((aDuring == M3DFixtureSources.C05_ENHANCED) ^ (bDuring == M3DFixtureSources.C05_ENHANCED));
            assertions.add(assertion("sibling.unchanged.behavior", siblingBehaviorUnchanged,
                    "while classId " + classA.classLoaderId + " enhanced: A=" + aDuring + " B=" + bDuring
                            + " (sibling must stay " + M3DFixtureSources.C05_BASELINE + ")"));
            assertions.add(assertion("sibling.unchanged.bytecodeHash", siblingBytecodeUnchanged,
                    "sibling(classLoaderId " + classB.classLoaderId + ") appliedHash "
                            + siblingHashBefore + " -> " + siblingHashAfter));
            assertions.add(assertion("designated.bytecodeHash.changed", designatedBytecodeChanged,
                    "designated(classLoaderId " + classA.classLoaderId + ") appliedHash "
                            + designatedHashBefore + " -> " + designatedHashAfter));

            // Precise unload/restore: BOTH loaders must return baseline after unload.
            HttpOutcome del = httpDelete(target.http, target.port, target.token, "/rules/c05-enhance",
                    env.operationTimeoutMillis);
            Integer aRestored = invokeAndRead(target, "INVOKE A", "RESULT A", env);
            Integer bRestored = invokeAndRead(target, "INVOKE B", "RESULT B", env);
            String designatedHashRestored = captureAppliedHash(target.http, target.port, target.token,
                    classA.classId, env);
            String siblingHashRestored = captureAppliedHash(target.http, target.port, target.token,
                    classB.classId, env);
            boolean bytecodeRestored = designatedHashBefore != null
                    && designatedHashBefore.equals(designatedHashRestored)
                    && siblingHashBefore != null
                    && siblingHashBefore.equals(siblingHashRestored);
            boolean unloadOk = del.statusCode() == 200
                    && aRestored != null && aRestored == M3DFixtureSources.C05_BASELINE
                    && bRestored != null && bRestored == M3DFixtureSources.C05_BASELINE
                    && bytecodeRestored;
            assertions.add(assertion("unload.restore", unloadOk,
                    "DELETE -> " + del.statusCode() + "; A=" + aRestored + " B=" + bRestored
                            + " (expected both " + M3DFixtureSources.C05_BASELINE + "); designated hash="
                            + designatedHashRestored + "; sibling hash=" + siblingHashRestored));

            // Aggregate required behavior.
            boolean aggregate = baseOk && enhanceOk && siblingBytecodeUnchanged
                    && siblingBehaviorUnchanged && designatedBytecodeChanged && unloadOk;
            assertions.add(assertion("只增强指定 loader", aggregate,
                    "designated enhanced+isolated+restored=" + enhanceOk + "&&" + siblingBehaviorUnchanged
                            + "&&" + unloadOk + "; sibling bytecode unchanged=" + siblingBytecodeUnchanged
                            + "; designated bytecode changed=" + designatedBytecodeChanged));
            if (!aggregate && failure[0].isEmpty()) {
                failure[0] = "C05 designated-loader enhancement or sibling isolation failed";
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

    // ============================================================ C06: proxies
    private M3DExecutionOutcome executeC06(CompatibilityScenario scenario, RealExecEnv env,
                                           Path targetJdkHome, M3DAuxJars auxJars) throws Exception {
        Path runDir = env.workDir.resolve("run-C06-" + env.runnerPid);
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
            M3DFixtureSources fixtures = new M3DFixtureSources(runDir);
            Path targetSrc = fixtures.writeSource(M3DFixtureSources.C06_TARGET_CLASS,
                    M3DFixtureSources.C06_TARGET_SOURCE);
            Path harnessSrc = fixtures.writeSource(M3DFixtureSources.C06_HARNESS_CLASS,
                    M3DFixtureSources.C06_HARNESS_SOURCE);
            Path classesDir = fixtures.classDirectory();
            Files.createDirectories(classesDir);
            List<Path> auxCp = List.of(auxJars.byteBuddyJar, auxJars.springCoreJar);
            if (!compileFixture(env, targetJdkHome, List.of(targetSrc, harnessSrc), classesDir, auxCp,
                    runDir, failure)) {
                return outcome(false, 0, env, "", "", "", stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            target = launch(scenario, env, targetJdkHome, classesDir, auxCp,
                    M3DFixtureSources.C06_HARNESS_CLASS, runDir, stdoutArtifact, stderrArtifact,
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

            // Prove the harness really constructed all three runtime proxy forms. Merely
            // compiling imports or labeling an assertion "CGLIB" is not evidence.
            String types = commandAndRead(target, "TYPES", "TYPES", env);
            boolean realJdkProxy = types != null && types.contains("jdkProxy=true");
            boolean realCglibProxy = types != null && types.contains("cglibFactory=true");
            boolean realByteBuddyTarget = types != null
                    && types.contains("byteBuddySubclass=true") && types.contains("ByteBuddy");
            boolean targetRelationships = types != null
                    && types.contains("jdkTargetClass=" + M3DFixtureSources.C06_TARGET_CLASS)
                    && types.contains("cglibSuper=" + M3DFixtureSources.C06_TARGET_CLASS)
                    && types.contains("byteBuddySuper=" + M3DFixtureSources.C06_TARGET_CLASS);
            assertions.add(assertion("jdk.proxy.real", realJdkProxy, safe(types)));
            assertions.add(assertion("cglib.proxy.real", realCglibProxy, safe(types)));
            assertions.add(assertion("bytebuddy.target.real", realByteBuddyTarget, safe(types)));
            assertions.add(assertion("proxy.target.relationships", targetRelationships, safe(types)));

            // Target resolution: discover the real ProxyTarget (not its generated proxies).
            ResolvedClass proxyTarget = resolveClassByExactName(target.http, target.port, target.token,
                    M3DFixtureSources.C06_TARGET_CLASS, env, "C06 target resolution");
            boolean resolved = proxyTarget != null;
            assertions.add(assertion("目标解析", resolved,
                    proxyTarget == null ? "ProxyTarget not found in /classes"
                            : "classId=" + proxyTarget.classId + " loader=" + proxyTarget.classLoaderId));
            if (!resolved) {
                failure[0] = "could not resolve ProxyTarget via /classes";
                assertions.add(assertion("目标解析与精确卸载", false, failure[0]));
                return finish(true, childPid[0], env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, failure[0]);
            }
            ResolvedMethod getAsInt = resolveMethod(target.http, target.port, target.token,
                    proxyTarget.classId, "getAsInt", "()I", env);
            if (getAsInt == null) {
                assertions.add(assertion("目标解析与精确卸载", false, "could not resolve getAsInt()"));
                return finish(true, childPid[0], env, targetJdk[0], launchCommand, "",
                        stdoutArtifact, stderrArtifact, assertions, "could not resolve getAsInt()");
            }

            // Baseline through every proxy type.
            Integer jdkBase = invokeAndRead(target, "INVOKE JDK", "RESULT JDK", env);
            Integer cglibBase = invokeAndRead(target, "INVOKE CGLIB", "RESULT CGLIB", env);
            Integer bbBase = invokeAndRead(target, "INVOKE BYTEBUDDY", "RESULT BYTEBUDDY", env);
            Integer directBase = invokeAndRead(target, "INVOKE DIRECT", "RESULT DIRECT", env);
            boolean baseOk = allOf(M3DFixtureSources.C06_BASELINE, jdkBase, cglibBase, bbBase, directBase);
            assertions.add(assertion("baseline.proxies", baseOk,
                    "jdk=" + jdkBase + " cglib=" + cglibBase + " bytebuddy=" + bbBase
                            + " direct=" + directBase + " (expected " + M3DFixtureSources.C06_BASELINE + ")"));

            // Enhance getAsInt() on the resolved target.
            HttpOutcome resp = httpPost(target.http, target.port, target.token, "/rules",
                    ruleJson("c06-enhance", proxyTarget, getAsInt, "getAsInt",
                            "return mock.returnValue(" + M3DFixtureSources.C06_ENHANCED + ")"),
                    env.operationTimeoutMillis);
            Integer jdkEnh = invokeAndRead(target, "INVOKE JDK", "RESULT JDK", env);
            Integer cglibEnh = invokeAndRead(target, "INVOKE CGLIB", "RESULT CGLIB", env);
            Integer bbEnh = invokeAndRead(target, "INVOKE BYTEBUDDY", "RESULT BYTEBUDDY", env);
            Integer directEnh = invokeAndRead(target, "INVOKE DIRECT", "RESULT DIRECT", env);
            boolean jdkOk = resp.statusCode() == 201 && equalsOf(M3DFixtureSources.C06_ENHANCED, jdkEnh);
            boolean cglibOk = equalsOf(M3DFixtureSources.C06_ENHANCED, cglibEnh);
            boolean bbOk = equalsOf(M3DFixtureSources.C06_ENHANCED, bbEnh);
            boolean directOk = equalsOf(M3DFixtureSources.C06_ENHANCED, directEnh);
            assertions.add(assertion("jdk.proxy", jdkOk,
                    "POST -> " + resp.statusCode() + "; JDK proxy " + jdkBase + " -> " + jdkEnh));
            assertions.add(assertion("cglib.proxy", cglibOk,
                    "CGLIB proxy " + cglibBase + " -> " + cglibEnh
                            + " (genuine org.springframework.cglib.proxy.Enhancer)"));
            assertions.add(assertion("bytebuddy.target", bbOk,
                    "Byte Buddy subclass " + bbBase + " -> " + bbEnh
                            + " (genuine net.bytebuddy runtime-generated subclass)"));
            assertions.add(assertion("direct.target", directOk,
                    "resolved target " + directBase + " -> " + directEnh));

            // Precise unload/restore.
            HttpOutcome del = httpDelete(target.http, target.port, target.token, "/rules/c06-enhance",
                    env.operationTimeoutMillis);
            Integer jdkRes = invokeAndRead(target, "INVOKE JDK", "RESULT JDK", env);
            Integer cglibRes = invokeAndRead(target, "INVOKE CGLIB", "RESULT CGLIB", env);
            Integer bbRes = invokeAndRead(target, "INVOKE BYTEBUDDY", "RESULT BYTEBUDDY", env);
            Integer directRes = invokeAndRead(target, "INVOKE DIRECT", "RESULT DIRECT", env);
            boolean unloadOk = del.statusCode() == 200
                    && allOf(M3DFixtureSources.C06_BASELINE, jdkRes, cglibRes, bbRes, directRes);
            assertions.add(assertion("unload.restore", unloadOk,
                    "DELETE -> " + del.statusCode() + "; jdk=" + jdkRes + " cglib=" + cglibRes
                            + " bytebuddy=" + bbRes + " direct=" + directRes
                            + " (expected " + M3DFixtureSources.C06_BASELINE + ")"));

            boolean aggregate = realJdkProxy && realCglibProxy && realByteBuddyTarget && targetRelationships
                    && resolved && baseOk && jdkOk && cglibOk && bbOk && directOk && unloadOk;
            assertions.add(assertion("目标解析与精确卸载", aggregate,
                    "realProxyTypes=" + (realJdkProxy && realCglibProxy && realByteBuddyTarget)
                            + "; targetRelationships=" + targetRelationships
                            + "; resolved=" + resolved + "; jdk=" + jdkOk + "; cglib=" + cglibOk
                            + "; bytebuddy=" + bbOk + "; unload=" + unloadOk));
            if (!aggregate && failure[0].isEmpty()) {
                failure[0] = "C06 proxy target resolution or enhancement or unload failed";
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

    // ============================================================ C07: lambda/bridge/synthetic
    private M3DExecutionOutcome executeC07(CompatibilityScenario scenario, RealExecEnv env) throws Exception {
        // C07 must exercise BOTH JDK 17 and JDK 21 (two independent target JVMs).
        Path runDir = env.workDir.resolve("run-C07-" + env.runnerPid);
        Files.createDirectories(runDir);
        Path stdoutArtifact = runDir.resolve("target.stdout");
        Path stderrArtifact = runDir.resolve("target.stderr");
        Files.writeString(stdoutArtifact, "", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(stderrArtifact, "", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        String[] failure = {""};
        // Record the higher JDK run as the row's primary target JVM.
        int[] primaryPid = {0};
        String[] primaryJdk = {""};
        String[] primaryLaunch = {""};

        boolean aggregateDiscovery = true;
        boolean aggregatePolicy = true;
        boolean aggregateBehavior = true;
        for (int major : new int[]{17, 21}) {
            Path jdkHome = env.targetJdks.get(major);
            if (jdkHome == null || !Files.isDirectory(jdkHome)) {
                // Gate already required both; if we still miss one, fail truthfully.
                failure[0] = "C07 missing required JDK " + major;
                aggregateDiscovery = aggregatePolicy = aggregateBehavior = false;
                continue;
            }
            String suffix = "JDK" + major;
            C07RunResult r = runC07OnJdk(scenario, env, jdkHome, major, runDir);
            // Append this run's stdout/stderr into the primary artifacts for traceability.
            appendArtifact(stdoutArtifact, r.stdoutSummary);
            appendArtifact(stderrArtifact, r.stderrSummary);
            if (r.targetStarted && r.childPid > 0 && r.failureReason.isBlank() && r.discoveryOk) {
                aggregateDiscovery = aggregateDiscovery && true;
            } else {
                aggregateDiscovery = false;
                if (failure[0].isEmpty()) {
                    failure[0] = "C07 " + suffix + " discovery: " + r.failureReason;
                }
            }
            aggregatePolicy = aggregatePolicy && r.policyOk;
            aggregateBehavior = aggregateBehavior && r.behaviorOk;
            if (!r.policyOk && failure[0].isEmpty()) {
                failure[0] = "C07 " + suffix + " policy failed: " + r.failureReason;
            }
            if (!r.behaviorOk && failure[0].isEmpty()) {
                failure[0] = "C07 " + suffix + " behavior failed: " + r.failureReason;
            }
            // Carry per-JDK assertions with the suffix to preserve subscenario evidence.
            for (CompatibilityRowRunner.Assertion a : r.assertions) {
                assertions.add(new CompatibilityRowRunner.Assertion(
                        a.name() + "." + suffix, a.passed(),
                        a.detail() + " [pid=" + r.childPid + " jdk=" + r.targetJdk + " launch=" + r.launchCommand + "]"));
            }
            // The highest JDK run becomes the primary target JVM recorded in the row.
            if (major == 21 && r.targetStarted && r.childPid > 0) {
                primaryPid[0] = r.childPid;
                primaryJdk[0] = r.targetJdk;
                primaryLaunch[0] = r.launchCommand;
            }
        }
        // Bare aggregate assertions covering the required behaviors across both JDKs.
        assertions.add(assertion("发现", aggregateDiscovery,
                "discovery policy (synthetic/bridge hidden from /methods) verified on JDK 17 and 21"));
        assertions.add(assertion("策略", aggregatePolicy,
                "stable concrete method targeted on JDK 17 and 21"));
        assertions.add(assertion("实际行为", aggregateBehavior,
                "enhancement through lambda and bridge paths + precise unload on JDK 17 and 21"));
        boolean ok = aggregateDiscovery && aggregatePolicy && aggregateBehavior;
        String reason = ok ? "" : (failure[0].isBlank() ? "C07 one or more subscenarios failed" : failure[0]);
        return finish(true, primaryPid[0], env, primaryJdk[0], primaryLaunch[0], "",
                stdoutArtifact, stderrArtifact, assertions, reason);
    }

    /** Runs the full C07 scenario on a single JDK. */
    private C07RunResult runC07OnJdk(CompatibilityScenario scenario, RealExecEnv env, Path jdkHome,
                                     int major, Path parentDir) {
        C07RunResult res = new C07RunResult();
        Path runDir = parentDir.resolve("jdk" + major);
        Path stdoutArtifact = runDir.resolve("target.stdout");
        Path stderrArtifact = runDir.resolve("target.stderr");
        try {
            Files.createDirectories(runDir);
            Files.writeString(stdoutArtifact, "", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(stderrArtifact, "", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            M3DFixtureSources fixtures = new M3DFixtureSources(runDir);
            Path source = fixtures.writeSource(M3DFixtureSources.C07_TARGET_CLASS,
                    M3DFixtureSources.C07_TARGET_SOURCE);
            Path classesDir = fixtures.classDirectory();
            Files.createDirectories(classesDir);
            String[] failure = {""};
            if (!compileFixture(env, jdkHome, List.of(source), classesDir, List.of(), runDir, failure)) {
                res.failureReason = failure[0];
                res.stdoutSummary = "JDK" + major + " compile failed";
                return res;
            }
            TargetHandle target = launch(scenario, env, jdkHome, classesDir, List.of(),
                    M3DFixtureSources.C07_TARGET_CLASS, runDir, stdoutArtifact, stderrArtifact,
                    failure, res.targetJdkArr, res.childPidArr);
            if (target == null) {
                res.failureReason = failure[0].isBlank() ? "target did not start" : failure[0];
                res.stdoutSummary = readShort(stdoutArtifact);
                res.stderrSummary = readShort(stderrArtifact);
                return res;
            }
            res.launchCommand = target.launchCommand;
            try {
                if (!readReady(target, env, failure, res.targetJdkArr, res.childPidArr)) {
                    res.failureReason = failure[0];
                    res.stdoutSummary = readShort(stdoutArtifact);
                    res.stderrSummary = readShort(stderrArtifact);
                    return res;
                }
                res.targetJdk = res.targetJdkArr[0];
                res.childPid = res.childPidArr[0];
                res.targetStarted = true;
                int actualMajor = PlatformNormals.majorJdk(res.targetJdk);
                boolean actualJdkMatches = actualMajor == major;
                res.assertions.add(new CompatibilityRowRunner.Assertion("target.jdk",
                        actualJdkMatches, "expected JDK " + major + ", target reported " + res.targetJdk));
                if (!actualJdkMatches) {
                    res.failureReason = "expected JDK " + major + " but target reported " + res.targetJdk;
                    return res;
                }
                if (!awaitHealth(target.http, target.port, target.token, env)) {
                    res.failureReason = "agent /health never came UP";
                    res.stdoutSummary = readShort(stdoutArtifact);
                    res.stderrSummary = readShort(stderrArtifact);
                    return res;
                }
                runC07Assertions(target, env, res);
                res.stdoutSummary = readShort(stdoutArtifact);
                res.stderrSummary = readShort(stderrArtifact);
                return res;
            } finally {
                target.close(env);
            }
        } catch (Exception e) {
            res.failureReason = "C07 JDK" + major + " error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            res.stdoutSummary = readShort(stdoutArtifact);
            res.stderrSummary = readShort(stderrArtifact);
            return res;
        }
    }

    /** Drives the discovery/policy/behavior assertions on one running C07 target. */
    private void runC07Assertions(TargetHandle target, RealExecEnv env, C07RunResult res) throws Exception {
        // REFLECT: prove the synthetic lambda method and the bridge method genuinely exist.
        target.stdin.write("REFLECT\n");
        target.stdin.flush();
        String reflect = target.stdout.nextLine(env.operationTimeoutMillis, TimeUnit.MILLISECONDS);
        boolean hasSyntheticLambda = reflect != null
                && reflect.contains("LambdaBridgeTarget#lambda$lambdaScore$0 synth=true bridge=false;");
        boolean hasBridge = reflect != null
                && reflect.contains("IntNode#compute synth=true bridge=true;");
        res.assertions.add(new CompatibilityRowRunner.Assertion("reflect.synthetic.exists",
                hasSyntheticLambda, "REFLECT=" + safe(reflect)));
        res.assertions.add(new CompatibilityRowRunner.Assertion("reflect.bridge.exists",
                hasBridge, "REFLECT=" + safe(reflect)));

        // Discover the stable concrete class via /classes.
        ResolvedClass lbt = resolveClassByExactName(target.http, target.port, target.token,
                M3DFixtureSources.C07_TARGET_CLASS, env, "C07 discover");
        ResolvedClass intNode = resolveClassByExactName(target.http, target.port, target.token,
                M3DFixtureSources.C07_TARGET_CLASS + "$IntNode", env, "C07 discover IntNode");
        boolean discovered = lbt != null && intNode != null;
        res.assertions.add(new CompatibilityRowRunner.Assertion("discover.classes", discovered,
                "LambdaBridgeTarget=" + (lbt != null) + " IntNode=" + (intNode != null)));
        if (!discovered) {
            res.failureReason = "could not discover LambdaBridgeTarget/IntNode";
            res.discoveryOk = false;
            return;
        }

        // Discovery policy: /methods must list the concrete methods and HIDE the synthetic
        // lambda$lambdaScore$0 and the bridge compute(Number).
        List<MethodEntry> lbtMethods = listMethods(target.http, target.port, target.token, lbt.classId, env);
        List<MethodEntry> intNodeMethods = listMethods(target.http, target.port, target.token, intNode.classId, env);
        boolean scoreListed = containsMethod(lbtMethods, "score", "()I");
        boolean lambdaListed = anyNameContains(lbtMethods, "lambda$");
        boolean computeConcreteListed = containsMethod(intNodeMethods, "compute", "(Ljava/lang/Integer;)I");
        boolean computeBridgeListed = containsMethod(intNodeMethods, "compute", "(Ljava/lang/Number;)I");
        boolean policyHidesSyntheticAndBridge = scoreListed && !lambdaListed
                && computeConcreteListed && !computeBridgeListed;
        res.discoveryOk = policyHidesSyntheticAndBridge && hasSyntheticLambda && hasBridge;
        res.assertions.add(new CompatibilityRowRunner.Assertion("discover.policy.hides.synthetic.bridge",
                policyHidesSyntheticAndBridge,
                "score listed=" + scoreListed + "; lambda$ listed=" + lambdaListed
                        + "; compute(Integer) listed=" + computeConcreteListed
                        + "; compute(Number) bridge listed=" + computeBridgeListed));

        // Policy: target the stable concrete methods (the synthetic/bridge are not
        // discoverable, so the concrete declaring methods are the enhancement targets).
        ResolvedMethod score = resolveMethod(target.http, target.port, target.token,
                lbt.classId, "score", "()I", env);
        ResolvedMethod compute = resolveMethod(target.http, target.port, target.token,
                intNode.classId, "compute", "(Ljava/lang/Integer;)I", env);
        res.policyOk = score != null && compute != null;
        res.assertions.add(new CompatibilityRowRunner.Assertion("policy.targets.concrete",
                res.policyOk, "score resolved=" + (score != null) + "; compute(Integer) resolved=" + (compute != null)));
        if (!res.policyOk) {
            res.failureReason = "could not resolve stable concrete methods";
            return;
        }

        // Baseline behavior through the lambda and bridge paths.
        Integer lambdaBase = invokeAndRead(target, "INVOKE LAMBDA", "RESULT LAMBDA", env);
        Integer scoreBase = invokeAndRead(target, "INVOKE SCORE", "RESULT SCORE", env);
        Integer bridgeBase = invokeAndRead(target, "INVOKE BRIDGE", "RESULT BRIDGE", env);
        Integer concreteBase = invokeAndRead(target, "INVOKE CONCRETE", "RESULT CONCRETE", env);
        boolean baseOk = equalsOf(M3DFixtureSources.C07_SCORE_BASELINE, lambdaBase, scoreBase)
                && equalsOf(M3DFixtureSources.C07_COMPUTE_BASELINE, bridgeBase, concreteBase);
        res.assertions.add(new CompatibilityRowRunner.Assertion("baseline.paths", baseOk,
                "lambda=" + lambdaBase + " score=" + scoreBase + " bridge=" + bridgeBase + " concrete=" + concreteBase));

        // Enhance score(); prove it takes effect THROUGH the lambda path (synthetic call site).
        HttpOutcome resp1 = httpPost(target.http, target.port, target.token, "/rules",
                ruleJson("c07-score", lbt, score, "score",
                        "return mock.returnValue(" + M3DFixtureSources.C07_SCORE_ENHANCED + ")"),
                env.operationTimeoutMillis);
        Integer lambdaEnh = invokeAndRead(target, "INVOKE LAMBDA", "RESULT LAMBDA", env);
        Integer scoreEnh = invokeAndRead(target, "INVOKE SCORE", "RESULT SCORE", env);
        boolean scoreEnhanceOk = resp1.statusCode() == 201
                && equalsOf(M3DFixtureSources.C07_SCORE_ENHANCED, lambdaEnh, scoreEnh);
        res.assertions.add(new CompatibilityRowRunner.Assertion("enhance.score.through.lambda",
                scoreEnhanceOk, "POST -> " + resp1.statusCode() + "; lambda " + lambdaBase + " -> " + lambdaEnh
                        + "; score " + scoreBase + " -> " + scoreEnh));

        // Unload score(); restore.
        HttpOutcome del1 = httpDelete(target.http, target.port, target.token, "/rules/c07-score",
                env.operationTimeoutMillis);
        Integer lambdaRes = invokeAndRead(target, "INVOKE LAMBDA", "RESULT LAMBDA", env);
        boolean scoreUnloadOk = del1.statusCode() == 200
                && equalsOf(M3DFixtureSources.C07_SCORE_BASELINE, lambdaRes);
        res.assertions.add(new CompatibilityRowRunner.Assertion("unload.score.restore",
                scoreUnloadOk, "DELETE -> " + del1.statusCode() + "; lambda -> " + lambdaRes));

        // Enhance compute(Integer); prove it takes effect THROUGH the bridge path.
        HttpOutcome resp2 = httpPost(target.http, target.port, target.token, "/rules",
                ruleJson("c07-compute", intNode, compute, "compute",
                        "return mock.returnValue(" + M3DFixtureSources.C07_COMPUTE_ENHANCED + ")"),
                env.operationTimeoutMillis);
        Integer bridgeEnh = invokeAndRead(target, "INVOKE BRIDGE", "RESULT BRIDGE", env);
        Integer concreteEnh = invokeAndRead(target, "INVOKE CONCRETE", "RESULT CONCRETE", env);
        boolean computeEnhanceOk = resp2.statusCode() == 201
                && equalsOf(M3DFixtureSources.C07_COMPUTE_ENHANCED, bridgeEnh, concreteEnh);
        res.assertions.add(new CompatibilityRowRunner.Assertion("enhance.compute.through.bridge",
                computeEnhanceOk, "POST -> " + resp2.statusCode() + "; bridge " + bridgeBase + " -> " + bridgeEnh
                        + "; concrete " + concreteBase + " -> " + concreteEnh));

        // Unload compute(); restore.
        HttpOutcome del2 = httpDelete(target.http, target.port, target.token, "/rules/c07-compute",
                env.operationTimeoutMillis);
        Integer bridgeRes = invokeAndRead(target, "INVOKE BRIDGE", "RESULT BRIDGE", env);
        boolean computeUnloadOk = del2.statusCode() == 200
                && equalsOf(M3DFixtureSources.C07_COMPUTE_BASELINE, bridgeRes);
        res.assertions.add(new CompatibilityRowRunner.Assertion("unload.compute.restore",
                computeUnloadOk, "DELETE -> " + del2.statusCode() + "; bridge -> " + bridgeRes));

        res.behaviorOk = baseOk && scoreEnhanceOk && scoreUnloadOk && computeEnhanceOk && computeUnloadOk;
        if (!res.behaviorOk && res.failureReason.isBlank()) {
            res.failureReason = "C07 behavior assertion failed";
        }
    }

    // ============================================================ shared launch
    private TargetHandle launch(CompatibilityScenario scenario, RealExecEnv env, Path jdkHome,
                               Path classesDir, List<Path> extraClasspath, String mainClass,
                               Path runDir, Path stdoutArtifact, Path stderrArtifact,
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
        String agentArgs = "coreJar=" + env.coreJar
                + ",bootstrapJar=" + env.bootstrapApiJar
                + ",host=127.0.0.1,port=" + port + ",token=" + token;
        String cp = classesDir.toString() + joinPaths(extraClasspath);
        List<String> cmd = new ArrayList<>();
        cmd.add(targetJava.toString());
        cmd.add("-javaagent:" + env.bootstrapJar + "=" + agentArgs);
        cmd.add("-Dclasses.dir=" + classesDir.toString());
        cmd.add("-cp");
        cmd.add(cp);
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
        return new TargetHandle(proc, stdout, stderr, stdin, http, port, token, launchCommand);
    }

    /** Reads the READY line and validates the reported PID/JDK against the process. */
    private boolean readReady(TargetHandle t, RealExecEnv env, String[] failure,
                              String[] targetJdk, int[] childPid) throws Exception {
        String ready = t.stdout.nextLine(env.startupTimeoutMillis, TimeUnit.MILLISECONDS);
        if (ready == null || !ready.startsWith("READY")) {
            failure[0] = "target did not print READY within " + env.startupTimeoutMillis
                    + "ms (last='" + safe(ready) + "')";
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

    /** Resolves the second same-name class (different classLoaderId than the first). */
    private ResolvedClass resolveSecondLoaderClass(HttpClient http, int port, String token,
                                                    String exactClassName, String firstLoaderId,
                                                    RealExecEnv env) throws Exception {
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
                if (exactClassName.equals(c.path("className").asText())
                        && !firstLoaderId.equals(c.path("classLoaderId").asText())) {
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

    /** Captures and returns the SHA-256 of the bytes actually applied in the JVM. */
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

    private Integer invokeAndRead(TargetHandle t, String command, String prefix, RealExecEnv env) throws Exception {
        String line = commandAndRead(t, command, prefix, env);
        if (line == null) {
            return null;
        }
        try {
            return Integer.parseInt(line.substring(prefix.length()).trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
    private M3DExecutionOutcome outcome(boolean started, int childPid, RealExecEnv env, String targetJdk,
                                        String launchCommand, String attachCommand, Path stdout, Path stderr,
                                        List<CompatibilityRowRunner.Assertion> assertions, String failureReason) {
        boolean independent = childPid > 0 && childPid != env.runnerPid;
        return new M3DExecutionOutcome(started, childPid, independent, targetJdk,
                launchCommand, attachCommand, stdout, stderr, new ArrayList<>(assertions), failureReason);
    }

    /** Adds the evidence assertions and returns the final outcome. */
    private M3DExecutionOutcome finish(boolean started, int childPid, RealExecEnv env, String targetJdk,
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
    private static boolean allOf(int expected, Integer... vals) {
        for (Integer v : vals) {
            if (v == null || v != expected) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalsOf(int expected, Integer... vals) {
        return allOf(expected, vals);
    }

    private static boolean containsMethod(List<MethodEntry> methods, String name, String descriptor) {
        for (MethodEntry m : methods) {
            if (m.name.equals(name) && m.descriptor.equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyNameContains(List<MethodEntry> methods, String fragment) {
        for (MethodEntry m : methods) {
            if (m.name.contains(fragment)) {
                return true;
            }
        }
        return false;
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

    private static void appendArtifact(Path file, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        try {
            Files.writeString(file, content + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // best effort
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
        }, "kairo-compat-m3d-stderr");
    }

    // --------------------------------------------------------------- inner types
    private record ResolvedClass(String classId, String classLoaderId, String className) {
    }

    private record ResolvedMethod(String classId, String name, String descriptor) {
    }

    private record MethodEntry(String name, String descriptor) {
    }

    private record HttpOutcome(int statusCode, String body) {
    }

    /** Per-JDK run result for C07. */
    private static final class C07RunResult {
        boolean targetStarted;
        int childPid;
        String targetJdk = "";
        String launchCommand = "";
        String failureReason = "";
        boolean discoveryOk;
        boolean policyOk;
        boolean behaviorOk;
        String stdoutSummary = "";
        String stderrSummary = "";
        final List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        final String[] targetJdkArr = {""};
        final int[] childPidArr = {0};
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

        TargetHandle(Process process, OutputPump stdout, Thread stderrThread, Writer stdin,
                     HttpClient http, int port, String token, String launchCommand) {
            this.process = process;
            this.stdout = stdout;
            this.stderrThread = stderrThread;
            this.stdin = stdin;
            this.http = http;
            this.port = port;
            this.token = token;
            this.launchCommand = launchCommand;
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
            }, "kairo-compat-m3d-stdout");
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
