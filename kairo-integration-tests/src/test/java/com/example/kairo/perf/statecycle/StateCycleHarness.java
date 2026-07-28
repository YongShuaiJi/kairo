package com.example.kairo.perf.statecycle;

import com.example.demo.OrderService;
import com.example.demo.v13.EnhancementFixtures.CallSiteSamples;
import com.example.demo.v13.EnhancementFixtures.ThrowingCtor;
import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.agent.core.bytecode.BytecodeCaptureService.CaptureResult;
import com.example.kairo.agent.core.bytecode.ClassIdentities;
import com.example.kairo.api.ApplyChainRequest;
import com.example.kairo.api.ApplyChainResult;
import com.example.kairo.api.ApplyChainStatus;
import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.ChainDesiredState;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.EnhancementTarget;
import com.example.kairo.api.InvokeOpcode;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.RuleChainEntry;
import com.example.kairo.api.RuleChainRevision;
import com.example.kairo.api.RuleChainSpec;
import com.example.kairo.api.bytecode.BytecodeDiffResult;
import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.bytebuddy.agent.ByteBuddyAgent;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * M2-B state-cycle harness (&sect;9.2). Runs real Agent/JVM lifecycle cycles
 * distributed across the fixed six-scenario matrix and writes machine-verifiable
 * {@code state-cycle-result.json}.
 *
 * <p>One cycle is one complete lifecycle on one real target: capture baseline
 * bytes, enhance via the real Byte Buddy / AgentRuntime path, invoke and verify
 * the enhanced behaviour, update or partially unload, invoke and verify again,
 * fully unload, invoke and verify the original behaviour, and prove the
 * normalized bytecode hash returned to the baseline.
 *
 * <p>The hash proof is non-vacuous: the enhanced-state hash MUST differ from the
 * baseline, and the after-unload hash MUST equal the baseline both as a raw
 * SHA-256 of the actual captured JVM bytes and as a normalized identity via the
 * product {@code BytecodeDiffService} (strips frames / debug / constant-pool
 * indices, normalizes labels). The harness fails on the first invalid lifecycle.
 *
 * <p>Exit codes: 0 pass; 2 bad args; 3 setup error; 4 lifecycle failure;
 * 5 result-write/aggregation error; 6 schema-validation failure. The result file
 * is always written (best-effort) even on failure.
 */
public final class StateCycleHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONCURRENT_THREADS = 8;

    private StateCycleHarness() {
    }

    public static void main(String[] args) {
        System.exit(runInProcess(args));
    }

    public static int runInProcess(String[] args) {
        StateCycleArgumentParser.Options opts;
        try {
            opts = StateCycleArgumentParser.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }
        if (opts.help()) {
            printUsage();
            return 0;
        }
        return run(opts);
    }

    private static int run(StateCycleArgumentParser.Options opts) {
        String startedAt = Instant.now().toString();
        int[] distribution = StateCycleScenarioCatalog.distribute(opts.cycles());
        List<StateCycleScenarioCatalog.Scenario> scenarios = StateCycleScenarioCatalog.all();

        Failure firstFailure = null;
        Map<String, ScenarioAccumulator> accumulators = new LinkedHashMap<>();
        for (StateCycleScenarioCatalog.Scenario s : scenarios) {
            accumulators.put(s.id(), new ScenarioAccumulator());
        }
        ConflictOutcome conflict = null;

        Instrumentation instrumentation = null;
        AgentRuntime runtime = null;
        try {
            instrumentation = ByteBuddyAgent.install();
            runtime = new AgentRuntime(instrumentation);
            runtime.start();

            for (int i = 0; i < scenarios.size(); i++) {
                StateCycleScenarioCatalog.Scenario s = scenarios.get(i);
                if (s.concurrent()) {
                    // The concurrent scenario has its own coordinated runner below;
                    // it is not a per-cycle switch case and must execute exactly once.
                    continue;
                }
                int cycleCount = distribution[i];
                ScenarioAccumulator acc = accumulators.get(s.id());
                for (int c = 1; c <= cycleCount && firstFailure == null; c++) {
                    try {
                        CycleSample sample = runCycle(runtime, s.id(), c);
                        if (!sample.enhancedDiffersFromBaseline()) {
                            throw new AssertionFailure("enhancement-bytecode",
                                    "enhancedHash!=baselineHash",
                                    "baseline=" + sample.baselineHash() + " enhanced=" + sample.enhancedHash(),
                                    "enhancement did not produce a non-vacuous bytecode change");
                        }
                        if (!sample.rulesClearedAfterUnload()) {
                            throw new AssertionFailure("rules-cleared", "true", "false",
                                    "rules remained registered after full unload");
                        }
                        if (!sample.hashRestored()) {
                            throw new AssertionFailure("hash-restore",
                                    "afterUnloadHash==baselineHash && normalizedIdentical",
                                    "baseline=" + sample.baselineHash() + " after=" + sample.afterUnloadHash()
                                            + " normalizedIdentical=" + sample.normalizedIdentical(),
                                    "bytecode hash not restored to baseline after full unload"
                                            + " (see M2-B defect report: applyRuleChain content-only change"
                                            + " + EMPTY unload does not retransform)");
                        }
                        acc.record(c, sample);
                    } catch (AssertionFailure af) {
                        firstFailure = new Failure(s.id(), c, af.phase, af.expected, af.actual, af.detail);
                        acc.fail();
                    } catch (Throwable t) {
                        firstFailure = new Failure(s.id(), c, "execute", "", "",
                                t.getClass().getSimpleName() + ": " + t.getMessage());
                        acc.fail();
                    }
                }
                if (firstFailure != null) {
                    break;
                }
            }

            if (firstFailure == null) {
                String concurrentId = StateCycleScenarioCatalog.concurrentScenario().id();
                ScenarioAccumulator concurrentAcc = accumulators.get(concurrentId);
                try {
                    conflict = runConcurrentConflict(runtime);
                    // Record the single concurrent cycle as completed so the totals
                    // reconcile (completed == requested). Its evidence lives in the
                    // concurrentConflict block, not in a per-cycle firstSample.
                    concurrentAcc.recordConcurrent();
                } catch (AssertionFailure af) {
                    firstFailure = new Failure(concurrentId,
                            1, af.phase, af.expected, af.actual, af.detail);
                    concurrentAcc.fail();
                } catch (Throwable t) {
                    firstFailure = new Failure(concurrentId,
                            1, "execute", "", "", t.getClass().getSimpleName() + ": " + t.getMessage());
                    concurrentAcc.fail();
                }
            }
        } catch (Throwable t) {
            firstFailure = new Failure("setup", 0, "agent-setup", "", "",
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (runtime != null) {
                try {
                    runtime.close();
                } catch (RuntimeException ignored) {
                    // best-effort close; a close failure must not mask the real result
                }
            }
        }

        int completedCycles = 0;
        int failedCycles = 0;
        for (StateCycleScenarioCatalog.Scenario s : scenarios) {
            ScenarioAccumulator acc = accumulators.get(s.id());
            completedCycles += acc.completed;
            failedCycles += acc.failed;
        }

        String overall = (firstFailure == null) ? "PASSED" : "FAILED";
        ObjectNode root = buildResult(opts, startedAt, distribution, scenarios, accumulators,
                conflict, firstFailure, completedCycles, failedCycles, overall);

        int writeStatus = writeResult(opts.output(), root);
        if (writeStatus != 0) {
            return writeStatus;
        }
        // Self-validate the schema/content.
        try {
            JsonNode written = MAPPER.readTree(new String(Files.readAllBytes(
                    Path.of(opts.output(), "state-cycle-result.json")), StandardCharsets.UTF_8));
            List<String> errors = new StateCycleResultValidator().validate(written, opts.cycles());
            if (!errors.isEmpty()) {
                System.err.println("SCHEMA VALIDATION FAILED:");
                errors.forEach(e -> System.err.println("  - " + e));
                return 6;
            }
        } catch (Exception e) {
            System.err.println("error: self-validation read failed: " + e);
            return 6;
        }

        if (firstFailure != null) {
            System.err.println("STATE-CYCLE FAILED: scenario=" + firstFailure.scenario
                    + " cycle=" + firstFailure.cycleIndex + " phase=" + firstFailure.phase);
            return 4;
        }
        return 0;
    }

    // -------------------------------------------------------- per-scenario cycles

    private static CycleSample runCycle(AgentRuntime runtime, String scenarioId, int cycle) throws Exception {
        return switch (scenarioId) {
            case "ordinary-method" -> ordinaryCycle(runtime, cycle);
            case "constructor-enhancement" -> constructorCycle(runtime, cycle);
            case "callsite-enhancement" -> callsiteCycle(runtime, cycle);
            case "rule-chain" -> chainCycle(runtime, cycle);
            case "parent-child-loader" -> parentChildCycle(runtime, cycle);
            default -> throw new IllegalStateException("no cyclic runner for " + scenarioId);
        };
    }

    private static CycleSample ordinaryCycle(AgentRuntime runtime, int cycle) throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
        Class<?> clazz = OrderService.class;

        CaptureResult base = runtime.captureService().capture(clazz);
        verify("baseline-behavior", "10", String.valueOf(new OrderService().calculateScore(5)));

        MockRule r1 = rule("ord-" + cycle, method, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.returnValue(77)");
        ApplyChainResult first = applyChain(runtime, "cmd-ord-" + cycle, "key-ord-" + cycle,
                RuleChainRevision.initial(), 1L, target, List.of(r1), ChainDesiredState.ACTIVE);
        verify("enhanced-behavior", "77", String.valueOf(new OrderService().calculateScore(5)));
        CaptureResult enhanced = runtime.captureService().capture(clazz);

        // Content-only update: same target/footprint, different script, no retransform.
        MockRule r2 = rule("ord-" + cycle, method, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.returnValue(88)");
        ApplyChainResult second = applyChain(runtime, "cmd-ord-u-" + cycle, "key-ord-u-" + cycle,
                first.applied(), 2L, target, List.of(r2), ChainDesiredState.ACTIVE);
        verify("updated-behavior", "88", String.valueOf(new OrderService().calculateScore(5)));
        CaptureResult updated = runtime.captureService().capture(clazz);

        unloadChain(runtime, "cmd-ord-x-" + cycle, "key-ord-x-" + cycle, second.applied(), target);
        verify("restored-behavior", "10", String.valueOf(new OrderService().calculateScore(5)));
        CaptureResult after = runtime.captureService().capture(clazz);

        return sample(runtime, clazz, base, enhanced, updated, after, "77", "88", "10");
    }

    private static CycleSample constructorCycle(AgentRuntime runtime, int cycle) throws Exception {
        Constructor<?> ctor = ThrowingCtor.class.getDeclaredConstructor();
        Class<?> clazz = ThrowingCtor.class;
        String baseline = constructThrowing();   // "throws:ctor-origin"

        CaptureResult base = runtime.captureService().capture(clazz);
        verify("baseline-behavior", "throws:ctor-origin", baseline);

        runtime.publishConstructor(ctor, constructorRule("ctor-" + cycle, ctor,
                EnhancementLocation.CONSTRUCTOR_THROW,
                "return mock.throwException('java.lang.IllegalStateException', 'mocked-ctor')"), "test");
        String enhancedBehavior = constructThrowing();
        verify("enhanced-behavior", "throws:mocked-ctor", enhancedBehavior);
        CaptureResult enhanced = runtime.captureService().capture(clazz);

        runtime.remove("ctor-" + cycle, "test");
        runtime.publishConstructor(ctor, constructorRule("ctor-" + cycle, ctor,
                EnhancementLocation.CONSTRUCTOR_THROW,
                "return mock.throwException('java.lang.IllegalStateException', 'updated-ctor')"), "test");
        String updatedBehavior = constructThrowing();
        verify("updated-behavior", "throws:updated-ctor", updatedBehavior);
        CaptureResult updated = runtime.captureService().capture(clazz);

        runtime.remove("ctor-" + cycle, "test");
        verify("restored-behavior", "throws:ctor-origin", constructThrowing());
        CaptureResult after = runtime.captureService().capture(clazz);

        return sample(runtime, clazz, base, enhanced, updated, after,
                enhancedBehavior, updatedBehavior, baseline);
    }

    private static CycleSample callsiteCycle(AgentRuntime runtime, int cycle) throws Exception {
        Method caller = CallSiteSamples.class.getMethod("callVirtual");
        Class<?> clazz = CallSiteSamples.class;
        CaptureResult base = runtime.captureService().capture(clazz);
        verify("baseline-behavior", "virtual", new CallSiteSamples().callVirtual());

        MockRule rule = callSiteRule("cs-" + cycle, caller, EnhancementLocation.CALL_BEFORE,
                CallSiteSamples.class.getName(), "virtualTarget", "()Ljava/lang/String;",
                InvokeOpcode.INVOKEVIRTUAL, 0, "return mock.returnValue('mocked')");
        runtime.publish(caller, rule, "test");
        verify("enhanced-behavior", "mocked", new CallSiteSamples().callVirtual());
        CaptureResult enhanced = runtime.captureService().capture(clazz);

        runtime.remove("cs-" + cycle, "test");
        MockRule rule2 = callSiteRule("cs-" + cycle, caller, EnhancementLocation.CALL_BEFORE,
                CallSiteSamples.class.getName(), "virtualTarget", "()Ljava/lang/String;",
                InvokeOpcode.INVOKEVIRTUAL, 0, "return mock.returnValue('updated')");
        runtime.publish(caller, rule2, "test");
        verify("updated-behavior", "updated", new CallSiteSamples().callVirtual());
        CaptureResult updated = runtime.captureService().capture(clazz);

        runtime.remove("cs-" + cycle, "test");
        verify("restored-behavior", "virtual", new CallSiteSamples().callVirtual());
        CaptureResult after = runtime.captureService().capture(clazz);

        return sample(runtime, clazz, base, enhanced, updated, after, "mocked", "updated", "virtual");
    }

    private static CycleSample chainCycle(AgentRuntime runtime, int cycle) throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
        Class<?> clazz = OrderService.class;
        CaptureResult base = runtime.captureService().capture(clazz);
        verify("baseline-behavior", "10", String.valueOf(new OrderService().calculateScore(5)));

        MockRule a = rule("chain-a-" + cycle, method, EnhancementLocation.METHOD_RETURN, 30,
                "return mock.replaceReturnValue(ctx.result() + 1)");
        MockRule b = rule("chain-b-" + cycle, method, EnhancementLocation.METHOD_RETURN, 20,
                "return mock.replaceReturnValue(ctx.result() + 10)");
        MockRule c = rule("chain-c-" + cycle, method, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.replaceReturnValue(ctx.result() + 100)");

        ApplyChainResult applied = applyChain(runtime, "cmd-chain-" + cycle, "key-chain-" + cycle,
                RuleChainRevision.initial(), 1L, target, List.of(a, b, c), ChainDesiredState.ACTIVE);
        verify("enhanced-behavior", "121", String.valueOf(new OrderService().calculateScore(5)));
        CaptureResult enhanced = runtime.captureService().capture(clazz);

        // Partial unload: drop the middle rule, keep a and c (exact remaining behaviour).
        ApplyChainResult partial = applyChain(runtime, "cmd-chain-p-" + cycle, "key-chain-p-" + cycle,
                applied.applied(), 2L, target, List.of(a, c), ChainDesiredState.ACTIVE);
        verify("partial-unload-behavior", "111", String.valueOf(new OrderService().calculateScore(5)));
        CaptureResult updated = runtime.captureService().capture(clazz);

        unloadChain(runtime, "cmd-chain-x-" + cycle, "key-chain-x-" + cycle, partial.applied(), target);
        verify("restored-behavior", "10", String.valueOf(new OrderService().calculateScore(5)));
        CaptureResult after = runtime.captureService().capture(clazz);

        return sample(runtime, clazz, base, enhanced, updated, after, "121", "111", "10");
    }

    private static CycleSample parentChildCycle(AgentRuntime runtime, int cycle) throws Exception {
        try (LoadedPair pair = loadParentChildDuplicateServices(cycle)) {
            Class<?> parentClass = pair.parentClass();
            Class<?> childClass = pair.childClass();
            Object parentInst = parentClass.getDeclaredConstructor().newInstance();
            Object childInst = childClass.getDeclaredConstructor().newInstance();
            Method parentEcho = parentClass.getMethod("echo", String.class);
            Method childEcho = childClass.getMethod("echo", String.class);

            CaptureResult parentBase = runtime.captureService().capture(parentClass);
            CaptureResult childBase = runtime.captureService().capture(childClass);
            verify("parent-baseline", "parent-x", parentEcho.invoke(parentInst, "x"));
            verify("child-baseline", "child-x", childEcho.invoke(childInst, "x"));

            // Enhance ONLY the child loader, targeted by ClassLoader identity.
            MockRule rule = publishRule("pcl-" + cycle, childEcho, EnhancementLocation.METHOD_RETURN,
                    "return mock.returnValue('CHILD-MOCKED')");
            runtime.publish(childEcho, rule, "test");
            verify("child-enhanced", "CHILD-MOCKED", childEcho.invoke(childInst, "x"));
            verify("parent-unchanged-while-child-enhanced", "parent-x", parentEcho.invoke(parentInst, "x"));
            CaptureResult childEnhanced = runtime.captureService().capture(childClass);
            CaptureResult parentDuring = runtime.captureService().capture(parentClass);

            // Update the child rule; parent must remain untouched.
            runtime.remove("pcl-" + cycle, "test");
            MockRule rule2 = publishRule("pcl-" + cycle, childEcho, EnhancementLocation.METHOD_RETURN,
                    "return mock.returnValue('CHILD-UPDATED')");
            runtime.publish(childEcho, rule2, "test");
            verify("child-updated", "CHILD-UPDATED", childEcho.invoke(childInst, "x"));
            verify("parent-unchanged-while-child-updated", "parent-x", parentEcho.invoke(parentInst, "x"));
            CaptureResult childUpdated = runtime.captureService().capture(childClass);

            // Full unload of the child rule; both loaders restore.
            runtime.remove("pcl-" + cycle, "test");
            verify("child-restored", "child-x", childEcho.invoke(childInst, "x"));
            verify("parent-restored", "parent-x", parentEcho.invoke(parentInst, "x"));
            CaptureResult childAfter = runtime.captureService().capture(childClass);
            CaptureResult parentAfter = runtime.captureService().capture(parentClass);

            // Parent bytes must never have changed.
            if (!parentBase.appliedHash().equals(parentDuring.appliedHash())
                    || !parentBase.appliedHash().equals(parentAfter.appliedHash())) {
                throw new AssertionFailure("parent-bytecode-stability",
                        parentBase.appliedHash(), parentAfter.appliedHash(),
                        "parent class bytes changed while only the child loader was targeted");
            }

            CycleSample sample = sample(runtime, childClass, childBase, childEnhanced, childUpdated, childAfter,
                    "CHILD-MOCKED", "CHILD-UPDATED", "child-x");
            // Augment with the parent-restore proof.
            return sample.withParent(parentBase.appliedHash(), parentAfter.appliedHash(),
                    normalizedIdentical(runtime, parentClass, parentBase, parentAfter));
        }
    }

    // -------------------------------------------------------- concurrent conflict

    private static ConflictOutcome runConcurrentConflict(AgentRuntime runtime) throws Exception {
        Method method = com.example.demo.v13.EnhancementFixtures.MethodKinds.class.getMethod("valueMethod", int.class);
        EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
        Class<?> clazz = com.example.demo.v13.EnhancementFixtures.MethodKinds.class;

        CaptureResult base = runtime.captureService().capture(clazz);
        verify("conflict-baseline", "value-1", new com.example.demo.v13.EnhancementFixtures.MethodKinds().valueMethod(1));

        int n = CONCURRENT_THREADS;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApplyChainResult>> futures = new ArrayList<>();
        List<MockRule> rules = new ArrayList<>();
        for (int t = 0; t < n; t++) {
            final int tid = t;
            MockRule rule = rule("conf-" + tid, method, EnhancementLocation.METHOD_RETURN, 10,
                    "return mock.returnValue('WIN-" + tid + "')");
            rules.add(rule);
            futures.add(pool.submit(() -> {
                start.await();
                // Raw applyRuleChain (not the applyChain helper): fencing makes exactly
                // one thread APPLIED and the rest STALE_COMMAND; losers must NOT throw.
                return runtime.applyRuleChain(chainRequest("cmd-conf-" + tid, "key-conf-" + tid,
                        RuleChainRevision.initial(), 1L, target, List.of(rule), ChainDesiredState.ACTIVE));
            }));
        }

        int applied = 0;
        int stale = 0;
        ApplyChainResult winnerResult = null;
        String winnerRuleId = null;
        String winnerValue = null;
        try {
            start.countDown();
            for (int t = 0; t < n; t++) {
                ApplyChainResult r;
                try {
                    r = futures.get(t).get();
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    throw new AssertionFailure("conflict-apply", "APPLIED or STALE_COMMAND",
                            cause.getClass().getSimpleName(),
                            "thread " + t + " applyRuleChain threw: " + cause.getClass().getSimpleName()
                                    + ": " + cause.getMessage());
                }
                if (r.status() == ApplyChainStatus.APPLIED) {
                    applied++;
                    winnerResult = r;
                    winnerRuleId = rules.get(t).id();
                    winnerValue = "WIN-" + t;
                } else if (r.status() == ApplyChainStatus.STALE_COMMAND) {
                    stale++;
                } else {
                    // Reject any status other than APPLIED / STALE_COMMAND.
                    throw new AssertionFailure("conflict-status", "APPLIED or STALE_COMMAND",
                            r.status().name(),
                            "thread " + t + " returned unexpected status " + r.status()
                                    + " (message=" + r.message() + ")");
                }
            }
        } finally {
            // Guarantee executor shutdown even on assertion failure.
            pool.shutdown();
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        }

        // Exactly one APPLIED winner and n-1 STALE_COMMAND losers.
        if (applied != 1) {
            throw new AssertionFailure("conflict-applied-count", "1", String.valueOf(applied),
                    "expected exactly one APPLIED conflict winner (got " + applied + ")");
        }
        if (stale != n - 1) {
            throw new AssertionFailure("conflict-stale-count", String.valueOf(n - 1), String.valueOf(stale),
                    "expected " + (n - 1) + " STALE_COMMAND losers (got " + stale + ")");
        }
        if (winnerResult == null) {
            throw new AssertionFailure("conflict-winner", "present", "absent",
                    "no APPLIED winner despite applied count");
        }

        CaptureResult enhanced = runtime.captureService().capture(clazz);
        if (base.appliedHash().equals(enhanced.appliedHash())) {
            throw new AssertionFailure("conflict-enhancement-bytecode",
                    "enhancedHash!=baselineHash",
                    "baseline=" + base.appliedHash() + " enhanced=" + enhanced.appliedHash(),
                    "concurrent winner did not produce a non-vacuous bytecode change");
        }
        String finalBehavior = new com.example.demo.v13.EnhancementFixtures.MethodKinds().valueMethod(1);
        verify("conflict-winner-active", winnerValue == null ? "" : winnerValue, finalBehavior);
        // No corrupted mixed state: the observed behaviour is exactly the winner.
        boolean mixed = !finalBehavior.equals(winnerValue);

        // Full unload using the WINNER's applied revision (not RuleChainRevision.initial()).
        unloadChain(runtime, "cmd-conf-x", "key-conf-x", winnerResult.applied(), target);
        String restored = new com.example.demo.v13.EnhancementFixtures.MethodKinds().valueMethod(1);
        verify("conflict-restored", "value-1", restored);
        CaptureResult after = runtime.captureService().capture(clazz);
        boolean normIdentical = normalizedIdentical(runtime, clazz, base, after);
        boolean hashRestored = base.appliedHash().equals(after.appliedHash()) && normIdentical;
        verify("conflict-hash-restored", "true", String.valueOf(hashRestored));
        verify("conflict-rules-cleared", "true", String.valueOf(runtime.rules().isEmpty()));

        return new ConflictOutcome(n, applied, stale, winnerRuleId == null ? "" : winnerRuleId,
                finalBehavior, restored, mixed, base.appliedHash(), enhanced.appliedHash(), after.appliedHash(),
                hashRestored, normIdentical);
    }

    // -------------------------------------------------------- hash / sample helpers

    private static CycleSample sample(AgentRuntime runtime, Class<?> clazz,
                                      CaptureResult base, CaptureResult enhanced, CaptureResult updated,
                                      CaptureResult after, String enhancedBehavior, String updatedBehavior,
                                      String restoredBehavior) {
        boolean enhancedDiffers = !base.appliedHash().equals(enhanced.appliedHash());
        boolean normIdentical = normalizedIdentical(runtime, clazz, base, after);
        boolean hashRestored = base.appliedHash().equals(after.appliedHash()) && normIdentical;
        boolean rulesCleared = runtime.rules().isEmpty();
        return new CycleSample(base.appliedHash(), enhanced.appliedHash(), updated.appliedHash(),
                after.appliedHash(), enhancedBehavior, updatedBehavior, restoredBehavior,
                enhancedDiffers, normIdentical, hashRestored, rulesCleared,
                null, null, false);
    }

    private static boolean normalizedIdentical(AgentRuntime runtime, Class<?> clazz,
                                               CaptureResult base, CaptureResult after) {
        BytecodeDiffResult diff = runtime.diffService().diff(
                ClassIdentities.of(clazz),
                base.appliedBytes(), base.revision(), BytecodeSnapshotKind.INPUT,
                after.appliedBytes(), after.revision(), BytecodeSnapshotKind.APPLIED);
        return diff.identical();
    }

    private static String constructThrowing() {
        try {
            new ThrowingCtor();
            return "ok";
        } catch (RuntimeException e) {
            return "throws:" + e.getMessage();
        }
    }

    // -------------------------------------------------------- chain / rule helpers

    private static ApplyChainRequest chainRequest(String commandId, String idempotencyKey,
                                                  RuleChainRevision expected, long desiredRevision,
                                                  EnhancementTarget target, List<MockRule> rules,
                                                  ChainDesiredState state) {
        RuleChainSpec spec = RuleChainSpec.builder()
                .chainId(target.method().className() + "#" + target.method().methodName())
                .revision(desiredRevision)
                .target(target)
                .entries(entries(rules))
                .desiredState(state)
                .build();
        return ApplyChainRequest.builder()
                .commandId(commandId)
                .idempotencyKey(idempotencyKey)
                .expected(expected)
                .desired(spec)
                .rules(rules)
                .target(target)
                .deadlineMillis(30_000L)
                .build();
    }

    private static ApplyChainResult applyChain(AgentRuntime runtime, String commandId, String idempotencyKey,
                                               RuleChainRevision expected, long desiredRevision,
                                               EnhancementTarget target, List<MockRule> rules,
                                               ChainDesiredState state) {
        ApplyChainResult result = runtime.applyRuleChain(
                chainRequest(commandId, idempotencyKey, expected, desiredRevision, target, rules, state));
        if (result.status() != ApplyChainStatus.APPLIED) {
            throw new AssertionFailure("apply-chain", "APPLIED", result.status().name(),
                    "commandId=" + commandId + " message=" + result.message());
        }
        return result;
    }

    private static void unloadChain(AgentRuntime runtime, String commandId, String idempotencyKey,
                                    EnhancementTarget target) {
        unloadChain(runtime, commandId, idempotencyKey, RuleChainRevision.initial(), target);
    }

    private static void unloadChain(AgentRuntime runtime, String commandId, String idempotencyKey,
                                    RuleChainRevision expected, EnhancementTarget target) {
        RuleChainSpec spec = RuleChainSpec.builder()
                .chainId(target.method().className() + "#" + target.method().methodName())
                .revision(expected.value() + 1)
                .target(target)
                .entries(List.of())
                .desiredState(ChainDesiredState.EMPTY)
                .build();
        ApplyChainRequest request = ApplyChainRequest.builder()
                .commandId(commandId)
                .idempotencyKey(idempotencyKey)
                .expected(expected)
                .desired(spec)
                .rules(List.of())
                .target(target)
                .deadlineMillis(30_000L)
                .build();
        ApplyChainResult result = runtime.applyRuleChain(request);
        if (result.status() != ApplyChainStatus.APPLIED && result.status() != ApplyChainStatus.NO_OP) {
            throw new AssertionFailure("unload-chain", "APPLIED", result.status().name(),
                    "commandId=" + commandId + " message=" + result.message());
        }
    }

    private static List<RuleChainEntry> entries(List<MockRule> rules) {
        return rules.stream()
                .map(r -> RuleChainEntry.builder()
                        .ruleId(r.id())
                        .version(r.version())
                        .priority(r.priority())
                        .createdAtMillis(r.createdAt())
                        .scriptHash(r.scriptHash() == null ? "" : r.scriptHash())
                        .mutexGroup(r.mutexGroup())
                        .build())
                .toList();
    }

    private static EnhancementTarget targetOf(Method method, EnhancementLocation location) {
        MethodSelector selector = new MethodSelector(method.getDeclaringClass().getName(),
                ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()),
                method.getName(), MethodDescriptor.of(method));
        return EnhancementTarget.of(selector, location);
    }

    private static MockRule rule(String id, Method method, EnhancementLocation location, int priority, String script) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(MethodSelector.builder()
                        .className(method.getDeclaringClass().getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()))
                        .methodName(method.getName())
                        .methodDescriptor(MethodDescriptor.of(method))
                        .build())
                .location(location)
                .phase(InvokePhase.BEFORE)
                .priority(priority)
                .script(script)
                .scriptHash(Integer.toHexString(script.hashCode()))
                .build();
    }

    private static MockRule publishRule(String id, Method method, EnhancementLocation location, String script) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(MethodSelector.builder()
                        .className(method.getDeclaringClass().getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()))
                        .methodName(method.getName())
                        .methodDescriptor(MethodDescriptor.of(method))
                        .build())
                .location(location)
                .script(script)
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
    }

    private static MockRule constructorRule(String id, Constructor<?> constructor,
                                            EnhancementLocation location, String script) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(MethodSelector.builder()
                        .className(constructor.getDeclaringClass().getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(constructor.getDeclaringClass().getClassLoader()))
                        .methodName("<init>")
                        .methodDescriptor(MethodDescriptor.of(constructor))
                        .build())
                .location(location)
                .script(script)
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
    }

    private static MockRule callSiteRule(String id, Method caller, EnhancementLocation location,
                                         String calleeOwner, String calleeName, String calleeDescriptor,
                                         InvokeOpcode opcode, int occurrence, String script) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(MethodSelector.builder()
                        .className(caller.getDeclaringClass().getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(caller.getDeclaringClass().getClassLoader()))
                        .methodName(caller.getName())
                        .methodDescriptor(MethodDescriptor.of(caller))
                        .build())
                .location(location)
                .callSiteSelector(CallSiteSelector.builder()
                        .owner(calleeOwner)
                        .name(calleeName)
                        .descriptor(calleeDescriptor)
                        .opcode(opcode)
                        .occurrenceIndex(occurrence)
                        .build())
                .script(script)
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
    }

    // -------------------------------------------------------- parent/child loader fixture

    private static final String DUPLICATE_FQN = "com.example.duplicate.DuplicateService";

    private record LoadedPair(Class<?> parentClass, Class<?> childClass,
                              URLClassLoader parentLoader, URLClassLoader childLoader,
                              Path parentDir, Path childDir) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            Exception failure = null;
            failure = closeAndCollect(childLoader, failure);
            failure = closeAndCollect(parentLoader, failure);
            failure = deleteAndCollect(childDir, failure);
            failure = deleteAndCollect(parentDir, failure);
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static LoadedPair loadParentChildDuplicateServices(int cycle) throws Exception {
        Path parentDir = null;
        Path childDir = null;
        URLClassLoader parentLoader = null;
        ChildFirstUrlClassLoader childLoader = null;
        try {
            parentDir = compileDuplicateServiceToDir("parent");
            childDir = compileDuplicateServiceToDir("child");
            parentLoader = new URLClassLoader(
                    new URL[]{parentDir.toUri().toURL()}, ClassLoader.getSystemClassLoader());
            childLoader = new ChildFirstUrlClassLoader(
                    new URL[]{childDir.toUri().toURL()}, parentLoader);
            Class<?> parentClass = Class.forName(DUPLICATE_FQN, true, parentLoader);
            Class<?> childClass = Class.forName(DUPLICATE_FQN, true, childLoader);
            if (parentClass == childClass) {
                throw new AssertionFailure("loader-distinctness", "distinct", "same",
                        "parent and child resolved to the same Class object");
            }
            if (parentClass.getClassLoader() == childClass.getClassLoader()) {
                throw new AssertionFailure("loader-identity", "distinct loaders", "same loader",
                        "parent and child share a ClassLoader");
            }
            assertClassLoaderIdsDistinct(parentClass.getClassLoader(), childClass.getClassLoader());
            return new LoadedPair(parentClass, childClass, parentLoader, childLoader, parentDir, childDir);
        } catch (Exception | Error failure) {
            try {
                new LoadedPair(null, null, parentLoader, childLoader, parentDir, childDir).close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * Assert that two {@link ClassLoader}s carry distinct {@link ClassLoaderIdentity}
     * ids. Used by the parent/child loader scenario: only the selected (child) loader
     * may change, so the two loaders must be genuinely distinct by identity. Throws on
     * a shared id (both resolved to the same loader).
     *
     * <p>Package-private for a focused deterministic test; no JVM agent required.
     * {@link ClassLoaderIdentity#idOf(ClassLoader)} assigns a unique sequential id per
     * ClassLoader instance, so two distinct loaders carry distinct ids.
     */
    static void assertClassLoaderIdsDistinct(ClassLoader parentLoader, ClassLoader childLoader) {
        if (ClassLoaderIdentity.idOf(parentLoader)
                .equals(ClassLoaderIdentity.idOf(childLoader))) {
            throw new AssertionFailure("loader-identity-ids", "distinct ids", "same id",
                    "parent and child ClassLoader ids must differ");
        }
    }

    private static Path compileDuplicateServiceToDir(String prefix) throws Exception {
        Path dir = Files.createTempDirectory("duplicate-service-" + prefix + "-" + UUID.randomUUID());
        Path sourceDir = dir.resolve("com/example/duplicate");
        Files.createDirectories(sourceDir);
        Path source = sourceDir.resolve("DuplicateService.java");
        Files.writeString(source, """
                package com.example.duplicate;
                public class DuplicateService {
                    public String echo(String value) {
                        return "%s-" + value;
                    }
                }
                """.formatted(prefix), StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionFailure("javac", "present", "absent", "no system JavaCompiler on classpath");
        }
        int rc = compiler.run(null, null, null, "-d", dir.toString(), source.toString());
        if (rc != 0) {
            throw new AssertionFailure("javac", "0", String.valueOf(rc), "compiling DuplicateService(" + prefix + ")");
        }
        return dir;
    }

    private static Exception closeAndCollect(URLClassLoader loader, Exception prior) {
        if (loader == null) {
            return prior;
        }
        try {
            loader.close();
            return prior;
        } catch (Exception failure) {
            return collect(prior, failure);
        }
    }

    private static Exception deleteAndCollect(Path root, Exception prior) {
        if (root == null || !Files.exists(root)) {
            return prior;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return prior;
        } catch (Exception failure) {
            return collect(prior, failure);
        }
    }

    private static Exception collect(Exception prior, Exception next) {
        if (prior == null) {
            return next;
        }
        prior.addSuppressed(next);
        return prior;
    }

    /**
     * A child-first {@link URLClassLoader} that resolves the duplicate class name from
     * its own URLs BEFORE delegating to its parent, so a parent and a child loader can
     * each independently define a class with the same binary name. Every other name is
     * resolved parent-first (standard delegation). Mirrors the real
     * LaunchedURLClassLoader shape the compatibility matrix exercises.
     */
    private static final class ChildFirstUrlClassLoader extends URLClassLoader {
        ChildFirstUrlClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(DUPLICATE_FQN)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        try {
                            c = findClass(name);
                        } catch (ClassNotFoundException ex) {
                            c = super.loadClass(name, false);
                        }
                    }
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    // -------------------------------------------------------- result building

    private static ObjectNode buildResult(StateCycleArgumentParser.Options opts, String startedAt,
                                          int[] distribution, List<StateCycleScenarioCatalog.Scenario> scenarios,
                                          Map<String, ScenarioAccumulator> accumulators, ConflictOutcome conflict,
                                          Failure firstFailure, int completedCycles, int failedCycles,
                                          String overall) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", "1.0");
        root.put("generatedAt", Instant.now().toString());
        root.put("startedAt", startedAt);
        root.put("endedAt", Instant.now().toString());
        root.put("buildId", opts.buildId());
        root.put("command", opts.command());
        root.put("mode", opts.mode());
        root.put("workingTreeDirty", opts.workingTreeDirty());
        ArrayNode jvmArgs = root.putArray("jvmArgs");
        for (String a : opts.jvmArgs().split("\\s+")) {
            if (!a.isEmpty()) {
                jvmArgs.add(a);
            }
        }
        ObjectNode env = root.putObject("environment");
        env.put("jdkVersion", System.getProperty("java.version"));
        env.put("osName", System.getProperty("os.name"));
        env.put("osArch", System.getProperty("os.arch"));
        env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        env.put("javaHome", System.getProperty("java.home"));

        ObjectNode cycles = root.putObject("cycles");
        cycles.put("requested", opts.cycles());
        cycles.put("completed", completedCycles);
        cycles.put("failed", failedCycles);

        ArrayNode scenariosNode = root.putArray("scenarios");
        for (int i = 0; i < scenarios.size(); i++) {
            StateCycleScenarioCatalog.Scenario s = scenarios.get(i);
            ScenarioAccumulator acc = accumulators.get(s.id());
            ObjectNode sn = scenariosNode.addObject();
            sn.put("id", s.id());
            sn.put("category", s.category());
            sn.put("description", s.description());
            sn.put("concurrent", s.concurrent());
            sn.put("cyclesRequested", distribution[i]);
            sn.put("cyclesCompleted", acc.completed);
            sn.put("cyclesFailed", acc.failed);
            if (acc.first != null) {
                sn.set("firstSample", sampleNode(acc.first));
            }
            if (acc.last != null) {
                sn.set("lastSample", sampleNode(acc.last));
            }
        }

        if (conflict != null) {
            root.set("concurrentConflict", conflictNode(conflict));
        } else {
            root.putNull("concurrentConflict");
        }

        if (firstFailure != null) {
            ObjectNode ff = root.putObject("firstFailure");
            ff.put("scenario", firstFailure.scenario);
            ff.put("cycleIndex", firstFailure.cycleIndex);
            ff.put("phase", firstFailure.phase);
            ff.put("expected", firstFailure.expected);
            ff.put("actual", firstFailure.actual);
            ff.put("detail", firstFailure.detail);
        } else {
            root.putNull("firstFailure");
        }
        root.put("overall", overall);
        return root;
    }

    private static ObjectNode sampleNode(CycleSample s) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("baselineHash", s.baselineHash);
        n.put("enhancedHash", s.enhancedHash);
        n.put("updatedHash", s.updatedHash);
        n.put("afterUnloadHash", s.afterUnloadHash);
        n.put("enhancedBehavior", s.enhancedBehavior);
        n.put("updatedBehavior", s.updatedBehavior);
        n.put("restoredBehavior", s.restoredBehavior);
        n.put("enhancedDiffersFromBaseline", s.enhancedDiffersFromBaseline);
        n.put("normalizedIdentical", s.normalizedIdentical);
        n.put("hashRestored", s.hashRestored);
        n.put("rulesClearedAfterUnload", s.rulesClearedAfterUnload);
        if (s.parentBaselineHash != null) {
            ObjectNode p = n.putObject("parentLoader");
            p.put("baselineHash", s.parentBaselineHash);
            p.put("afterUnloadHash", s.parentAfterUnloadHash);
            p.put("normalizedIdentical", s.parentNormalizedIdentical);
            p.put("unchanged", s.parentBaselineHash.equals(s.parentAfterUnloadHash) && s.parentNormalizedIdentical);
        }
        return n;
    }

    private static ObjectNode conflictNode(ConflictOutcome c) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("threads", c.threads);
        n.put("applied", c.applied);
        n.put("staleRejected", c.staleRejected);
        n.put("winnerRuleId", c.winnerRuleId);
        n.put("finalBehavior", c.finalBehavior);
        n.put("restoredBehavior", c.restoredBehavior);
        n.put("mixedStateDetected", c.mixedStateDetected);
        n.put("baselineHash", c.baselineHash);
        n.put("enhancedHash", c.enhancedHash);
        n.put("afterUnloadHash", c.afterUnloadHash);
        n.put("hashRestored", c.hashRestored);
        n.put("normalizedIdentical", c.normalizedIdentical);
        return n;
    }

    private static int writeResult(String outputDir, ObjectNode root) {
        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            Path file = dir.resolve("state-cycle-result.json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
            return 0;
        } catch (Exception e) {
            System.err.println("error: failed to write state-cycle-result.json: " + e);
            return 5;
        }
    }

    // -------------------------------------------------------- verification

    private static void verify(String phase, String expected, Object actual) {
        String a = String.valueOf(actual);
        if (!expected.equals(a)) {
            throw new AssertionFailure(phase, expected, a, "behaviour mismatch");
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: StateCycleHarness --cycles <N> --output <dir> --build-id <40-hex>
                              --command <text> --jvm-args <args> --mode <pr|dev>
                              --working-tree-dirty <true|false> [--help]

                Runs real Agent/JVM lifecycle cycles distributed across the M2-B scenario
                matrix and writes <output>/state-cycle-result.json. N >= 6 so every
                scenario runs at least once.
                """);
    }

    // -------------------------------------------------------- value types

    private static final class AssertionFailure extends RuntimeException {
        final String phase;
        final String expected;
        final String actual;
        final String detail;

        AssertionFailure(String phase, String expected, String actual, String detail) {
            super(phase + ": expected=" + expected + " actual=" + actual + " detail=" + detail);
            this.phase = phase;
            this.expected = expected;
            this.actual = actual;
            this.detail = detail;
        }
    }

    private record Failure(String scenario, int cycleIndex, String phase, String expected, String actual,
                           String detail) {
    }

    private record CycleSample(String baselineHash, String enhancedHash, String updatedHash,
                               String afterUnloadHash, String enhancedBehavior, String updatedBehavior,
                               String restoredBehavior, boolean enhancedDiffersFromBaseline,
                               boolean normalizedIdentical, boolean hashRestored, boolean rulesClearedAfterUnload,
                               String parentBaselineHash, String parentAfterUnloadHash,
                               boolean parentNormalizedIdentical) {
        CycleSample withParent(String baselineHash, String afterUnloadHash, boolean normalizedIdentical) {
            return new CycleSample(this.baselineHash, this.enhancedHash, this.updatedHash,
                    this.afterUnloadHash, this.enhancedBehavior, this.updatedBehavior, this.restoredBehavior,
                    this.enhancedDiffersFromBaseline, this.normalizedIdentical, this.hashRestored,
                    this.rulesClearedAfterUnload, baselineHash, afterUnloadHash, normalizedIdentical);
        }
    }

    static final class ScenarioAccumulator {
        int completed;
        int failed;
        CycleSample first;
        CycleSample last;

        void record(int cycleIndex, CycleSample sample) {
            completed++;
            if (first == null) {
                first = sample;
            }
            last = sample;
        }

        /**
         * Record the single concurrent cycle as completed. The concurrent scenario's
         * evidence lives in the {@code concurrentConflict} block, not in a per-cycle
         * sample, so {@code first}/{@code last} remain null.
         */
        void recordConcurrent() {
            completed++;
        }

        /** Whether this accumulator holds at least one per-cycle sample. */
        boolean hasSample() {
            return first != null;
        }

        void fail() {
            failed++;
        }
    }

    private record ConflictOutcome(int threads, int applied, int staleRejected, String winnerRuleId,
                                   String finalBehavior, String restoredBehavior, boolean mixedStateDetected,
                                   String baselineHash, String enhancedHash, String afterUnloadHash,
                                   boolean hashRestored, boolean normalizedIdentical) {
    }
}
