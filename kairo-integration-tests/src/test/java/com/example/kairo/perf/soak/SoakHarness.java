package com.example.kairo.perf.soak;

import com.example.demo.OrderService;
import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.agent.core.bytecode.BytecodeHash;
import com.example.kairo.agent.core.bytecode.BytecodeSnapshotKey;
import com.example.kairo.agent.core.bytecode.ClassIdentities;
import com.example.kairo.api.ApplyChainRequest;
import com.example.kairo.api.ApplyChainResult;
import com.example.kairo.api.ApplyChainStatus;
import com.example.kairo.api.ChainDesiredState;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.EnhancementTarget;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.RuleChainEntry;
import com.example.kairo.api.RuleChainRevision;
import com.example.kairo.api.RuleChainSpec;
import com.example.kairo.api.bytecode.BytecodeDiffResult;
import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationRevision;
import com.example.kairo.api.snapshot.AgentRuntimeSnapshot;
import com.example.kairo.agent.server.SoakPlatformLink;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.bytebuddy.agent.ByteBuddyAgent;

import javax.tools.ToolProvider;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.ClassFileTransformer;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sun.management.UnixOperatingSystemMXBean;

/**
 * M2-D long-running stability / soak harness (&sect;9.4). Drives a real
 * {@link AgentRuntime} (real Byte Buddy agent + real rule chain lifecycle) under sustained
 * load and writes machine-verifiable {@code soak-result.json} plus a raw per-minute
 * time-series file.
 *
 * <p><b>Cadence (fixed, &sect;9.4).</b> Every 1 minute a time-series summary is captured;
 * continuous real enhanced-target invocations run between cadence events; every 5 minutes a
 * second, classloader-isolated target performs a real enhance / update / partial-unload / full-unload
 * batch (reusing the M2-B
 * {@code chainCycle} lifecycle and the precise-unload hash-restore proof); every 30 minutes an
 * Agent/Platform command-channel disconnect/recovery runs. The live {@link AgentRuntime} and
 * enhanced target stay alive while the real agent-side Platform poller is closed. Reconnect uses
 * a fresh poller and the real {@code REFRESH_RUNTIME_STATE} command path to prove that the same
 * process and applied chain remain observable. Full Platform/DB reconciliation is already covered
 * by the M1 closed-loop acceptance test and is deliberately not duplicated in this resource soak.
 *
 * <p><b>Time.</b> The production {@link SoakClock.WallClock} advances real time as the loop
 * runs real invocations, so the 1m/5m/30m cadence fires at genuine wall-clock intervals for RC
 * ({@code PT2H}) / RELEASE ({@code P7D}). The test-only {@link SoakClock.AcceleratedClock}
 * advances virtual time per tick so a full cadence sequence completes in milliseconds while
 * every cadence boundary still does real lifecycle work. The fixed cadence is identical for
 * both; only the clock implementation differs.
 *
 * <p><b>Never fabricate / never hide.</b> Each failure condition (&sect;9.4) is recorded and
 * exits non-zero: abnormal exit (any uncaught {@link Throwable}), OOME evidence (a caught
 * {@link OutOfMemoryError}), leaked business exceptions (an enhanced invocation throwing),
 * persistent state drift (&gt; 5 min), a sustained resource-budget breach (&gt; 5 min), and
 * inability to perform a precise unload (hash not restored / lifecycle rules not cleared). The
 * continuous and lifecycle targets intentionally use different classes, and every lifecycle
 * batch owns a disposable ClassLoader: retransformation is class-wide, and repeatedly redefining
 * one long-lived class would make HotSpot retain obsolete method metadata and cause the harness
 * to measure its own test topology instead of Kairo's retained state.
 * The harness
 * does not inflate timeouts, does not sleep to fake a lifecycle, does not continue on error,
 * and does not weaken assertions. A summary's heap read follows one {@code System.gc()} so it
 * measures retained (reclaimable) heap, not transient allocation noise - this is the same
 * observation concept as the M2-C probe, not a sleep.
 *
 * <p>Exit codes: 0 pass; 2 bad args; 3 setup error; 4 stability/lifecycle failure
 * (firstFailure recorded); 5 result-write error; 6 schema-validation failure. The result file
 * is always written (best-effort) even on failure.
 */
public final class SoakHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SoakCadence CADENCE = SoakCadence.DOCUMENTED;
    private static final SoakBudget BUDGET = SoakBudget.DOCUMENTED;
    private static final int BURST_SIZE = 200;
    private static final int WARMUP_MIN_LIFECYCLE_BATCHES = 128;
    private static final int WARMUP_MAX_LIFECYCLE_BATCHES = 512;
    private static final int WARMUP_SAMPLE_EVERY_BATCHES = 32;
    private static final int WARMUP_PLATEAU_WINDOW_SAMPLES = 5;
    private static final double WARMUP_MAX_WINDOW_METASPACE_GROWTH_PCT = 2.0;
    private static final int WARMUP_ALLOWED_OUTSTANDING_LOADERS = 2;

    private static final MemoryMXBean MEMORY_MX = ManagementFactory.getMemoryMXBean();
    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final ClassLoadingMXBean CLASSLOAD_MX = ManagementFactory.getClassLoadingMXBean();
    private static final MemoryPoolMXBean METASPACE_POOL = findMetaspacePool();
    private static final UnixOperatingSystemMXBean UNIX_OS = findUnixOs();

    private SoakHarness() {
    }

    public static void main(String[] args) {
        System.exit(runInProcess(args));
    }

    /** Production entry: parses args and runs with the real {@link WallClock}. */
    public static int runInProcess(String[] args) {
        SoakArgumentParser.Options opts;
        try {
            opts = SoakArgumentParser.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }
        if (opts.help()) {
            printUsage();
            return 0;
        }
        return run(opts, new SoakClock.WallClock());
    }

    /**
     * Test-only entry: runs with an injected clock so the fixed cadence can be exercised on a
     * tiny real-time budget. The cadence constants and the production {@link WallClock} default
     * are unchanged; only the time source is replaced.
     */
    static int runWithClock(SoakArgumentParser.Options opts, SoakClock clock) {
        return run(opts, clock);
    }

    private static int run(SoakArgumentParser.Options opts, SoakClock clock) {
        Instant startedAt = clock.now();
        Context ctx = new Context();
        ctx.opts = opts;
        ctx.clock = clock;

        Instrumentation instrumentation = null;
        try {
            openTimeSeries(ctx);
            instrumentation = ByteBuddyAgent.install();
            ctx.instrumentation = instrumentation;
            ctx.runtime = new AgentRuntime(instrumentation);
            ctx.runtime.start();
            ctx.processStartId = "soak-host:" + ProcessHandle.current().pid() + ":" + startedAt.toEpochMilli();
            ctx.platformLink = new SoakPlatformLink(ctx.runtime, "soak-agent", ctx.processStartId);

            setupContinuousTarget(ctx);
            // Begin the continuous phase with the primary enhanced.
            enhanceContinuous(ctx);
            ctx.expectedPrimaryValue = 77;

            // Prime every lifecycle path that otherwise first appears after the measurement
            // baseline (the 5-minute batch and the 30-minute reconnect). Without this explicit
            // cold-start phase, minute 1 is not a stable JVM baseline: the first reconnect alone
            // lazily loads hundreds of harness/protocol classes and can look like a sustained
            // Metaspace leak even when usage immediately plateaus. Warm-up uses cycle zero so its
            // commands cannot collide with measured cycle ids, and the reset below excludes
            // it from duration/cadence/evidence counts. Product budgets remain unchanged.
            warmUpMeasurementPaths(ctx);
            clock.reset();
            startedAt = clock.now();
            SoakBudgetChecker checker = new SoakBudgetChecker(BUDGET);

            Duration duration = opts.duration();
            Duration nextSummary = CADENCE.summaryInterval();
            Duration nextBatch = CADENCE.batchInterval();
            Duration nextDisconnect = CADENCE.disconnectInterval();

            while (ctx.firstFailure == null) {
                Duration elapsed = clock.elapsed();
                if (elapsed.compareTo(duration) >= 0) {
                    break;
                }
                // Continuous real enhanced-target invocations.
                Failure burstFailure = runBurst(ctx);
                if (burstFailure != null) {
                    ctx.firstFailure = burstFailure;
                    break;
                }
                clock.tick();
                elapsed = clock.elapsed();

                // 1-minute time-series summary (handles a tick crossing several boundaries).
                while (elapsed.compareTo(nextSummary) >= 0 && ctx.firstFailure == null) {
                    SoakObservation obs = captureSummary(ctx, checker);
                    SoakBudgetChecker.Failure breach = checker.evaluate(obs);
                    if (breach != null) {
                        obs = obs.withSustainedBreach(true);
                        ctx.firstFailure = toFailure("summary", breach, clock);
                    }
                    SoakBudgetChecker.Failure drift = checker.evaluateDrift(
                            obs.driftDetected(), currentDriftStartedAtSeconds(ctx),
                            obs.elapsedSeconds());
                    if (ctx.firstFailure == null && drift != null) {
                        ctx.firstFailure = toFailure("summary", drift, clock);
                    }
                    recordObservation(ctx, obs);
                    if (ctx.firstFailure != null) {
                        break;
                    }
                    nextSummary = nextSummary.plus(CADENCE.summaryInterval());
                }
                if (ctx.firstFailure != null) {
                    break;
                }
                // 5-minute enhance/update/partial-unload/full-unload batch.
                while (elapsed.compareTo(nextBatch) >= 0 && ctx.firstFailure == null) {
                    ctx.firstFailure = runBatch(ctx);
                    if (ctx.firstFailure == null) {
                        ctx.batchesRun++;
                    } else {
                        ctx.failedBatches++;
                    }
                    nextBatch = nextBatch.plus(CADENCE.batchInterval());
                }
                if (ctx.firstFailure != null) {
                    break;
                }
                // 30-minute Agent/Platform disconnect/recovery.
                while (elapsed.compareTo(nextDisconnect) >= 0 && ctx.firstFailure == null) {
                    ctx.firstFailure = runDisconnectRecovery(ctx);
                    nextDisconnect = nextDisconnect.plus(CADENCE.disconnectInterval());
                }
            }
        } catch (OutOfMemoryError oom) {
            ctx.oomEvidence = true;
            ctx.firstFailure = new Failure("oom", "execute", "out-of-memory", "", "",
                    oom.getClass().getSimpleName() + ": " + String.valueOf(oom.getMessage()),
                    clock.now(), elapsedSeconds(ctx, clock), true);
        } catch (EvidenceWriteFailure e) {
            ctx.evidenceWriteFailure = true;
            ctx.firstFailure = capture(ctx, "evidence", "result-write", e, clock);
        } catch (Throwable t) {
            ctx.firstFailure = capture(ctx, "setup", "abnormal-exit", t, clock);
        } finally {
            if (ctx.platformLink != null) {
                try {
                    ctx.platformLink.close();
                } catch (RuntimeException e) {
                    if (ctx.firstFailure == null) {
                        ctx.firstFailure = capture(ctx, "cleanup", "platform-link-close", e, clock);
                    }
                }
            }
            if (ctx.runtime != null) {
                try {
                    ctx.runtime.close();
                } catch (RuntimeException e) {
                    if (ctx.firstFailure == null) {
                        ctx.firstFailure = capture(ctx, "cleanup", "runtime-close", e, clock);
                    }
                }
            }
        }

        Duration completed = clock.elapsed();
        boolean completedFull = completed.compareTo(opts.duration()) >= 0 && ctx.firstFailure == null;
        String finalState = ctx.firstFailure == null ? "COMPLETED" : (ctx.oomEvidence ? "ABORTED" : "FAILED");
        String overall = ctx.firstFailure == null ? "PASSED" : "FAILED";

        ObjectNode root = buildResult(ctx, startedAt, clock, completed, completedFull, finalState, overall);
        int writeStatus = writeResult(ctx, root);
        if (writeStatus != 0) {
            return writeStatus;
        }
        // Self-validate the schema/content.
        try {
            JsonNode written = MAPPER.readTree(new String(Files.readAllBytes(
                    Path.of(opts.output(), "soak-result.json")), StandardCharsets.UTF_8));
            List<String> errors = new SoakResultValidator().validate(written, opts.duration());
            if (!errors.isEmpty()) {
                System.err.println("SCHEMA VALIDATION FAILED:");
                errors.forEach(e -> System.err.println("  - " + e));
                return 6;
            }
        } catch (Exception e) {
            System.err.println("error: self-validation read failed: " + e);
            return 6;
        }
        if (ctx.evidenceWriteFailure) {
            return 5;
        }
        if (ctx.firstFailure != null) {
            System.err.println("SOAK FAILED: phase=" + ctx.firstFailure.phase
                    + " reason=" + ctx.firstFailure.reason + " detail=" + ctx.firstFailure.detail);
            return 4;
        }
        return 0;
    }

    /**
     * Exercise every measured path and establish a bounded, evidenced steady-state baseline.
     * The release budget still starts at the first measured minute and remains 10%; calibration
     * only prevents JVM class-metadata allocator ramp-up from being mistaken for retained Kairo
     * state. A non-plateauing process or non-reclaimable lifecycle loader fails setup instead of
     * silently extending warm-up or relaxing the budget.
     */
    private static void warmUpMeasurementPaths(Context ctx) throws Exception {
        Failure disconnectFailure = runDisconnectRecovery(ctx, 0, false);
        if (disconnectFailure != null) {
            throw new AssertionFailure("warmup-" + disconnectFailure.phase,
                    disconnectFailure.expected, disconnectFailure.actual, disconnectFailure.detail);
        }
        ctx.warmupDisconnectCompleted = true;

        // Load the observation/Jackson path before minute 1 as well. The observation is not
        // recorded; it exists solely to make the subsequent baseline a stable-window sample.
        SoakObservation warmupObservation = captureSummary(ctx, new SoakBudgetChecker(BUDGET));
        ObjectNode warmupJson = MAPPER.createObjectNode();
        warmupJson.put("timestamp", warmupObservation.timestamp().toString());
        warmupJson.put("metaspaceUsedBytes", warmupObservation.metaspaceUsedBytes());
        warmupJson.put("loadedClassCount", warmupObservation.loadedClassCount());
        MAPPER.writeValueAsString(warmupJson);
        ctx.warmupResourceSampleCompleted = true;

        recordWarmupSample(ctx, 0);
        for (int batch = 1; batch <= WARMUP_MAX_LIFECYCLE_BATCHES; batch++) {
            Failure batchFailure = runBatch(ctx, -batch);
            if (batchFailure != null) {
                throw new AssertionFailure("warmup-" + batchFailure.phase,
                        batchFailure.expected, batchFailure.actual, batchFailure.detail);
            }
            ctx.warmupBatchesRun = batch;
            if (batch % WARMUP_SAMPLE_EVERY_BATCHES == 0) {
                recordWarmupSample(ctx, batch);
                if (batch >= WARMUP_MIN_LIFECYCLE_BATCHES && warmupPlateauEstablished(ctx)) {
                    ctx.warmupSteadyStateEstablished = true;
                    ctx.warmupLifecycleLoadersCreated = ctx.lifecycleLoadersCreated;
                    ctx.warmupLifecycleLoadersCollected = ctx.lifecycleLoadersCollected;
                    break;
                }
            }
        }
        ctx.warmupBatchCompleted = ctx.warmupBatchesRun > 0;
        if (!ctx.warmupSteadyStateEstablished) {
            throw new AssertionFailure("warmup-steady-state",
                    "metaspace growth <= " + WARMUP_MAX_WINDOW_METASPACE_GROWTH_PCT
                            + "% across bounded window and lifecycle loaders reclaimed",
                    "batches=" + ctx.warmupBatchesRun
                            + ",windowGrowthPct=" + ctx.warmupWindowGrowthPct
                            + ",created=" + ctx.lifecycleLoadersCreated
                            + ",collected=" + ctx.lifecycleLoadersCollected,
                    "JVM did not reach a reclaimable lifecycle steady state within "
                            + WARMUP_MAX_LIFECYCLE_BATCHES + " batches; refusing to establish a low-water baseline");
        }

        ctx.summaries = 0;
        ctx.disconnectDetails.clear();
        ctx.disconnectLastOutcome = null;
        ctx.behaviorDriftStartedAtSeconds = -1L;
        ctx.runtimeStateDriftStartedAtSeconds = -1L;
    }

    private static void requestFullGc() {
        try {
            System.gc();
        } catch (RuntimeException ignored) {
            // Best effort, identical to the per-summary retained-memory observation contract.
        }
    }

    private static void recordWarmupSample(Context ctx, int batches) {
        requestFullGc();
        drainCollectedLifecycleLoaders(ctx);
        long metaspace = METASPACE_POOL == null ? -1L : METASPACE_POOL.getUsage().getUsed();
        if (metaspace < 0) {
            throw new AssertionFailure("warmup-metaspace-observation", "supported Metaspace MXBean", "unsupported",
                    "cannot establish a fail-closed Metaspace baseline on this JVM");
        }
        WarmupSample sample = new WarmupSample(batches, metaspace, CLASSLOAD_MX.getLoadedClassCount(),
                ctx.lifecycleLoadersCreated, ctx.lifecycleLoadersCollected);
        ctx.warmupSamples.add(sample);
        if (ctx.warmupSamples.size() == 1) {
            ctx.warmupInitialMetaspaceBytes = metaspace;
        }
        ctx.warmupFinalMetaspaceBytes = metaspace;
        ctx.warmupLifecycleLoadersCreated = ctx.lifecycleLoadersCreated;
        ctx.warmupLifecycleLoadersCollected = ctx.lifecycleLoadersCollected;
    }

    private static boolean warmupPlateauEstablished(Context ctx) {
        int size = ctx.warmupSamples.size();
        if (size < WARMUP_PLATEAU_WINDOW_SAMPLES) {
            return false;
        }
        WarmupSample oldest = ctx.warmupSamples.get(size - WARMUP_PLATEAU_WINDOW_SAMPLES);
        WarmupSample newest = ctx.warmupSamples.get(size - 1);
        long growth = Math.max(0L, newest.metaspaceUsedBytes() - oldest.metaspaceUsedBytes());
        ctx.warmupWindowGrowthPct = oldest.metaspaceUsedBytes() <= 0 ? Double.POSITIVE_INFINITY
                : ((double) growth / oldest.metaspaceUsedBytes()) * 100.0;
        // HotSpot can retire the newest redefinition cohort only at the following class-unloading
        // safepoint. Exclude exactly that newest sampling cohort, but fail closed on every older
        // loader that has already crossed a full observation/GC boundary.
        WarmupSample previous = ctx.warmupSamples.get(size - 2);
        ctx.warmupEligibleLifecycleLoaders = previous.lifecycleLoadersCreated();
        ctx.warmupEligibleLoadersOutstanding = (int) ctx.lifecycleLoaderReferences.values().stream()
                .filter(ordinal -> ordinal <= ctx.warmupEligibleLifecycleLoaders)
                .count();
        ctx.warmupLatestCohortGraceLoaders = Math.max(0,
                newest.lifecycleLoadersCreated() - ctx.warmupEligibleLifecycleLoaders);
        return ctx.warmupWindowGrowthPct <= WARMUP_MAX_WINDOW_METASPACE_GROWTH_PCT
                && ctx.warmupEligibleLoadersOutstanding <= WARMUP_ALLOWED_OUTSTANDING_LOADERS;
    }

    private static void drainCollectedLifecycleLoaders(Context ctx) {
        Reference<? extends ClassLoader> ref;
        while ((ref = ctx.lifecycleLoaderQueue.poll()) != null) {
            if (ctx.lifecycleLoaderReferences.remove(ref) != null) {
                ctx.lifecycleLoadersCollected++;
            }
        }
        if (ctx.runtime != null) {
            ctx.runtime.classLoaderRepository().pollCollected();
        }
    }

    // -------------------------------------------------------- independent soak targets

    private static void setupContinuousTarget(Context ctx) throws Exception {
        ctx.continuousMethod = OrderService.class.getMethod("calculateScore", int.class);
        ctx.continuousClass = OrderService.class;
        ctx.continuousTarget = targetOf(ctx.continuousMethod, EnhancementLocation.METHOD_RETURN);
        ctx.continuousInstance = new OrderService();
    }

    private static void enhanceContinuous(Context ctx) {
        // Each (re-)application of the continuous rule is a distinct command: a unique
        // commandId / idempotencyKey per call avoids the platform's duplicate-command dedup
        // ("previous result returned") while the rule id "soak-cont" stays stable.
        ctx.continuousApplySeq++;
        MockRule cont = rule("soak-cont", ctx.continuousMethod, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.returnValue(77)");
        ApplyChainResult result = applyChain(ctx, "cmd-soak-cont-" + ctx.continuousApplySeq,
                "key-soak-cont-" + ctx.continuousApplySeq,
                ctx.continuousRevision, ctx.continuousRevision.value() + 1, ctx.continuousTarget,
                List.of(cont), ChainDesiredState.ACTIVE);
        ctx.continuousRevision = result.applied();
    }

    private static void verifyLifecycleBaseline(LifecycleTarget lifecycle, int expected) throws Exception {
        int v = invokeLifecycle(lifecycle);
        if (v != expected) {
            throw new AssertionFailure("baseline-behavior", String.valueOf(expected), String.valueOf(v),
                    "lifecycle target baseline behaviour mismatch");
        }
    }

    // -------------------------------------------------------- continuous burst

    private static Failure runBurst(Context ctx) {
        try {
            boolean drifted = false;
            for (int i = 0; i < BURST_SIZE; i++) {
                int v = ctx.continuousInstance.calculateScore(5);
                if (v != ctx.expectedPrimaryValue) {
                    drifted = true;
                }
                ctx.continuousInvocations++;
            }
            if (drifted) {
                if (ctx.behaviorDriftStartedAtSeconds < 0) {
                    ctx.behaviorDriftStartedAtSeconds = elapsedSeconds(ctx, ctx.clock);
                }
            } else {
                ctx.behaviorDriftStartedAtSeconds = -1L;
            }
            return null;
        } catch (OutOfMemoryError oom) {
            // Preserve OOME for the outer run boundary, which records oomEvidence=true and
            // finalState=ABORTED. Treating it as a business exception would falsify the gate.
            throw oom;
        } catch (Throwable t) {
            // An enhanced invocation throwing is a leaked business exception (§9.4) - fail now.
            if (ctx.behaviorDriftStartedAtSeconds < 0) {
                ctx.behaviorDriftStartedAtSeconds = elapsedSeconds(ctx, ctx.clock);
            }
            return new Failure("continuous-invoke", "execute", "leaked-business-exception",
                    "no-throw", t.getClass().getSimpleName(),
                    "enhanced invocation threw: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    ctx.clock.now(), elapsedSeconds(ctx, ctx.clock), false);
        }
    }

    // -------------------------------------------------------- 5-minute batch

    private static Failure runBatch(Context ctx) {
        return runBatch(ctx, ctx.batchesRun + 1);
    }

    private static Failure runBatch(Context ctx, int cycle) {
        try {
            LifecycleTarget lifecycle = newLifecycleTarget(ctx);
            verifyLifecycleBaseline(lifecycle, 10);
            MockRule a = rule("chain-a-" + cycle, lifecycle.method(), EnhancementLocation.METHOD_RETURN, 30,
                    "return mock.replaceReturnValue(ctx.result() + 1)");
            MockRule b = rule("chain-b-" + cycle, lifecycle.method(), EnhancementLocation.METHOD_RETURN, 20,
                    "return mock.replaceReturnValue(ctx.result() + 10)");
            MockRule c = rule("chain-c-" + cycle, lifecycle.method(), EnhancementLocation.METHOD_RETURN, 10,
                    "return mock.replaceReturnValue(ctx.result() + 100)");

            // Enhance chain [a,b,c].
            ApplyChainResult applied = applyChain(ctx, "cmd-batch-" + cycle + "-e", "key-batch-" + cycle + "-e",
                    RuleChainRevision.initial(), 1, lifecycle.target(),
                    List.of(a, b, c), ChainDesiredState.ACTIVE);
            RuleChainRevision lifecycleRevision = applied.applied();
            verify("batch-enhanced", 121, invokeLifecycle(lifecycle));
            verify("continuous-isolation-after-enhance", 77, ctx.continuousInstance.calculateScore(5));
            byte[] baselineBytes = retransformInputBytes(ctx, lifecycle.type());

            // Partial unload: drop b, keep a and c.
            ApplyChainResult partial = applyChain(ctx, "cmd-batch-" + cycle + "-p", "key-batch-" + cycle + "-p",
                    lifecycleRevision, lifecycleRevision.value() + 1, lifecycle.target(),
                    List.of(a, c), ChainDesiredState.ACTIVE);
            lifecycleRevision = partial.applied();
            verify("batch-partial-unload", 111, invokeLifecycle(lifecycle));
            verify("continuous-isolation-after-update", 77, ctx.continuousInstance.calculateScore(5));

            // Full unload.
            RetransformOutputCapture unloadCapture = new RetransformOutputCapture(lifecycle.type());
            ctx.instrumentation.addTransformer(unloadCapture, true);
            try {
                unloadChain(ctx, "cmd-batch-" + cycle + "-x", "key-batch-" + cycle + "-x",
                        lifecycle.target(), lifecycleRevision);
            } finally {
                ctx.instrumentation.removeTransformer(unloadCapture);
            }
            verify("batch-full-unload", 10, invokeLifecycle(lifecycle));
            verify("continuous-isolation-after-unload", 77, ctx.continuousInstance.calculateScore(5));
            boolean lifecycleEmpty = ctx.runtime.chainApplier().snapshot(lifecycle.target()).isEmpty();
            boolean continuousActive = !ctx.runtime.chainApplier().snapshot(ctx.continuousTarget).isEmpty();
            if (!lifecycleEmpty || !continuousActive) {
                throw new AssertionFailure("batch-lifecycle-rules-cleared",
                        "lifecycleEmpty=true,continuousActive=true",
                        "lifecycleEmpty=" + lifecycleEmpty + ",continuousActive=" + continuousActive,
                        "lifecycle rules remained registered after full unload in batch " + cycle);
            }
            byte[] afterBytes = unloadCapture.bytes();
            if (afterBytes == null) {
                throw new AssertionFailure("precise-unload-capture", "captured bytes", "null",
                        "full-unload retransform did not expose final JVM bytes in batch " + cycle);
            }
            boolean normIdentical = normalizedIdentical(ctx, lifecycle.type(), baselineBytes, afterBytes);
            String baselineHash = BytecodeHash.sha256Hex(baselineBytes);
            String afterHash = BytecodeHash.sha256Hex(afterBytes);
            boolean hashRestored = baselineHash.equals(afterHash) && normIdentical;
            if (!hashRestored) {
                throw new AssertionFailure("precise-unload",
                        "afterUnloadHash==baselineHash && normalizedIdentical",
                        "baseline=" + baselineHash + " after=" + afterHash
                                + " normalizedIdentical=" + normIdentical,
                        "bytecode hash not restored to baseline after full unload in batch " + cycle
                                + " (§9.4 inability to perform precise unload)");
            }

            // The continuously hot class was never retransformed by this lifecycle batch.
            verify("batch-continuous-still-enhanced", 77, ctx.continuousInstance.calculateScore(5));
            ctx.expectedPrimaryValue = 77;
            return null;
        } catch (AssertionFailure af) {
            return new Failure("batch-" + cycle, af.phase, "lifecycle-failure",
                    af.expected, af.actual, af.detail, ctx.clock.now(), elapsedSeconds(ctx, ctx.clock), false);
        } catch (OutOfMemoryError oom) {
            // OOME has dedicated evidence/final-state semantics at the outer run boundary.
            throw oom;
        } catch (Throwable t) {
            return new Failure("batch-" + cycle, "execute", "lifecycle-failure", "",
                    t.getClass().getSimpleName(), t.getClass().getSimpleName() + ": " + t.getMessage(),
                    ctx.clock.now(), elapsedSeconds(ctx, ctx.clock), false);
        }
    }

    private static LifecycleTarget newLifecycleTarget(Context ctx) throws Exception {
        LifecycleClassLoader loader = new LifecycleClassLoader(SoakHarness.class.getClassLoader());
        int ordinal = ++ctx.lifecycleLoadersCreated;
        WeakReference<ClassLoader> loaderReference = new WeakReference<>(loader, ctx.lifecycleLoaderQueue);
        ctx.lifecycleLoaderReferences.put(loaderReference, ordinal);
        Class<?> type = Class.forName(LIFECYCLE_TARGET_CLASS, true, loader);
        Method method = type.getMethod("calculateScore", int.class);
        Object instance = type.getDeclaredConstructor().newInstance();
        EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
        return new LifecycleTarget(type, method, instance, target);
    }

    private static byte[] retransformInputBytes(Context ctx, Class<?> type) {
        ClassIdentity identity = ClassIdentities.of(type);
        TransformationRevision revision = ctx.runtime.transformationJournal().currentRevision(identity);
        BytecodeSnapshotKey key = new BytecodeSnapshotKey(identity, revision, BytecodeSnapshotKind.INPUT);
        return ctx.runtime.snapshotRepository().bytes(key).orElseThrow(() ->
                new AssertionFailure("baseline-capture", "first-transform INPUT snapshot", "missing",
                        "Kairo did not retain the JVM input bytes for " + identity));
    }

    private static int invokeLifecycle(LifecycleTarget lifecycle) throws Exception {
        return ((Number) lifecycle.method().invoke(lifecycle.instance(), 5)).intValue();
    }

    // -------------------------------------------------------- 30-minute disconnect/recovery

    private static Failure runDisconnectRecovery(Context ctx) {
        return runDisconnectRecovery(ctx, ctx.disconnectsRun + 1, true);
    }

    private static Failure runDisconnectRecovery(Context ctx, int cycle, boolean countAsMeasured) {
        try {
            // Precondition: the live JVM is currently enhanced.
            verify("disconnect-precondition", 77, ctx.continuousInstance.calculateScore(5));
            // Disconnect only the Platform command channel. The AgentRuntime and enhanced JVM
            // must stay alive; turning this into a runtime restart would test the wrong lifecycle.
            ctx.platformLink.disconnect();
            verify("disconnect-jvm-stays-enhanced", 77, ctx.continuousInstance.calculateScore(5));

            // Reconnect through a fresh real poller and read the actual state through the same
            // REFRESH_RUNTIME_STATE command path used by the Platform protocol.
            ctx.platformLink.reconnect();
            AgentRuntimeSnapshot snapshot = ctx.platformLink.refreshRuntimeState();
            if (!ctx.processStartId.equals(snapshot.processStartId())) {
                throw new AssertionFailure("recovery-process-identity", ctx.processStartId,
                        snapshot.processStartId(), "reconnect changed processStartId in cycle " + cycle);
            }
            if (snapshot.rules().isEmpty() || snapshot.chains().isEmpty()) {
                throw new AssertionFailure("recovery-actual-state", "applied chain visible",
                        "rules=" + snapshot.rules().size() + ",chains=" + snapshot.chains().size(),
                        "REFRESH_RUNTIME_STATE lost the applied chain in cycle " + cycle);
            }
            verify("recovery-jvm-still-enhanced", 77, ctx.continuousInstance.calculateScore(5));
            if (countAsMeasured) {
                ctx.disconnectsRun++;
            }
            ctx.disconnectDetails.add("cycle " + cycle + ": RECOVERED processStartId="
                    + snapshot.processStartId() + " rules=" + snapshot.rules().size()
                    + " chains=" + snapshot.chains().size());
            ctx.disconnectLastOutcome = "RECOVERED";
            return null;
        } catch (AssertionFailure af) {
            ctx.disconnectDetails.add("cycle " + cycle + ": FAILED: " + af.detail);
            ctx.disconnectLastOutcome = "FAILED";
            return new Failure("disconnect-" + cycle, af.phase, "disconnect-recovery",
                    af.expected, af.actual, af.detail, ctx.clock.now(), elapsedSeconds(ctx, ctx.clock), false);
        } catch (OutOfMemoryError oom) {
            // OOME has dedicated evidence/final-state semantics at the outer run boundary.
            throw oom;
        } catch (Throwable t) {
            ctx.disconnectDetails.add("cycle " + cycle + ": FAILED: " + t.getClass().getSimpleName());
            ctx.disconnectLastOutcome = "FAILED";
            return new Failure("disconnect-" + cycle, "execute", "disconnect-recovery", "",
                    t.getClass().getSimpleName(), t.getClass().getSimpleName() + ": " + t.getMessage(),
                    ctx.clock.now(), elapsedSeconds(ctx, ctx.clock), false);
        }
    }

    // -------------------------------------------------------- summary / observation

    private static SoakObservation captureSummary(Context ctx, SoakBudgetChecker checker) {
        ctx.summaries++;
        int minuteIndex = ctx.summaries;
        long elapsedSeconds = elapsedSeconds(ctx, ctx.clock);
        // One GC so heap reflects retained (reclaimable) memory, not transient allocation noise.
        // This is the M2-C observation concept; it is not a sleep and does not pause the soak
        // meaningfully (~one GC per minute).
        requestFullGc();
        drainCollectedLifecycleLoaders(ctx);
        long heap = MEMORY_MX.getHeapMemoryUsage().getUsed();
        long metaspace = METASPACE_POOL == null ? -1L : METASPACE_POOL.getUsage().getUsed();
        long fd = UNIX_OS == null ? -1L : UNIX_OS.getOpenFileDescriptorCount();
        int threadCount = THREAD_MX.getThreadCount();
        int loadedClassCount = CLASSLOAD_MX.getLoadedClassCount();
        AgentRuntimeSnapshot runtimeSnapshot = ctx.runtime.snapshotRuntimeState("soak-agent", ctx.processStartId);
        int rules = runtimeSnapshot.rules().size();
        boolean runtimeStateDrift = rules != 1 || runtimeSnapshot.chains().size() != 1;
        if (runtimeStateDrift) {
            if (ctx.runtimeStateDriftStartedAtSeconds < 0) {
                ctx.runtimeStateDriftStartedAtSeconds = elapsedSeconds;
            }
        } else {
            ctx.runtimeStateDriftStartedAtSeconds = -1L;
        }
        int snapshot = ctx.runtime.snapshotRepository().size();
        int journal = ctx.runtime.transformationJournal().recordCount();
        int instrType = ctx.runtime.instrumentationRegistry().typeCount();
        int instrMethod = ctx.runtime.instrumentationRegistry().methodCount();
        long driftStartedAtSeconds = currentDriftStartedAtSeconds(ctx);
        boolean driftActive = driftStartedAtSeconds >= 0;
        long driftPersistentSeconds = driftActive
                ? Math.max(0L, elapsedSeconds - driftStartedAtSeconds) : 0L;
        SoakObservation obs = new SoakObservation(
                minuteIndex,
                ctx.clock.now(),
                elapsedSeconds,
                heap,
                metaspace,
                threadCount,
                fd,
                loadedClassCount,
                rules,
                snapshot,
                journal,
                instrType,
                instrMethod,
                ctx.continuousInvocations,
                ctx.batchesRun,
                ctx.disconnectsRun,
                driftActive,
                driftPersistentSeconds,
                false,
                false,
                false,
                false,
                false);
        // Compute the per-window breach flags vs the established baseline (the first summary IS
        // the baseline, so its flags stay false). The checker is the single source of the breach
        // computation; this only mirrors its result into the evidence record.
        SoakObservation baseline = checker.baseline();
        if (baseline != null) {
            SoakBudgetChecker.Breaches breaches = SoakBudgetChecker.breaches(obs, baseline, BUDGET);
            obs = obs.withBreaches(breaches.heap(), breaches.metaspace(), breaches.thread(), breaches.fd());
        }
        return obs;
    }

    private static void recordObservation(Context ctx, SoakObservation obs) {
        ctx.observations.add(obs);
        appendTimeSeries(ctx, obs);
    }

    private static void appendTimeSeries(Context ctx, SoakObservation obs) {
        openTimeSeries(ctx);
        try {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("minuteIndex", obs.minuteIndex());
            n.put("timestamp", obs.timestamp().toString());
            n.put("elapsedSeconds", obs.elapsedSeconds());
            n.put("heapUsedBytes", obs.heapUsedBytes());
            n.put("metaspaceUsedBytes", obs.metaspaceUsedBytes());
            n.put("threadCount", obs.threadCount());
            n.put("openFdCount", obs.openFdCount());
            n.put("loadedClassCount", obs.loadedClassCount());
            n.put("publishedRuleCount", obs.publishedRuleCount());
            n.put("snapshotCount", obs.snapshotCount());
            n.put("journalRecordCount", obs.journalRecordCount());
            n.put("instrumentationTypeCount", obs.instrumentationTypeCount());
            n.put("instrumentationMethodCount", obs.instrumentationMethodCount());
            n.put("continuousInvocations", obs.continuousInvocations());
            n.put("batchesRun", obs.batchesRun());
            n.put("disconnectsRun", obs.disconnectsRun());
            n.put("driftDetected", obs.driftDetected());
            n.put("driftPersistentSeconds", obs.driftPersistentSeconds());
            n.put("heapBreach", obs.heapBreach());
            n.put("metaspaceBreach", obs.metaspaceBreach());
            n.put("threadBreach", obs.threadBreach());
            n.put("fdBreach", obs.fdBreach());
            n.put("sustainedBreach", obs.sustainedBreach());
            ctx.timeSeriesWriter.write(n.toString());
            ctx.timeSeriesWriter.write("\n");
            ctx.timeSeriesWriter.flush();
        } catch (Exception e) {
            throw new EvidenceWriteFailure("failed to append time-series entry: " + e, e);
        }
    }

    private static void openTimeSeries(Context ctx) {
        if (ctx.timeSeriesWriter == null) {
            try {
                Files.createDirectories(Path.of(ctx.opts.output()));
                ctx.timeSeriesPath = Path.of(ctx.opts.output(), "soak-timeseries.jsonl");
                ctx.timeSeriesWriter = Files.newBufferedWriter(ctx.timeSeriesPath, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // A failure to open the raw time-series file is a result-write error.
                throw new EvidenceWriteFailure("failed to open raw time-series file: " + e, e);
            }
        }
    }

    // -------------------------------------------------------- chain / rule helpers (mirror M2-B)

    private static ApplyChainResult applyChain(Context ctx, String commandId, String idempotencyKey,
                                               RuleChainRevision expected, long desiredRevision,
                                               EnhancementTarget target, List<MockRule> rules,
                                               ChainDesiredState state) {
        ApplyChainResult result = ctx.runtime.applyRuleChain(
                chainRequest(commandId, idempotencyKey, expected, desiredRevision, target, rules, state));
        if (result.status() != ApplyChainStatus.APPLIED) {
            throw new AssertionFailure("apply-chain", "APPLIED", result.status().name(),
                    "commandId=" + commandId + " message=" + result.message());
        }
        return result;
    }

    private static void unloadChain(Context ctx, String commandId, String idempotencyKey,
                                    EnhancementTarget target, RuleChainRevision currentRevision) {
        RuleChainSpec spec = RuleChainSpec.builder()
                .chainId(target.method().className() + "#" + target.method().methodName())
                .revision(currentRevision.value() + 1)
                .target(target)
                .entries(List.of())
                .desiredState(ChainDesiredState.EMPTY)
                .build();
        ApplyChainRequest request = ApplyChainRequest.builder()
                .commandId(commandId)
                .idempotencyKey(idempotencyKey)
                .expected(currentRevision)
                .desired(spec)
                .rules(List.of())
                .target(target)
                .deadlineMillis(30_000L)
                .build();
        ApplyChainResult result = ctx.runtime.applyRuleChain(request);
        if (result.status() != ApplyChainStatus.APPLIED && result.status() != ApplyChainStatus.NO_OP) {
            throw new AssertionFailure("unload-chain", "APPLIED", result.status().name(),
                    "commandId=" + commandId + " message=" + result.message());
        }
    }

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

    private static boolean normalizedIdentical(Context ctx, Class<?> targetClass,
                                               byte[] baselineBytes, byte[] afterBytes) {
        BytecodeDiffResult diff = ctx.runtime.diffService().diff(
                ClassIdentities.of(targetClass),
                baselineBytes, TransformationRevision.INITIAL, BytecodeSnapshotKind.INPUT,
                afterBytes, TransformationRevision.INITIAL, BytecodeSnapshotKind.APPLIED);
        return diff.identical();
    }

    // -------------------------------------------------------- result building

    private static ObjectNode buildResult(Context ctx, Instant startedAt, SoakClock clock,
                                         Duration completed, boolean completedFull,
                                         String finalState, String overall) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", "1.0");
        root.put("generatedAt", clock.now().toString());
        root.put("startedAt", startedAt.toString());
        root.put("endedAt", clock.now().toString());
        root.put("buildId", ctx.opts.buildId());
        root.put("command", ctx.opts.command());
        root.put("mode", ctx.opts.mode());
        root.put("workingTreeDirty", ctx.opts.workingTreeDirty());
        ArrayNode jvmArgs = root.putArray("jvmArgs");
        for (String a : ctx.opts.jvmArgs().split("\\s+")) {
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

        ObjectNode dur = root.putObject("duration");
        dur.put("requested", ctx.opts.duration().toString());
        dur.put("requestedSeconds", ctx.opts.duration().toSeconds());
        dur.put("completedSeconds", completed.toMillis() / 1000.0);
        dur.put("completedIso", completed.toString());
        dur.put("completed", completedFull);

        ObjectNode cadence = root.putObject("cadence");
        cadence.put("summaryInterval", CADENCE.summaryInterval().toString());
        cadence.put("batchInterval", CADENCE.batchInterval().toString());
        cadence.put("disconnectInterval", CADENCE.disconnectInterval().toString());

        ObjectNode topology = root.putObject("workloadTopology");
        topology.put("continuousTargetClass", ctx.continuousClass.getName());
        topology.put("lifecycleTargetClass", LIFECYCLE_TARGET_CLASS);
        topology.put("classSeparated", !ctx.continuousClass.getName().equals(LIFECYCLE_TARGET_CLASS));
        topology.put("lifecycleClassLoaderPerBatch", true);
        topology.put("continuousTargetParticipatesInLifecycleBatches", false);
        topology.put("lifecycleTargetReceivesContinuousTraffic", false);

        ObjectNode cycles = root.putObject("cycles");
        cycles.put("continuousInvocations", ctx.continuousInvocations);
        cycles.put("continuousTargetEnhanceApplications", ctx.continuousApplySeq);
        cycles.put("enhanceUnloadBatches", ctx.batchesRun);
        cycles.put("disconnectRecoveries", ctx.disconnectsRun);
        cycles.put("summaries", ctx.summaries);
        cycles.put("failedBatches", ctx.failedBatches);

        ObjectNode warmup = root.putObject("measurementWarmup");
        warmup.put("strategy", "bounded-adaptive-metaspace-plateau");
        warmup.put("enhanceUnloadBatch", ctx.warmupBatchCompleted);
        warmup.put("disconnectRecovery", ctx.warmupDisconnectCompleted);
        warmup.put("resourceSample", ctx.warmupResourceSampleCompleted);
        warmup.put("excludedFromDurationAndCycles", true);
        warmup.put("minimumLifecycleBatches", WARMUP_MIN_LIFECYCLE_BATCHES);
        warmup.put("maximumLifecycleBatches", WARMUP_MAX_LIFECYCLE_BATCHES);
        warmup.put("sampleEveryBatches", WARMUP_SAMPLE_EVERY_BATCHES);
        warmup.put("plateauWindowBatches",
                (WARMUP_PLATEAU_WINDOW_SAMPLES - 1) * WARMUP_SAMPLE_EVERY_BATCHES);
        warmup.put("maxWindowMetaspaceGrowthPct", WARMUP_MAX_WINDOW_METASPACE_GROWTH_PCT);
        warmup.put("batchesRun", ctx.warmupBatchesRun);
        warmup.put("steadyStateEstablished", ctx.warmupSteadyStateEstablished);
        warmup.put("initialMetaspaceUsedBytes", ctx.warmupInitialMetaspaceBytes);
        warmup.put("finalMetaspaceUsedBytes", ctx.warmupFinalMetaspaceBytes);
        warmup.put("observedWindowMetaspaceGrowthPct", ctx.warmupWindowGrowthPct);
        warmup.put("lifecycleLoadersCreated", ctx.warmupLifecycleLoadersCreated);
        warmup.put("lifecycleLoadersCollected", ctx.warmupLifecycleLoadersCollected);
        warmup.put("lifecycleLoadersOutstanding",
                Math.max(0, ctx.warmupLifecycleLoadersCreated - ctx.warmupLifecycleLoadersCollected));
        warmup.put("eligibleLifecycleLoaders", ctx.warmupEligibleLifecycleLoaders);
        warmup.put("eligibleLifecycleLoadersOutstanding", ctx.warmupEligibleLoadersOutstanding);
        warmup.put("latestCohortGraceLoaders", ctx.warmupLatestCohortGraceLoaders);
        warmup.put("allowedOutstandingLifecycleLoaders", WARMUP_ALLOWED_OUTSTANDING_LOADERS);
        ArrayNode warmupSamples = warmup.putArray("samples");
        for (WarmupSample sample : ctx.warmupSamples) {
            ObjectNode node = warmupSamples.addObject();
            node.put("lifecycleBatches", sample.lifecycleBatches());
            node.put("metaspaceUsedBytes", sample.metaspaceUsedBytes());
            node.put("loadedClassCount", sample.loadedClassCount());
            node.put("lifecycleLoadersCreated", sample.lifecycleLoadersCreated());
            node.put("lifecycleLoadersCollected", sample.lifecycleLoadersCollected());
        }

        ObjectNode budgets = root.putObject("budgets");
        budgets.put("maxHeapGrowthPct", BUDGET.maxHeapGrowthPct());
        budgets.put("maxMetaspaceGrowthPct", BUDGET.maxMetaspaceGrowthPct());
        budgets.put("maxThreadDelta", BUDGET.maxThreadDelta());
        budgets.put("maxFdDelta", BUDGET.maxFdDelta());
        budgets.put("driftThresholdSeconds", BUDGET.driftThresholdSeconds());
        budgets.put("sustainedBreachWindowSeconds", BUDGET.sustainedBreachWindowSeconds());

        ObjectNode ts = root.putObject("timeSeries");
        ts.put("rawPath", rawPathString(ctx));
        ts.put("format", "jsonl");
        ts.put("count", ctx.observations.size());
        ts.put("summaryIntervalSeconds", CADENCE.summaryInterval().toSeconds());

        ArrayNode obsNode = root.putArray("observations");
        for (SoakObservation o : ctx.observations) {
            ObjectNode on = obsNode.addObject();
            on.put("minuteIndex", o.minuteIndex());
            on.put("timestamp", o.timestamp().toString());
            on.put("elapsedSeconds", o.elapsedSeconds());
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
            on.put("continuousInvocations", o.continuousInvocations());
            on.put("batchesRun", o.batchesRun());
            on.put("disconnectsRun", o.disconnectsRun());
            on.put("driftDetected", o.driftDetected());
            on.put("driftPersistentSeconds", o.driftPersistentSeconds());
            on.put("heapBreach", o.heapBreach());
            on.put("metaspaceBreach", o.metaspaceBreach());
            on.put("threadBreach", o.threadBreach());
            on.put("fdBreach", o.fdBreach());
            on.put("sustainedBreach", o.sustainedBreach());
        }

        ObjectNode dr = root.putObject("disconnectRecovery");
        dr.put("count", ctx.disconnectsRun);
        dr.put("lastOutcome", ctx.disconnectLastOutcome == null ? "NONE" : ctx.disconnectLastOutcome);
        ArrayNode details = dr.putArray("details");
        ctx.disconnectDetails.forEach(details::add);

        root.put("oomEvidence", ctx.oomEvidence);

        if (ctx.firstFailure != null) {
            ObjectNode ff = root.putObject("firstFailure");
            ff.put("scenario", ctx.firstFailure.scenario);
            ff.put("phase", ctx.firstFailure.phase);
            ff.put("reason", ctx.firstFailure.reason);
            ff.put("expected", ctx.firstFailure.expected);
            ff.put("actual", ctx.firstFailure.actual);
            ff.put("detail", ctx.firstFailure.detail);
            ff.put("failureTime", ctx.firstFailure.failureTime.toString());
            ff.put("failureSeconds", ctx.firstFailure.failureSeconds);
        } else {
            root.putNull("firstFailure");
        }
        root.put("finalState", finalState);
        root.put("overall", overall);
        return root;
    }

    private static String rawPathString(Context ctx) {
        if (ctx.timeSeriesPath == null) {
            return "";
        }
        // Prefer a repo-root-relative path when the output dir is under the working directory,
        // so the recorded raw time-series path is an in-repo/local path (§9.4).
        Path out = ctx.timeSeriesPath.toAbsolutePath().normalize();
        String userDir = System.getProperty("user.dir");
        Path root = Path.of(userDir).toAbsolutePath().normalize();
        if (out.startsWith(root)) {
            return root.relativize(out).toString();
        }
        return out.toString();
    }

    private static int writeResult(Context ctx, ObjectNode root) {
        boolean closeFailed = false;
        try {
            if (ctx.timeSeriesWriter != null) {
                ctx.timeSeriesWriter.flush();
                ctx.timeSeriesWriter.close();
                ctx.timeSeriesWriter = null;
            }
        } catch (Exception e) {
            closeFailed = true;
            System.err.println("error: failed to flush/close soak-timeseries.jsonl: " + e);
        }
        try {
            Path dir = Path.of(ctx.opts.output());
            Files.createDirectories(dir);
            Path file = dir.resolve("soak-result.json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
            return closeFailed ? 5 : 0;
        } catch (Exception e) {
            System.err.println("error: failed to write soak-result.json: " + e);
            return 5;
        }
    }

    // -------------------------------------------------------- helpers

    private static long elapsedSeconds(Context ctx, SoakClock clock) {
        return clock.elapsed().getSeconds();
    }

    private static long currentDriftStartedAtSeconds(Context ctx) {
        if (ctx.behaviorDriftStartedAtSeconds < 0) {
            return ctx.runtimeStateDriftStartedAtSeconds;
        }
        if (ctx.runtimeStateDriftStartedAtSeconds < 0) {
            return ctx.behaviorDriftStartedAtSeconds;
        }
        return Math.min(ctx.behaviorDriftStartedAtSeconds, ctx.runtimeStateDriftStartedAtSeconds);
    }

    private static Failure toFailure(String scenario, SoakBudgetChecker.Failure bf, SoakClock clock) {
        // The checker's phase ("sustained-resource-breach" / "persistent-state-drift") is also
        // the failure reason category; the harness scenario records where in the soak it fired.
        return new Failure(scenario, bf.phase(), bf.phase(),
                bf.expected(), bf.actual(), bf.detail(),
                clock.now(), bf.failureSeconds(), false);
    }

    private static Failure capture(Context ctx, String phase, String reason, Throwable t, SoakClock clock) {
        return new Failure(phase, "execute", reason, "",
                t.getClass().getSimpleName(),
                t.getClass().getSimpleName() + ": " + t.getMessage(),
                clock.now(), elapsedSeconds(ctx, clock), false);
    }

    private static void verify(String phase, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionFailure(phase, String.valueOf(expected), String.valueOf(actual),
                    "behaviour mismatch");
        }
    }

    private static MemoryPoolMXBean findMetaspacePool() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.NON_HEAP && pool.getName().contains("Metaspace")) {
                return pool;
            }
        }
        return null;
    }

    private static UnixOperatingSystemMXBean findUnixOs() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        return os instanceof UnixOperatingSystemMXBean unix ? unix : null;
    }

    private static void printUsage() {
        System.out.println("""
                Usage: SoakHarness --duration <ISO-8601> --output <dir> --build-id <40-hex>
                              --command <text> --jvm-args <args> --mode <pr|dev>
                              --working-tree-dirty <true|false> [--help]

                Runs the M2-D long-running stability soak (§9.4): per-minute time-series summary,
                continuous real enhanced-target invocations, a 5-minute enhance/update/partial-unload/
                full-unload batch, and a 30-minute Agent/Platform disconnect/recovery. RC uses
                --duration PT2H; RELEASE uses --duration P7D. Writes <output>/soak-result.json.
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

    private static final class EvidenceWriteFailure extends RuntimeException {
        EvidenceWriteFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Failure(String scenario, String phase, String reason, String expected, String actual,
                           String detail, Instant failureTime, long failureSeconds,
                           boolean oomEvidence) {
    }

    /** Mutable run context so the loop helpers stay stateless-looking. */
    private static final class Context {
        SoakArgumentParser.Options opts;
        SoakClock clock;
        Instrumentation instrumentation;
        AgentRuntime runtime;
        SoakPlatformLink platformLink;
        String processStartId;
        Method continuousMethod;
        Class<?> continuousClass;
        EnhancementTarget continuousTarget;
        OrderService continuousInstance;
        RuleChainRevision continuousRevision = RuleChainRevision.initial();
        int continuousApplySeq;
        int expectedPrimaryValue = 10;
        long continuousInvocations;
        int batchesRun;
        int failedBatches;
        int disconnectsRun;
        int summaries;
        long behaviorDriftStartedAtSeconds = -1L;
        long runtimeStateDriftStartedAtSeconds = -1L;
        boolean oomEvidence;
        boolean evidenceWriteFailure;
        boolean warmupBatchCompleted;
        boolean warmupDisconnectCompleted;
        boolean warmupResourceSampleCompleted;
        int warmupBatchesRun;
        boolean warmupSteadyStateEstablished;
        long warmupInitialMetaspaceBytes = -1L;
        long warmupFinalMetaspaceBytes = -1L;
        double warmupWindowGrowthPct = -1.0;
        int lifecycleLoadersCreated;
        int lifecycleLoadersCollected;
        int warmupLifecycleLoadersCreated;
        int warmupLifecycleLoadersCollected;
        int warmupEligibleLifecycleLoaders;
        int warmupEligibleLoadersOutstanding;
        int warmupLatestCohortGraceLoaders;
        ReferenceQueue<ClassLoader> lifecycleLoaderQueue = new ReferenceQueue<>();
        Map<WeakReference<ClassLoader>, Integer> lifecycleLoaderReferences = new LinkedHashMap<>();
        List<WarmupSample> warmupSamples = new ArrayList<>();
        Failure firstFailure;
        List<SoakObservation> observations = new ArrayList<>();
        List<String> disconnectDetails = new ArrayList<>();
        String disconnectLastOutcome;
        java.io.BufferedWriter timeSeriesWriter;
        Path timeSeriesPath;
    }

    private static final String LIFECYCLE_TARGET_CLASS = "com.example.demo.SoakLifecycleTarget";
    private static final String LIFECYCLE_TARGET_RESOURCE = "com/example/demo/SoakLifecycleTarget.class";

    private record LifecycleTarget(Class<?> type, Method method, Object instance,
                                   EnhancementTarget target) {
    }

    private record WarmupSample(int lifecycleBatches, long metaspaceUsedBytes, int loadedClassCount,
                                int lifecycleLoadersCreated, int lifecycleLoadersCollected) {
    }

    /** Captures the bytes produced by all earlier transformers during the physical unload itself. */
    private static final class RetransformOutputCapture implements ClassFileTransformer {
        private final Class<?> target;
        private volatile byte[] bytes;

        RetransformOutputCapture(Class<?> target) {
            this.target = target;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (classBeingRedefined == target) {
                bytes = classfileBuffer.clone();
            }
            return null;
        }

        byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }
    }

    /** Child-first only for the lifecycle fixture; all API/agent types remain parent-owned. */
    private static final class LifecycleClassLoader extends ClassLoader {
        LifecycleClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!LIFECYCLE_TARGET_CLASS.equals(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (java.io.InputStream in = getParent().getResourceAsStream(LIFECYCLE_TARGET_RESOURCE)) {
                        if (in == null) {
                            throw new ClassNotFoundException("missing " + LIFECYCLE_TARGET_RESOURCE);
                        }
                        byte[] bytes = in.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
