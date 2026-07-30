package com.example.kairo.perf.leak;

import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.agent.core.DefaultProxyTargetAnalyzer;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.ProxyType;
import com.example.kairo.api.ProxyAnalysis;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.matcher.ElementMatchers;

import javax.tools.ToolProvider;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * M2-C ClassLoader/Groovy leak-check harness (&sect;9.3). Runs real create / enhance /
 * invoke / unload / close lifecycle cycles distributed across the fixed leak-surface
 * scenario matrix, observes resource trends across stable windows, and writes
 * machine-verifiable {@code leak-result.json}.
 *
 * <p>Every cycle exercises a real unloadable business {@link URLClassLoader}, a real
 * Groovy script compilation against that loader (exercising the weak-reference compile
 * cache and generation holder, whose real {@code KairoGroovyClassLoader} instances are
 * weak-tracked too), and a real Byte Buddy retransform via the agent. After all cycles the
 * agent is closed and a bounded GC sequence drains the ClassLoader reference queue; the
 * harness then reports how many explicitly-created unloadable loaders survive (residual),
 * split by business/Groovy and warm-up/measured, the heap/metaspace/thread/FD trends, the
 * bounded cache sizes, and the real Groovy compile-cache/generation diagnostics, evaluated
 * against the documented &sect;9.3 budgets.
 *
 * <p><b>Warm-up.</b> Before the baseline window the harness runs one lifecycle of every
 * measured path so one-time framework/Groovy/Byte Buddy class-loading warm-up is already
 * in the baseline (a stable first window, not a cold one). Warm-up loaders are weak-tracked
 * and accounted separately; they are never folded into the requested cycle counts.
 *
 * <p><b>Never fabricate.</b> The post-close window is measured against the real closed
 * {@code AgentRuntime}, never a null that synthesizes zeros; Groovy diagnostics are read
 * from the real compiler (fail-closed). Cleanup failures are recorded; a fixtures/runtime
 * close failure that would invalidate the post-close evidence is a lifecycle failure.
 *
 * <p>Exit codes: 0 pass; 2 bad args; 4 gate or lifecycle failure; 5 result-write error;
 * 6 schema-validation failure. The result file is always written (best-effort) even on
 * failure, except a setup failure that prevented any evidence capture.
 */
public final class LeakCheckHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LeakBudget BUDGET = LeakBudget.DOCUMENTED;
    private static final int GC_ATTEMPTS = 6;
    private static final long GC_SETTLE_MS = 80L;
    private static final int COMPILE_BATCH = 8;
    private static final int COMPILE_REPEAT = 2;

    private LeakCheckHarness() {
    }

    public static void main(String[] args) {
        System.exit(runInProcess(args));
    }

    public static int runInProcess(String[] args) {
        LeakArgumentParser.Options opts;
        try {
            opts = LeakArgumentParser.parse(args);
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

    private static int run(LeakArgumentParser.Options opts) {
        String startedAt = Instant.now().toString();
        int[] distribution = LeakScenarioCatalog.distribute(opts.cycles());
        List<LeakScenarioCatalog.Scenario> scenarios = LeakScenarioCatalog.all();

        Map<String, ScenarioAccumulator> accumulators = new LinkedHashMap<>();
        for (LeakScenarioCatalog.Scenario s : scenarios) {
            accumulators.put(s.id(), new ScenarioAccumulator());
        }

        List<LeakObservation> observations = new ArrayList<>();
        ResourceProbe probe = new ResourceProbe();
        List<String> cleanupFailures = new ArrayList<>();
        Failure firstFailure = null;
        LeakBudgetChecker.Verdict verdict = null;
        WarmupInfo warmupInfo = null;

        LeakFixtureCompiler fixtures = null;
        Instrumentation instrumentation = null;
        AgentRuntime runtime = null;
        try {
            fixtures = LeakFixtureCompiler.compile();
            instrumentation = ByteBuddyAgent.install();
            runtime = new AgentRuntime(instrumentation);
            runtime.start();

            // Warm-up: exercise every measured path once so the baseline is a stable
            // first window (post class-loading warm-up), then assert the registries that
            // unload clears are back to the pre-measurement state. Warm-up loaders are
            // weak-tracked (phase=WARMUP) and accounted separately, never as requested cycles.
            warmupInfo = warmUp(runtime, probe, fixtures, cleanupFailures);
            if (!warmupInfo.registriesResetToBaseline() && firstFailure == null) {
                firstFailure = new Failure("warmup", 0, "registries-reset", "true", "false",
                        "warm-up did not return rule/instrumentation registries to baseline");
            }

            // W0: first stable window (baseline, after warm-up, agent alive, no measured cycles yet).
            observations.add(probe.observe("baseline", true, runtime));

            for (int i = 0; i < scenarios.size() && firstFailure == null; i++) {
                LeakScenarioCatalog.Scenario s = scenarios.get(i);
                ScenarioAccumulator acc = accumulators.get(s.id());
                int cycleCount = distribution[i];
                for (int c = 1; c <= cycleCount && firstFailure == null; c++) {
                    try {
                        CycleOutcome outcome = runCycle(runtime, probe, fixtures, s.id(), c,
                                ResourceProbe.LoaderPhase.MEASURED, cleanupFailures);
                        acc.record(c, outcome);
                    } catch (AssertionFailure af) {
                        firstFailure = new Failure(s.id(), c, af.phase, af.expected, af.actual, af.detail);
                        acc.fail();
                    } catch (Throwable t) {
                        firstFailure = new Failure(s.id(), c, "execute", "", "",
                                t.getClass().getSimpleName() + ": " + t.getMessage());
                        acc.fail();
                    }
                }
                if (firstFailure == null) {
                    // Per-scenario trend window (agent alive, post full GC).
                    observations.add(probe.observe("after-" + s.id(), true, runtime));
                }
            }

            // W1: last stable window (post-cycles, agent alive). Captured even if a
            // lifecycle failure aborted the matrix so the resource trend is recorded.
            observations.add(probe.observe("post-cycles", true, runtime));
        } catch (Throwable t) {
            firstFailure = new Failure("setup", 0, "agent-setup", "", "",
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            // Close fixtures BEFORE the post-close observation so FD/heap measurements are
            // genuine (no open fixture handles inflating the post-close window). A close
            // failure that invalidates the post-close evidence is a lifecycle failure.
            if (fixtures != null) {
                try {
                    fixtures.close();
                } catch (RuntimeException e) {
                    cleanupFailures.add("fixtures: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    if (firstFailure == null) {
                        firstFailure = new Failure("cleanup", 0, "fixtures-close", "closed", "threw",
                                "fixtures.close() failed before post-close; FD/heap evidence invalidated: " + e.getMessage());
                    }
                }
            }
            if (runtime != null) {
                try {
                    runtime.close();
                } catch (RuntimeException e) {
                    cleanupFailures.add("runtime: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    if (firstFailure == null) {
                        firstFailure = new Failure("cleanup", 0, "runtime-close", "closed", "threw",
                                "runtime.close() failed before post-close; post-close evidence invalidated: " + e.getMessage());
                    }
                }
            }
        }

        // W2: post-close window against the REAL closed runtime. An extra thorough bounded
        // GC ensures every unreachable loader is collected before the residual count is read.
        // If setup failed (runtime null) or close left the runtime unreadable, the post-close
        // window cannot be captured honestly; the already-recorded lifecycle failure surfaces.
        LeakObservation postClose = null;
        if (runtime != null) {
            try {
                probe.boundedGc(GC_ATTEMPTS, GC_SETTLE_MS);
                postClose = probe.observe("post-close", true, runtime);
                observations.add(postClose);
            } catch (Throwable t) {
                if (firstFailure == null) {
                    firstFailure = new Failure("post-close", 0, "observe", "", "",
                            t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }

        // Evaluate the budget gates on the captured windows.
        LeakObservation baseline = findWindow(observations, "baseline");
        LeakObservation postCycles = findWindow(observations, "post-cycles");
        if (baseline != null && postCycles != null && postClose != null) {
            verdict = new LeakBudgetChecker(BUDGET).evaluate(baseline, postCycles, postClose, observations);
            if (firstFailure == null && verdict.firstFailure() != null) {
                LeakBudgetChecker.GateResult gf = verdict.firstFailure();
                firstFailure = new Failure("gate", 0, gf.name(),
                        "passed=true", "passed=false",
                        "gate " + gf.name() + " failed: observed=" + gf.observed()
                                + " budget=" + gf.budget() + " (" + gf.detail() + ")");
            }
        }

        int completedCycles = 0;
        int failedCycles = 0;
        for (LeakScenarioCatalog.Scenario s : scenarios) {
            ScenarioAccumulator acc = accumulators.get(s.id());
            if (acc != null) {
                completedCycles += acc.completed;
                failedCycles += acc.failed;
            }
        }

        String overall = (firstFailure == null) ? "PASSED" : "FAILED";
        ObjectNode root = buildResult(opts, startedAt, distribution, scenarios, accumulators,
                observations, verdict, firstFailure, completedCycles, failedCycles, overall,
                warmupInfo, postClose, cleanupFailures);

        int writeStatus = writeResult(opts.output(), root);
        if (writeStatus != 0) {
            return writeStatus;
        }
        // Self-validate the schema/content only when the full evidence was captured; a
        // setup/lifecycle failure that prevented the post-close window is surfaced as exit 4
        // (firstFailure) rather than re-reported as a schema failure.
        if (postClose != null) {
            try {
                JsonNode written = MAPPER.readTree(new String(Files.readAllBytes(
                        Path.of(opts.output(), "leak-result.json")), StandardCharsets.UTF_8));
                List<String> errors = new LeakResultValidator().validate(written, opts.cycles());
                if (!errors.isEmpty()) {
                    System.err.println("SCHEMA VALIDATION FAILED:");
                    errors.forEach(e -> System.err.println("  - " + e));
                    return 6;
                }
            } catch (Exception e) {
                System.err.println("error: self-validation read failed: " + e);
                return 6;
            }
        }

        if (firstFailure != null) {
            System.err.println("LEAK-CHECK FAILED: scenario=" + firstFailure.scenario
                    + " cycle=" + firstFailure.cycleIndex + " phase=" + firstFailure.phase);
            return 4;
        }
        return 0;
    }

    // -------------------------------------------------------- warm-up

    private static WarmupInfo warmUp(AgentRuntime runtime, ResourceProbe probe,
                                     LeakFixtureCompiler fixtures, List<String> cleanupFailures) {
        List<String> exercised = new ArrayList<>();
        int executed = 0;
        for (LeakScenarioCatalog.Scenario s : LeakScenarioCatalog.all()) {
            try {
                runCycle(runtime, probe, fixtures, s.id(), 1, ResourceProbe.LoaderPhase.WARMUP, cleanupFailures);
                exercised.add(s.id());
                executed++;
            } catch (AssertionFailure af) {
                throw new IllegalStateException("warm-up " + s.id() + " assertion failed: " + af.detail, af);
            } catch (Throwable t) {
                throw new IllegalStateException("warm-up " + s.id() + " failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            }
        }
        // After warm-up, unload/cleanup has happened inside each cycle; assert the registries
        // that unload clears are back to the pre-measurement state (rules + instrumentation).
        // Snapshot/journal are bounded caches, not registries, and are not asserted to zero.
        probe.boundedGc(GC_ATTEMPTS, GC_SETTLE_MS);
        int rules = runtime.rules().size();
        int instrTypes = runtime.instrumentationRegistry().typeCount();
        int instrMethods = runtime.instrumentationRegistry().methodCount();
        boolean reset = rules == 0 && instrTypes == 0 && instrMethods == 0;
        return new WarmupInfo(exercised, executed, reset);
    }

    // -------------------------------------------------------- per-scenario cycles

    private static CycleOutcome runCycle(AgentRuntime runtime, ResourceProbe probe,
                                         LeakFixtureCompiler fixtures, String scenarioId, int cycle,
                                         ResourceProbe.LoaderPhase phase, List<String> cleanupFailures) throws Exception {
        return switch (scenarioId) {
            case "business-classloader" -> businessCycle(runtime, probe, fixtures, cycle, phase, cleanupFailures);
            case "jdk-proxy" -> jdkProxyCycle(runtime, probe, fixtures, cycle, phase, cleanupFailures);
            case "lambda-bridge-synthetic" -> lambdaBridgeSyntheticCycle(runtime, probe, fixtures, cycle, phase, cleanupFailures);
            case "groovy-compile-cache" -> groovyCompileCacheCycle(runtime, probe, fixtures, cycle, phase, cleanupFailures);
            case "cglib-detection" -> cglibDetectionCycle(runtime, probe, fixtures, cycle, phase, cleanupFailures);
            case "bytebuddy-generated" -> byteBuddyGeneratedCycle(runtime, probe, fixtures, cycle, phase, cleanupFailures);
            default -> throw new IllegalStateException("no leak runner for " + scenarioId);
        };
    }

    /** Rule-id / label suffix tag distinguishing warm-up from measured cycles. */
    private static String tag(ResourceProbe.LoaderPhase phase) {
        return phase == ResourceProbe.LoaderPhase.WARMUP ? "w" : "";
    }

    private static CycleOutcome businessCycle(AgentRuntime runtime, ResourceProbe probe,
                                              LeakFixtureCompiler fixtures, int cycle,
                                              ResourceProbe.LoaderPhase phase, List<String> cleanupFailures) throws Exception {
        URLClassLoader loader = newLoader(fixtures);
        String t = tag(phase);
        try {
            Class<?> clazz = Class.forName(fixtures.binaryName(LeakFixtureCompiler.LEAK_SERVICE), true, loader);
            Method echo = clazz.getMethod("echo", String.class);
            LeakEchoContract inst = (LeakEchoContract) clazz.getDeclaredConstructor().newInstance();
            verify("baseline", "echo:x", inst.echo("x"));
            String ruleId = "biz-" + t + cycle;
            runtime.publish(echo, rule(ruleId, echo, EnhancementLocation.METHOD_RETURN,
                    "return mock.returnValue('BIZ-" + cycle + "')"), "leak");
            String enhanced = inst.echo("x");
            verify("enhanced", "BIZ-" + cycle, enhanced);
            runtime.remove(ruleId, "leak");
            String restored = inst.echo("x");
            verify("restored", "echo:x", restored);
            return new CycleOutcome(enhanced, restored);
        } finally {
            probe.register(loader, ClassLoaderIdentity.idOf(loader), "business-" + t + cycle,
                    ResourceProbe.LoaderKind.BUSINESS, phase);
            probe.registerGroovyLoaders(runtime, phase);
            closeOrRecord(loader, "business-" + t + cycle, cleanupFailures);
        }
    }

    private static CycleOutcome jdkProxyCycle(AgentRuntime runtime, ResourceProbe probe,
                                              LeakFixtureCompiler fixtures, int cycle,
                                              ResourceProbe.LoaderPhase phase, List<String> cleanupFailures) throws Exception {
        URLClassLoader loader = newLoader(fixtures);
        String t = tag(phase);
        try {
            Class<?> iface = Class.forName(fixtures.binaryName(LeakFixtureCompiler.LEAK_INTERFACE), true, loader);
            Class<?> impl = Class.forName(fixtures.binaryName(LeakFixtureCompiler.LEAK_SERVICE), true, loader);
            LeakEchoContract implInstance =
                    (LeakEchoContract) impl.getDeclaredConstructor().newInstance();
            Method echo = impl.getMethod("echo", String.class);
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{iface, LeakEchoContract.class},
                    (p, method, args) -> implInstance.echo((String) args[0]));
            // The JDK proxy class is defined in the business loader (a generated class
            // that must be reclaimable with the loader).
            verify("proxy-class-in-loader", "true",
                    String.valueOf(proxy.getClass().getClassLoader() == loader));
            verify("baseline", "echo:x", implInstance.echo("x"));
            String ruleId = "proxy-" + t + cycle;
            runtime.publish(echo, rule(ruleId, echo, EnhancementLocation.METHOD_RETURN,
                    "return mock.returnValue('PROXY-" + cycle + "')"), "leak");
            // Invoke THROUGH the proxy: it delegates to the enhanced impl method.
            String enhanced = ((LeakEchoContract) proxy).echo("x");
            verify("enhanced-through-proxy", "PROXY-" + cycle, enhanced);
            runtime.remove(ruleId, "leak");
            String restored = ((LeakEchoContract) proxy).echo("x");
            verify("restored", "echo:x", restored);
            return new CycleOutcome(enhanced, restored);
        } finally {
            probe.register(loader, ClassLoaderIdentity.idOf(loader), "jdk-proxy-" + t + cycle,
                    ResourceProbe.LoaderKind.BUSINESS, phase);
            probe.registerGroovyLoaders(runtime, phase);
            closeOrRecord(loader, "jdk-proxy-" + t + cycle, cleanupFailures);
        }
    }

    private static CycleOutcome lambdaBridgeSyntheticCycle(AgentRuntime runtime, ResourceProbe probe,
                                                           LeakFixtureCompiler fixtures, int cycle,
                                                           ResourceProbe.LoaderPhase phase, List<String> cleanupFailures) throws Exception {
        URLClassLoader loader = newLoader(fixtures);
        String t = tag(phase);
        try {
            // Exercise the lambda/synthetic hidden class: invoking transform() forces the
            // JVM to define the lambda form in this loader.
            Class<?> lambdaHolder = Class.forName(fixtures.binaryName(LeakFixtureCompiler.LAMBDA_HOLDER), true, loader);
            IntUnaryOperator lambdaInst =
                    (IntUnaryOperator) lambdaHolder.getDeclaredConstructor().newInstance();
            verify("lambda-transform", String.valueOf(5 * 3 + 1), lambdaInst.applyAsInt(5));

            // Exercise the bridge/synthetic policy path: publishing on the generated
            // bridge method must be rejected by the agent's SyntheticBridgePolicy.
            Class<?> genericSub = Class.forName(fixtures.binaryName(LeakFixtureCompiler.GENERIC_SUB), true, loader);
            Method bridge = genericSub.getMethod("process", Object.class);
            verify("bridge-is-bridge", "true", String.valueOf(bridge.isBridge()));
            boolean rejected = false;
            try {
                runtime.publish(bridge, rule("bridge-" + t + cycle, bridge, EnhancementLocation.METHOD_RETURN,
                        "return mock.returnValue('BR')"), "leak");
            } catch (IllegalArgumentException e) {
                rejected = e.getMessage() != null && e.getMessage().contains("Synthetic and bridge");
            }
            verify("bridge-rejected", "true", String.valueOf(rejected));

            // Enhance the real (non-bridge) method; the bridge delegates to it.
            Method real = genericSub.getMethod("process", String.class);
            LeakStringProcessor subInst =
                    (LeakStringProcessor) genericSub.getDeclaredConstructor().newInstance();
            verify("baseline", "sub:x", subInst.process("x"));
            String ruleId = "syn-" + t + cycle;
            runtime.publish(real, rule(ruleId, real, EnhancementLocation.METHOD_RETURN,
                    "return mock.returnValue('SYN-" + cycle + "')"), "leak");
            String enhanced = subInst.process("x");
            verify("enhanced", "SYN-" + cycle, enhanced);
            runtime.remove(ruleId, "leak");
            String restored = subInst.process("x");
            verify("restored", "sub:x", restored);
            return new CycleOutcome(enhanced, restored);
        } finally {
            probe.register(loader, ClassLoaderIdentity.idOf(loader), "lambda-bridge-synthetic-" + t + cycle,
                    ResourceProbe.LoaderKind.BUSINESS, phase);
            probe.registerGroovyLoaders(runtime, phase);
            closeOrRecord(loader, "lambda-bridge-synthetic-" + t + cycle, cleanupFailures);
        }
    }

    private static CycleOutcome groovyCompileCacheCycle(AgentRuntime runtime, ResourceProbe probe,
                                                        LeakFixtureCompiler fixtures, int cycle,
                                                        ResourceProbe.LoaderPhase phase, List<String> cleanupFailures) throws Exception {
        URLClassLoader loader = newLoader(fixtures);
        String t = tag(phase);
        try {
            Class<?> clazz = Class.forName(fixtures.binaryName(LeakFixtureCompiler.LEAK_SERVICE), true, loader);
            Method echo = clazz.getMethod("echo", String.class);
            LeakEchoContract inst = (LeakEchoContract) clazz.getDeclaredConstructor().newInstance();
            // A batch of distinct scripts (cache miss) against this loader.
            for (int i = 0; i < COMPILE_BATCH; i++) {
                String ruleId = "gc-" + t + cycle + "-" + i;
                runtime.publish(echo, rule(ruleId, echo, EnhancementLocation.METHOD_RETURN,
                        "return mock.returnValue('GC" + i + "')"), "leak");
                verify("cache-miss-" + i, "GC" + i, inst.echo("x"));
                runtime.remove(ruleId, "leak");
            }
            // Repeats of the first script (cache hit: same ruleId/version/script/loader).
            for (int j = 0; j < COMPILE_REPEAT; j++) {
                String ruleId = "gc-" + t + cycle + "-0";
                runtime.publish(echo, rule(ruleId, echo, EnhancementLocation.METHOD_RETURN,
                        "return mock.returnValue('GC0')"), "leak");
                verify("cache-hit-" + j, "GC0", inst.echo("x"));
                runtime.remove(ruleId, "leak");
            }
            String restored = inst.echo("x");
            verify("restored", "echo:x", restored);
            return new CycleOutcome("GC0", restored);
        } finally {
            probe.register(loader, ClassLoaderIdentity.idOf(loader), "groovy-compile-cache-" + t + cycle,
                    ResourceProbe.LoaderKind.BUSINESS, phase);
            probe.registerGroovyLoaders(runtime, phase);
            closeOrRecord(loader, "groovy-compile-cache-" + t + cycle, cleanupFailures);
        }
    }

    private static CycleOutcome cglibDetectionCycle(AgentRuntime runtime, ResourceProbe probe,
                                                    LeakFixtureCompiler fixtures, int cycle,
                                                    ResourceProbe.LoaderPhase phase, List<String> cleanupFailures) throws Exception {
        URLClassLoader loader = newLoader(fixtures);
        String t = tag(phase);
        try {
            Class<?> clazz = Class.forName(fixtures.binaryName(LeakFixtureCompiler.CGLIB_ENHANCER), true, loader);
            // Exercise the product's CGLIB detection path (name-based classification).
            // This is detection-only: no CGLIB runtime dependency, no CGLIB runtime generation.
            ProxyAnalysis analysis = new DefaultProxyTargetAnalyzer().analyze(clazz);
            verify("cglib-classified", ProxyType.CGLIB.name(), analysis.proxyType().name());
            Method echo = clazz.getMethod("echo", String.class);
            LeakEchoContract inst = (LeakEchoContract) clazz.getDeclaredConstructor().newInstance();
            verify("baseline", "cglib:x", inst.echo("x"));
            String ruleId = "cgb-" + t + cycle;
            runtime.publish(echo, rule(ruleId, echo, EnhancementLocation.METHOD_RETURN,
                    "return mock.returnValue('CGLIB-" + cycle + "')"), "leak");
            String enhanced = inst.echo("x");
            verify("enhanced", "CGLIB-" + cycle, enhanced);
            runtime.remove(ruleId, "leak");
            String restored = inst.echo("x");
            verify("restored", "cglib:x", restored);
            return new CycleOutcome(enhanced, restored);
        } finally {
            probe.register(loader, ClassLoaderIdentity.idOf(loader), "cglib-detection-" + t + cycle,
                    ResourceProbe.LoaderKind.BUSINESS, phase);
            probe.registerGroovyLoaders(runtime, phase);
            closeOrRecord(loader, "cglib-detection-" + t + cycle, cleanupFailures);
        }
    }

    /**
     * A genuinely Byte Buddy <em>generated</em> target class: Byte Buddy generates a
     * subclass (name carrying {@code $ByteBuddy$}), the bytes are defined IN an unloadable
     * business {@link URLClassLoader} so the defining loader is reclaimable, the real
     * analyzer classifies it ({@link ProxyType#BYTE_BUDDY}), and the generated class is
     * enhanced / invoked / unloaded through the real agent. This is distinct from the
     * ordinary Byte Buddy retransformation every scenario performs.
     */
    private static CycleOutcome byteBuddyGeneratedCycle(AgentRuntime runtime, ResourceProbe probe,
                                                        LeakFixtureCompiler fixtures, int cycle,
                                                        ResourceProbe.LoaderPhase phase, List<String> cleanupFailures) throws Exception {
        URLClassLoader loader = newLoader(fixtures);
        String t = tag(phase);
        try {
            // Load the superclass (LeakService) in the business loader so the generated
            // subclass's superclass is resolvable in the same loader.
            Class<?> superClass = Class.forName(fixtures.binaryName(LeakFixtureCompiler.LEAK_SERVICE), true, loader);
            // Generate a Byte Buddy subclass that overrides echo (delegating to super) so the
            // declaring class of the enhanced method is the generated class itself.
            Class<?> generated = new ByteBuddy()
                    .subclass(superClass)
                    .name(superClass.getName() + "$ByteBuddy$Gen" + cycle)
                    .method(ElementMatchers.named("echo").and(ElementMatchers.takesArguments(String.class)))
                    .intercept(MethodCall.invokeSuper().withAllArguments())
                    .make()
                    .load(loader, ClassLoadingStrategy.Default.INJECTION)
                    .getLoaded();
            // The defining loader of the generated class IS the business loader (unloadable).
            verify("generated-class-in-loader", "true",
                    String.valueOf(generated.getClassLoader() == loader));
            // Classify via the real analyzer; record the real (BYTE_BUDDY) classification.
            ProxyAnalysis analysis = new DefaultProxyTargetAnalyzer().analyze(generated);
            verify("bytebuddy-classified", ProxyType.BYTE_BUDDY.name(), analysis.proxyType().name());
            Method echo = generated.getMethod("echo", String.class);
            LeakEchoContract inst =
                    (LeakEchoContract) generated.getDeclaredConstructor().newInstance();
            verify("baseline", "echo:x", inst.echo("x"));
            String ruleId = "bb-" + t + cycle;
            runtime.publish(echo, rule(ruleId, echo, EnhancementLocation.METHOD_RETURN,
                    "return mock.returnValue('BB-" + cycle + "')"), "leak");
            String enhanced = inst.echo("x");
            verify("enhanced", "BB-" + cycle, enhanced);
            runtime.remove(ruleId, "leak");
            String restored = inst.echo("x");
            verify("restored", "echo:x", restored);
            return new CycleOutcome(enhanced, restored);
        } finally {
            probe.register(loader, ClassLoaderIdentity.idOf(loader), "bytebuddy-generated-" + t + cycle,
                    ResourceProbe.LoaderKind.BUSINESS, phase);
            probe.registerGroovyLoaders(runtime, phase);
            closeOrRecord(loader, "bytebuddy-generated-" + t + cycle, cleanupFailures);
        }
    }

    // -------------------------------------------------------- loader / rule helpers

    private static URLClassLoader newLoader(LeakFixtureCompiler fixtures) throws Exception {
        return new URLClassLoader(new URL[]{fixtures.directory().toUri().toURL()},
                ClassLoader.getSystemClassLoader());
    }

    /** Close a loader, recording any failure as evidence rather than swallowing it. */
    private static void closeOrRecord(URLClassLoader loader, String label, List<String> cleanupFailures) {
        try {
            loader.close();
        } catch (Exception e) {
            // A loader close failure does not invalidate the post-close evidence (a not-closed
            // loader is still weak-tracked and surfaces as an honest residual), so it is
            // recorded as evidence rather than treated as a lifecycle failure.
            if (cleanupFailures != null) {
                cleanupFailures.add(label + ".close(): " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private static MockRule rule(String id, Method method, EnhancementLocation location, String script) {
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
                .priority(100)
                .percentage(100)
                .script(script)
                .scriptHash(Integer.toHexString(script.hashCode()))
                .failOpen(true)
                .enabled(true)
                .build();
    }

    private static void verify(String phase, String expected, Object actual) {
        String a = String.valueOf(actual);
        if (!expected.equals(a)) {
            throw new AssertionFailure(phase, expected, a, "behaviour mismatch");
        }
    }

    private static LeakObservation findWindow(List<LeakObservation> observations, String label) {
        for (LeakObservation o : observations) {
            if (label.equals(o.label())) {
                return o;
            }
        }
        return null;
    }

    // -------------------------------------------------------- result building

    private static ObjectNode buildResult(LeakArgumentParser.Options opts, String startedAt,
                                          int[] distribution, List<LeakScenarioCatalog.Scenario> scenarios,
                                          Map<String, ScenarioAccumulator> accumulators,
                                          List<LeakObservation> observations, LeakBudgetChecker.Verdict verdict,
                                          Failure firstFailure, int completedCycles, int failedCycles,
                                          String overall, WarmupInfo warmupInfo, LeakObservation postClose,
                                          List<String> cleanupFailures) {
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
        env.put("pid", ProcessHandle.current().pid());
        env.put("javacAvailable", ToolProvider.getSystemJavaCompiler() != null);

        ObjectNode cycles = root.putObject("cycles");
        cycles.put("requested", opts.cycles());
        cycles.put("completed", completedCycles);
        cycles.put("failed", failedCycles);

        ArrayNode scenariosNode = root.putArray("scenarios");
        for (int i = 0; i < scenarios.size(); i++) {
            LeakScenarioCatalog.Scenario s = scenarios.get(i);
            ScenarioAccumulator acc = accumulators.get(s.id());
            ObjectNode sn = scenariosNode.addObject();
            sn.put("id", s.id());
            sn.put("category", s.category());
            sn.put("description", s.description());
            sn.put("leakSurface", s.leakSurface());
            sn.put("cyclesRequested", distribution[i]);
            sn.put("cyclesCompleted", acc == null ? 0 : acc.completed);
            sn.put("cyclesFailed", acc == null ? 0 : acc.failed);
            if (acc != null && acc.first != null) {
                ObjectNode o = sn.putObject("firstOutcome");
                o.put("enhancedBehavior", acc.first.enhancedBehavior);
                o.put("restoredBehavior", acc.first.restoredBehavior);
            }
            if (acc != null && acc.last != null && acc.first != acc.last) {
                ObjectNode o = sn.putObject("lastOutcome");
                o.put("enhancedBehavior", acc.last.enhancedBehavior);
                o.put("restoredBehavior", acc.last.restoredBehavior);
            }
        }

        // Warm-up evidence: explicit, separate from requested cycle counts. Loader counts
        // are the final residual accounting from the post-close window.
        ObjectNode warmup = root.putObject("warmup");
        ArrayNode paths = warmup.putArray("exercisedPaths");
        if (warmupInfo != null) {
            warmupInfo.exercisedPaths.forEach(paths::add);
            warmup.put("cyclesExecuted", warmupInfo.cyclesExecuted);
            warmup.put("registriesResetToBaseline", warmupInfo.registriesResetToBaseline);
        } else {
            warmup.put("cyclesExecuted", 0);
            warmup.put("registriesResetToBaseline", false);
        }
        if (postClose != null) {
            warmup.put("businessTrackedLoaders", postClose.warmupBusiness().tracked());
            warmup.put("businessLiveTrackedLoaders", postClose.warmupBusiness().live());
            warmup.put("businessCollectedLoaders", postClose.warmupBusiness().collected());
            warmup.put("groovyTrackedLoaders", postClose.warmupGroovy().tracked());
            warmup.put("groovyLiveTrackedLoaders", postClose.warmupGroovy().live());
            warmup.put("groovyCollectedLoaders", postClose.warmupGroovy().collected());
        } else {
            for (String f : List.of("businessTrackedLoaders", "businessLiveTrackedLoaders",
                    "businessCollectedLoaders", "groovyTrackedLoaders", "groovyLiveTrackedLoaders",
                    "groovyCollectedLoaders")) {
                warmup.put(f, 0);
            }
        }

        ArrayNode obsNode = root.putArray("observations");
        for (LeakObservation o : observations) {
            ObjectNode on = obsNode.addObject();
            on.put("label", o.label());
            on.put("postFullGc", o.postFullGc());
            on.put("timestamp", o.timestamp().toString());
            on.put("heapUsedBytes", o.heapUsedBytes());
            on.put("metaspaceUsedBytes", o.metaspaceUsedBytes());
            on.put("threadCount", o.threadCount());
            on.put("openFdCount", o.openFdCount());
            on.put("loadedClassCount", o.loadedClassCount());
            on.put("publishedRuleCount", o.publishedRuleCount());
            on.put("snapshotCount", o.snapshotCount());
            on.put("journalRecordCount", o.journalRecordCount());
            on.put("instrumentationTypeCount", o.instrumentationTypeCount());
            on.put("instrumentationMethodCount", o.instrumentationMethodCount());
            on.put("trackedLoadersTotal", o.total().tracked());
            on.put("liveTrackedLoaders", o.total().live());
            on.put("collectedLoaders", o.total().collected());
            writeBucket(on, "measuredBusiness", o.measuredBusiness());
            writeBucket(on, "measuredGroovy", o.measuredGroovy());
            writeBucket(on, "warmupBusiness", o.warmupBusiness());
            writeBucket(on, "warmupGroovy", o.warmupGroovy());
            on.put("groovyCacheEntries", o.groovy().cacheEntries());
            on.put("groovyGenerationCount", o.groovy().generationCount());
            on.put("groovyMaxClassesInGeneration", o.groovy().maxClassesInGeneration());
            on.put("groovyGenerationHighWater", o.groovy().generationHighWater());
            on.put("groovyLiveTrackedLoaders", o.groovy().liveGroovyLoaders());
        }

        ObjectNode budgets = root.putObject("budgets");
        budgets.put("maxResidualClassLoaders", BUDGET.maxResidualClassLoaders());
        budgets.put("maxThreadDelta", BUDGET.maxThreadDelta());
        budgets.put("maxFdDelta", BUDGET.maxFdDelta());
        budgets.put("maxHeapGrowthPct", BUDGET.maxHeapGrowthPct());
        budgets.put("maxMetaspaceGrowthPct", BUDGET.maxMetaspaceGrowthPct());
        budgets.put("snapshotMaxEntries", BUDGET.snapshotMaxEntries());
        budgets.put("journalMaxRecords", BUDGET.journalMaxRecords());
        budgets.put("groovyCacheMaxEntries", BUDGET.groovyCacheMaxEntries());
        budgets.put("generationMaxClasses", BUDGET.generationMaxClasses());

        ArrayNode gatesNode = root.putArray("gates");
        if (verdict != null) {
            for (LeakBudgetChecker.GateResult g : verdict.gates()) {
                ObjectNode gn = gatesNode.addObject();
                gn.put("name", g.name());
                gn.put("passed", g.passed());
                gn.put("observed", g.observed());
                gn.put("budget", g.budget());
                gn.put("detail", g.detail());
            }
        }

        ArrayNode cleanupNode = root.putArray("cleanupFailures");
        cleanupFailures.forEach(cleanupNode::add);

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

    private static void writeBucket(ObjectNode on, String prefix, LeakObservation.LoaderCounts counts) {
        on.put(prefix + "TrackedLoaders", counts.tracked());
        on.put(prefix + "LiveTrackedLoaders", counts.live());
        on.put(prefix + "CollectedLoaders", counts.collected());
    }

    private static int writeResult(String outputDir, ObjectNode root) {
        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            Path file = dir.resolve("leak-result.json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
            return 0;
        } catch (Exception e) {
            System.err.println("error: failed to write leak-result.json: " + e);
            return 5;
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: LeakCheckHarness --cycles <N> --output <dir> --build-id <40-hex>
                              --command <text> --jvm-args <args> --mode <pr|dev>
                              --working-tree-dirty <true|false> [--help]

                Runs real create/enhance/invoke/unload/close lifecycle cycles across the
                M2-C leak-surface matrix and writes <output>/leak-result.json. N >= 6 so
                every scenario runs at least once (including the Byte Buddy generated class).
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

    private record Failure(String scenario, int cycleIndex, String phase, String expected,
                           String actual, String detail) {
    }

    private record CycleOutcome(String enhancedBehavior, String restoredBehavior) {
    }

    private record WarmupInfo(List<String> exercisedPaths, int cyclesExecuted,
                              boolean registriesResetToBaseline) {
    }

    static final class ScenarioAccumulator {
        int completed;
        int failed;
        CycleOutcome first;
        CycleOutcome last;

        void record(int cycleIndex, CycleOutcome outcome) {
            completed++;
            if (first == null) {
                first = outcome;
            }
            last = outcome;
        }

        void fail() {
            failed++;
        }
    }
}
