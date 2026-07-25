package com.example.kairo.agent.core;

import com.example.kairo.agent.core.bytecode.BytecodeCaptureService;
import com.example.kairo.agent.core.bytecode.BytecodeHash;
import com.example.kairo.agent.core.bytecode.BytecodeSnapshotRepository;
import com.example.kairo.agent.core.bytecode.ClassIdentities;
import com.example.kairo.agent.core.bytecode.DecompilerService;
import com.example.kairo.agent.core.bytecode.TransformationJournal;
import com.example.kairo.agent.core.bytecode.TransformationPreviewService;
import com.example.kairo.agent.core.bytecode.diff.BytecodeDiffService;
import com.example.kairo.agent.core.script.AgentScriptCompilerFactory;
import com.example.kairo.agent.core.script.ScriptSessionHost;
import com.example.kairo.agent.core.script.ScriptSessionLimits;
import com.example.kairo.agent.core.script.ScriptSessionManager;
import com.example.kairo.agent.core.script.ScriptSessionTarget;
import com.example.kairo.api.CallSiteIdentity;
import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.ClassSelector;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.EnhancementTarget;
import com.example.kairo.api.InvokeOpcode;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.SupportLevel;
import com.example.kairo.api.TargetMatchResult;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.ClassMetadata;
import com.example.kairo.bridge.KairoBridge;
import com.example.kairo.core.AgentBridgeDispatcher;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.CompiledRule;
import com.example.kairo.core.MethodDescriptor;
import com.example.kairo.core.MethodKey;
import com.example.kairo.core.RuleDispatcher;
import com.example.kairo.core.RuleDispatcherConfig;
import com.example.kairo.core.RulePublisher;
import com.example.kairo.core.RuleRegistry;
import com.example.kairo.core.RuleSet;
import com.example.kairo.groovy.CompiledMockScript;
import com.example.kairo.object.DefaultRuntimeObjectFactory;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class AgentRuntime implements AutoCloseable, ScriptSessionHost {

    private final Instrumentation instrumentation;
    private final DefaultInstrumentationRegistry instrumentationRegistry;
    private final RuleRegistry ruleRegistry;
    private final AgentScriptCompilerFactory scriptCompilerFactory;
    private final RulePublisher rulePublisher;
    private final RuleDispatcher ruleDispatcher;
    private final RuleDispatcherConfig dispatcherConfig;
    private final RecordingInvocationObserver recordingObserver;
    private final ByteBuddyTransformerManager transformerManager;
    private final LoadedClassRepository loadedClassRepository;
    private final RuntimeEventBuffer eventBuffer;
    private final BytecodeSnapshotRepository snapshotRepository;
    private final TransformationJournal transformationJournal;
    private final TransformationPreviewService previewService;
    private final BytecodeCaptureService captureService;
    private final BytecodeDiffService diffService;
    private final DecompilerService decompilerService;
    private final ScriptSessionManager scriptSessionManager;
    private final RuleChainApplier chainApplier;
    private final ClassLoaderRepository classLoaderRepository;
    private final ProxyTargetAnalyzer proxyAnalyzer = new DefaultProxyTargetAnalyzer();
    private final ModuleDiagnostics moduleDiagnostics;
    private final SyntheticBridgePolicy syntheticBridgePolicy = new SyntheticBridgePolicy();
    private final PendingEnhancementRegistry pendingRegistry = new PendingEnhancementRegistry();
    private final HotUpdateReconciler hotUpdateReconciler = new HotUpdateReconciler();
    private final ConcurrentHashMap<String, PublishedRule> publishedRules = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EnhancementTarget> targetByRuleId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EnhancementTarget> targetByRecordingId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RecordingRegistration> activeRecordings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> degradedClasses = new ConcurrentHashMap<>();
    // V1.5 §4.4: classes whose bytecode hash changed since the last successful apply (external
    // redefine/hot-swap), detected by the redefine listener and surfaced as TARGET_DRIFTED on
    // resolveTarget and DISCOVER_TARGETS. Mirrors HotUpdateReconciler's drift verdict so the read
    // side can report driftStatus without re-reading bytes on every query.
    private final ConcurrentHashMap<String, String> driftedClasses = new ConcurrentHashMap<>();
    private final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.STARTING);
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kairo-rule-cleanup");
        thread.setDaemon(true);
        return thread;
    });
    private final long startTimeMillis = System.currentTimeMillis();
    private volatile boolean globallyEnabled = true;
    private volatile String loadMode = "unknown";

    /**
     * V1.7 M1-F &sect;8.6 item 4: set by a local emergency op through the loopback Agent API
     * ({@code disable-all}/{@code reset-all}/{@code reset-class}) so the Platform, once it reconnects
     * and refreshes the runtime snapshot, can recognize the operator's manual recovery and refrain
     * from blindly re-applying desired state that would undo it. Cleared by {@code enable-all} (the
     * explicit resume), so reconciliation resumes only after the operator has reviewed and resumed.
     */
    private volatile boolean emergencyHeld = false;
    private final CallSiteScanner callSiteScanner = new CallSiteScanner();

    /**
     * V1.7 M1-C &sect;8.3: the smallest explicit synchronization seam that coordinates a consistent
     * runtime-state snapshot read with the apply/unload/reset/disable mutation paths. The snapshot
     * takes the read lock; every path that mutates the snapshotted state (published rules, chains,
     * degraded classes, the global enabled flag) takes the write lock. It is a seam, not a redesign:
     * the enhancement engine, the dispatcher and the business hot path are untouched (the dispatcher
     * reads the registry directly and never participates in this lock).
     */
    private final ReentrantReadWriteLock snapshotLock = new ReentrantReadWriteLock();

    public AgentRuntime(Instrumentation instrumentation) {
        this(instrumentation, RuleDispatcherConfig.defaults());
    }

    public AgentRuntime(Instrumentation instrumentation, RuleDispatcherConfig dispatcherConfig) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.dispatcherConfig = Objects.requireNonNull(dispatcherConfig, "dispatcherConfig");
        this.instrumentationRegistry = new DefaultInstrumentationRegistry();
        this.ruleRegistry = new RuleRegistry();
        this.scriptCompilerFactory = new AgentScriptCompilerFactory(AgentRuntime.class.getClassLoader());
        this.rulePublisher = new RulePublisher(scriptCompilerFactory, ruleRegistry);
        this.eventBuffer = new RuntimeEventBuffer();
        this.ruleDispatcher = new RuleDispatcher(ruleRegistry, new DefaultRuntimeObjectFactory(),
                new com.example.kairo.core.DecisionValidator(),
                new com.example.kairo.core.ReentryGuard(),
                new com.example.kairo.core.SamplingPolicy(),
                eventBuffer,
                java.time.Clock.systemUTC(),
                dispatcherConfig);
        this.recordingObserver = new RecordingInvocationObserver();
        this.snapshotRepository = new BytecodeSnapshotRepository(
                new BytecodeSnapshotRepository.Config(256, 8L * 1024 * 1024, 30L * 60 * 1000));
        this.transformationJournal = new TransformationJournal(
                new TransformationJournal.Config(64, 4096));
        this.transformerManager = new ByteBuddyTransformerManager(instrumentation, instrumentationRegistry,
                snapshotRepository, transformationJournal);
        this.previewService = new TransformationPreviewService(instrumentationRegistry,
                snapshotRepository, transformationJournal);
        this.captureService = new BytecodeCaptureService(transformerManager,
                snapshotRepository, transformationJournal);
        this.diffService = new BytecodeDiffService();
        this.decompilerService = new DecompilerService(
                com.example.kairo.agent.core.bytecode.BytecodeDecompilers.defaultDecompiler(),
                2 * 1024 * 1024, 5000L);
        this.loadedClassRepository = new LoadedClassRepository(instrumentation);
        this.classLoaderRepository = new ClassLoaderRepository();
        this.moduleDiagnostics = new ModuleDiagnostics(instrumentation);
        this.scriptSessionManager = new ScriptSessionManager(this, scriptCompilerFactory,
                this::resolveScriptSessionTarget, Clock.systemUTC(), ScriptSessionLimits.defaults(),
                syntheticBridgePolicy);
        this.chainApplier = new RuleChainApplier(ruleRegistry, scriptCompilerFactory, loadedClassRepository,
                instrumentationRegistry, transformerManager, eventBuffer);
        // V1.5 §3.2: register every residual cache so the ReferenceQueue cleaner
        // purges snapshots, journal, compile cache, method cache and targets the
        // moment a tracked ClassLoader is garbage-collected.
        classLoaderRepository.addListener(snapshotRepository::clearForLoader);
        classLoaderRepository.addListener(transformationJournal::clearForLoader);
        classLoaderRepository.addListener(scriptCompilerFactory::clearForLoader);
        classLoaderRepository.addListener(ruleRegistry::clearForLoader);
        classLoaderRepository.addListener(instrumentationRegistry::clearForLoader);
        loadedClassRepository.bind(classLoaderRepository);
        // V1.5 §4.4: observe first loads so a pending rule materializes the instant a matching
        // class appears. The observer runs on the JVM class-loading thread and only hands work
        // to the cleanup executor, so the transformer never re-enters rule publication.
        transformerManager.addFirstLoadObserver(this::onClassFirstLoaded);
        // V1.5 §4.4: observe external redefines so the hot-update reconciler flags drift the
        // moment the redefined bytes are captured, and revalidates call-site fingerprints. The
        // listener runs on the redefine thread and hands heavy reconciliation off.
        transformerManager.addRedefinitionListener(this::onClassRedefinition);
    }

    public void start() {
        try {
            KairoBridge.install(new AgentBridgeDispatcher(ruleDispatcher, recordingObserver));
            transformerManager.install();
            cleanupExecutor.scheduleWithFixedDelay(this::cleanupExpiredRules, 1, 1, TimeUnit.SECONDS);
            cleanupExecutor.scheduleWithFixedDelay(scriptSessionManager::expireDue, 1, 1, TimeUnit.SECONDS);
            cleanupExecutor.scheduleWithFixedDelay(snapshotRepository::evictExpired, 5, 5, TimeUnit.SECONDS);
            // V1.5 §3.2: drain the ClassLoader ReferenceQueue so a collected loader's
            // residual caches are purged promptly rather than at the next capacity eviction.
            cleanupExecutor.scheduleWithFixedDelay(classLoaderRepository::pollCollected, 2, 2, TimeUnit.SECONDS);
            // V1.5 §4.4: materialize pending rules whose selector matches a class that has
            // since been loaded. Runs on the agent executor (not the class-load thread) so
            // the transformer stays free of re-entrancy.
            cleanupExecutor.scheduleWithFixedDelay(this::pollPendingMatches, 2, 2, TimeUnit.SECONDS);
            state.set(AgentState.ACTIVE);
            eventBuffer.record("agent.start", "system", null, null, "Kairo agent started");
        } catch (RuntimeException e) {
            enterDegraded("Agent startup failed: " + e.getMessage());
            throw e;
        }
    }

    public void loadMode(String loadMode) {
        this.loadMode = loadMode == null ? "unknown" : loadMode;
    }

    public CompiledRule publish(Method method, MockRule rule) {
        return publish(method, rule, "system");
    }

    public CompiledRule publish(Method method, MockRule rule, String actor) {
        snapshotLock.writeLock().lock();
        try {
            return publishLocked(method, rule, actor);
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    private CompiledRule publishLocked(Method method, MockRule rule, String actor) {
        requireOperationalForPublish();
        SyntheticBridgePolicy.Verdict verdict = syntheticBridgePolicy.evaluate(method);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException("Synthetic and bridge methods cannot be mocked: " + method
                    + "; " + verdict.reason());
        }
        rejectUnenhanceableMethod(method);
        MockRule effectiveRule = rule.callSiteSelector() != null
                ? resolveCallSiteRule(method, rule)
                : rule;
        EnhancementTarget newTarget = enhancementTargetOf(method, effectiveRule);
        MethodKey methodKey = MethodKey.of(method);
        PublishedRule previous = publishedRules.get(rule.id());
        EnhancementTarget previousTarget = targetByRuleId.get(rule.id());
        boolean publishApplied = false;
        boolean targetTransitioned = false;
        try {
            CompiledRule compiledRule = rulePublisher.publish(method, effectiveRule);
            publishApplied = true;
            publishedRules.put(rule.id(), new PublishedRule(method, null, methodKey,
                    compiledRule.rule(), compiledRule));
            targetTransitioned = applyTargetUpdate(method.getDeclaringClass(), previousTarget, newTarget);
            targetByRuleId.put(rule.id(), newTarget);
            eventBuffer.record(previous == null ? "rule.create" : "rule.update", actor, rule.id(),
                    methodKey.toString(), "Published rule version " + compiledRule.rule().version()
                            + " at " + effectiveRule.effectiveLocation());
            recordAppliedHash(method.getDeclaringClass());
            return compiledRule;
        } catch (RuntimeException e) {
            if (publishApplied) {
                ruleRegistry.restoreRule(methodKey, rule.id(),
                        previous == null ? null : previous.compiledRule());
                if (previous == null) {
                    publishedRules.remove(rule.id());
                } else {
                    publishedRules.put(rule.id(), previous);
                }
                try {
                    if (targetTransitioned) {
                        applyTargetUpdate(method.getDeclaringClass(), newTarget, previousTarget);
                        targetByRuleId.put(rule.id(), previousTarget);
                    }
                } catch (RuntimeException restoreFailure) {
                    degradedClasses.put(method.getDeclaringClass().getName(), restoreFailure.getMessage());
                    enterDegraded("Cannot restore instrumentation for " + method.getDeclaringClass().getName());
                    e.addSuppressed(restoreFailure);
                }
            }
            eventBuffer.record("rule.publish.failed", actor, rule.id(), methodKey.toString(), e.getMessage());
            throw e;
        }
    }

    /**
     * Publish a constructor-enhancement rule. Constructors do not have a
     * reflective {@code Method}, so they take a separate path that builds a
     * {@code <init>} target and weaves {@link ConstructorAdvice}.
     */
    public CompiledRule publishConstructor(Constructor<?> constructor, MockRule rule, String actor) {
        snapshotLock.writeLock().lock();
        try {
            return publishConstructorLocked(constructor, rule, actor);
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    private CompiledRule publishConstructorLocked(Constructor<?> constructor, MockRule rule, String actor) {
        requireOperationalForPublish();
        if (Modifier.isNative(constructor.getModifiers())) {
            throw new IllegalArgumentException("Native constructors cannot be enhanced: " + constructor);
        }
        rejectUnmodifiable(constructor.getDeclaringClass());
        EnhancementTarget newTarget = constructorTargetOf(constructor, rule);
        MethodKey methodKey = new MethodKey(constructor.getDeclaringClass(), "<init>",
                MethodDescriptor.of(constructor));
        PublishedRule previous = publishedRules.get(rule.id());
        EnhancementTarget previousTarget = targetByRuleId.get(rule.id());
        boolean publishApplied = false;
        boolean targetTransitioned = false;
        try {
            CompiledRule compiledRule = rulePublisher.publishConstructor(constructor, rule);
            publishApplied = true;
            publishedRules.put(rule.id(), new PublishedRule(null, constructor, methodKey,
                    compiledRule.rule(), compiledRule));
            targetTransitioned = applyTargetUpdate(constructor.getDeclaringClass(), previousTarget, newTarget);
            targetByRuleId.put(rule.id(), newTarget);
            eventBuffer.record(previous == null ? "rule.create" : "rule.update", actor, rule.id(),
                    methodKey.toString(), "Published constructor rule at " + rule.effectiveLocation());
            recordAppliedHash(constructor.getDeclaringClass());
            return compiledRule;
        } catch (RuntimeException e) {
            if (publishApplied) {
                ruleRegistry.restoreRule(methodKey, rule.id(),
                        previous == null ? null : previous.compiledRule());
                if (previous == null) {
                    publishedRules.remove(rule.id());
                } else {
                    publishedRules.put(rule.id(), previous);
                }
                try {
                    if (targetTransitioned) {
                        applyTargetUpdate(constructor.getDeclaringClass(), newTarget, previousTarget);
                        targetByRuleId.put(rule.id(), previousTarget);
                    }
                } catch (RuntimeException restoreFailure) {
                    degradedClasses.put(constructor.getDeclaringClass().getName(), restoreFailure.getMessage());
                    enterDegraded("Cannot restore instrumentation for " + constructor.getDeclaringClass().getName());
                    e.addSuppressed(restoreFailure);
                }
            }
            eventBuffer.record("rule.publish.failed", actor, rule.id(), methodKey.toString(), e.getMessage());
            throw e;
        }
    }

    public CompiledRule publish(String classId, MockRule rule, String actor) {
        Method method = loadedClassRepository.resolveMethod(classId, rule.target().methodName(), rule.target().methodDescriptor());
        return publish(method, rule, actor);
    }

    public CompiledRule publishTarget(String classIdOrName, MockRule rule, String actor) {
        Method method = loadedClassRepository.resolveMethodTarget(
                classIdOrName, rule.target().methodName(), rule.target().methodDescriptor());
        return publish(method, rule, actor);
    }

    public void remove(Method method, String ruleId) {
        remove(MethodKey.of(method), ruleId, "system");
    }

    public void remove(String ruleId, String actor) {
        PublishedRule publishedRule = requireRule(ruleId);
        remove(publishedRule.methodKey(), ruleId, actor);
    }

    public CompiledRule setEnabled(String ruleId, boolean enabled, String actor) {
        PublishedRule publishedRule = requireRule(ruleId);
        MockRule next = publishedRule.rule().toBuilder()
                .enabled(enabled)
                .version(publishedRule.rule().version() + 1)
                .build();
        CompiledRule compiledRule = publishedRule.constructor() != null
                ? publishConstructor(publishedRule.constructor(), next, actor)
                : publish(publishedRule.method(), next, actor);
        eventBuffer.record(enabled ? "rule.enable" : "rule.disable", actor, ruleId,
                publishedRule.methodKey().toString(), "Rule enabled=" + enabled);
        return compiledRule;
    }

    private void remove(MethodKey methodKey, String ruleId, String actor) {
        snapshotLock.writeLock().lock();
        try {
            removeLocked(methodKey, ruleId, actor);
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    private void removeLocked(MethodKey methodKey, String ruleId, String actor) {
        PublishedRule publishedRule = requireRule(ruleId);
        EnhancementTarget target = targetByRuleId.get(ruleId);
        Class<?> declaringClass = publishedRule.declaringClass();
        boolean targetRemoved = false;
        try {
            if (publishedRule.constructor() != null) {
                rulePublisher.remove(methodKey, ruleId);
            } else {
                rulePublisher.remove(publishedRule.method(), ruleId);
            }
            publishedRules.remove(ruleId);
            targetByRuleId.remove(ruleId);
            if (target != null) {
                applyTargetUpdate(declaringClass, target, null);
                targetRemoved = true;
            }
            eventBuffer.record("rule.delete", actor, ruleId, methodKey.toString(), "Deleted rule");
        } catch (RuntimeException e) {
            ruleRegistry.restoreRule(methodKey, ruleId, publishedRule.compiledRule());
            publishedRules.put(ruleId, publishedRule);
            targetByRuleId.put(ruleId, target);
            if (targetRemoved) {
                applyTargetUpdate(declaringClass, null, target);
            }
            eventBuffer.record("rule.delete.failed", actor, ruleId, methodKey.toString(), e.getMessage());
            throw e;
        }
    }

    public void disableAll(boolean disabled) {
        snapshotLock.writeLock().lock();
        try {
            disableAllLocked(disabled);
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    /**
     * V1.7 M1-F &sect;8.6 item 4: mark the agent as locally emergency-operated through the loopback api
     * ({@code disable-all}/{@code reset-all}/{@code reset-class}). The flag is reported in the runtime
     * snapshot so Platform reconciliation defers re-application rather than blindly undoing the
     * operator's manual recovery. Cleared by {@code enable-all} (the explicit resume).
     */
    public void markEmergency(String actor) {
        snapshotLock.writeLock().lock();
        try {
            emergencyHeld = true;
            eventBuffer.record("agent.emergency.hold", actor, null, null,
                    "Local emergency operation; Platform reconciliation will defer re-application until cleared");
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    /** V1.7 M1-F &sect;8.6 item 4: clear the emergency hold (the explicit resume, {@code enable-all}). */
    public void clearEmergency(String actor) {
        snapshotLock.writeLock().lock();
        try {
            emergencyHeld = false;
            eventBuffer.record("agent.emergency.cleared", actor, null, null,
                    "Emergency hold cleared; Platform reconciliation may resume");
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    /** V1.7 M1-F: whether a local emergency op has marked the agent (reported in the snapshot). */
    public boolean emergencyHeld() {
        snapshotLock.readLock().lock();
        try {
            return emergencyHeld;
        } finally {
            snapshotLock.readLock().unlock();
        }
    }

    private void disableAllLocked(boolean disabled) {
        globallyEnabled = !disabled;
        ruleDispatcher.enabled(globallyEnabled);
        if (state.get() != AgentState.DEGRADED) {
            state.set(disabled ? AgentState.DISABLED : AgentState.ACTIVE);
        }
        eventBuffer.record(disabled ? "agent.disable-all" : "agent.enable-all", "system", null, null,
                "Global enabled=" + globallyEnabled);
    }

    public void resetAll(String actor) {
        snapshotLock.writeLock().lock();
        try {
            resetAllLocked(actor);
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    private void resetAllLocked(String actor) {
        state.set(AgentState.RESETTING);
        KairoBridge.uninstall();
        ruleDispatcher.enabled(false);
        try {
            // V1.4: precise reset. Collect every class Kairo currently enhances, clear the
            // desired chains, unregister every target (empty Kairo plan), then retransform
            // each affected class so its bytecode regenerates without Kairo advice. The
            // transformer itself is NOT reset (no coarse transformer.reset / RESET_ALL):
            // other agents' advice and the transformer registration are preserved.
            java.util.Set<EnhancementTarget> targets = instrumentationRegistry.snapshot();
            java.util.Map<String, Class<?>> affected = new java.util.LinkedHashMap<>();
            for (EnhancementTarget t : targets) {
                String classId = loadedClassRepository.classId(
                        t.method().className(), t.method().classLoaderId());
                if (!affected.containsKey(classId)) {
                    try {
                        Class<?> clazz = loadedClassRepository.resolveClass(classId);
                        if (clazz != null && instrumentation.isModifiableClass(clazz)) {
                            affected.put(classId, clazz);
                        }
                    } catch (RuntimeException ignored) {
                        // class no longer loaded; nothing to retransform
                    }
                }
            }
            ruleRegistry.clear();
            chainApplier.clearCache();
            publishedRules.clear();
            targetByRuleId.clear();
            scriptSessionManager.clear();
            activeRecordings.clear();
            recordingObserver.clear();
            targets.forEach(instrumentationRegistry::unregister);
            for (Class<?> clazz : affected.values()) {
                try {
                    transformerManager.retransform(clazz);
                    transformerManager.recordRecovery(clazz, "reset-all precise retransform");
                } catch (RuntimeException ignore) {
                    degradedClasses.put(clazz.getName(), "reset retransform failed");
                }
            }
            degradedClasses.keySet().removeIf(name -> affected.values().stream()
                    .noneMatch(c -> c.getName().equals(name)));
            KairoBridge.install(new AgentBridgeDispatcher(ruleDispatcher, recordingObserver));
            globallyEnabled = true;
            ruleDispatcher.enabled(true);
            state.set(AgentState.ACTIVE);
            eventBuffer.record("agent.reset-all", actor, null, null,
                    "Cleared Kairo chains and regenerated " + affected.size()
                            + " class(es) via precise retransform (no RESET_ALL)");
        } catch (RuntimeException e) {
            enterDegraded("Reset all failed: " + e.getMessage());
            eventBuffer.record("agent.reset-all.failed", actor, null, null, e.getMessage());
            throw e;
        }
    }

    // -------------------------------------------------------- V1.4 fenced chain apply

    /**
     * Apply a fenced rule-chain command: compile the full desired chain, run
     * conflict analysis, retransform only when the footprint changes, verify via
     * V1.1 actual-bytecode result, then atomically CAS-replace the running
     * snapshot. Stale revisions return {@code STALE_COMMAND}; duplicate
     * idempotency keys replay the previous result.
     */
    public com.example.kairo.api.ApplyChainResult applyRuleChain(com.example.kairo.api.ApplyChainRequest request) {
        snapshotLock.writeLock().lock();
        try {
            return chainApplier.applyChain(request);
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    /** Reconcile the Agent's actual chain against the Platform's desired revision. */
    public com.example.kairo.api.ReconcileResult reconcileChain(com.example.kairo.api.EnhancementTarget target,
                                                                com.example.kairo.api.RuleChainRevision desired) {
        return chainApplier.reconcile(target, desired);
    }

    /** The V1.4 chain applier, exposed for tests and the platform command layer. */
    public RuleChainApplier chainApplier() {
        return chainApplier;
    }

    public ResetClassResult resetClass(String classId, String actor) {
        snapshotLock.writeLock().lock();
        try {
            return resetClassLocked(classId, actor);
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    private ResetClassResult resetClassLocked(String classId, String actor) {
        scriptSessionManager.deactivateTarget(classId, actor);
        activeRecordings.values().stream()
                .filter(recording -> classId.equals(recording.classId()) || classId.equals(recording.className()))
                .map(RecordingRegistration::sessionId)
                .toList()
                .forEach(sessionId -> stopRecording(sessionId, actor));
        List<String> ruleIds = publishedRules.values().stream()
                .filter(rule -> matchesClass(rule, classId))
                .map(rule -> rule.rule().id())
                .toList();
        List<String> removed = new java.util.ArrayList<>();
        Map<String, String> failures = new java.util.LinkedHashMap<>();
        for (String ruleId : ruleIds) {
            try {
                remove(ruleId, actor);
                removed.add(ruleId);
            } catch (RuntimeException e) {
                failures.put(ruleId, String.valueOf(e.getMessage()));
            }
        }
        if (failures.isEmpty()) {
            degradedClasses.remove(classId);
        } else {
            degradedClasses.put(classId, failures.toString());
            enterDegraded("Class reset failed for " + classId);
        }
        eventBuffer.record("agent.reset-class", actor, null, classId,
                "Removed " + removed.size() + " rules; failures=" + failures.size());
        return new ResetClassResult(classId, List.copyOf(removed), Map.copyOf(failures), rules(),
                !failures.isEmpty());
    }

    public void recordEvent(String type, String actor, String ruleId, String target, String message) {
        eventBuffer.record(type, actor, ruleId, target, message);
    }

    public void recordingSink(RecordingEventSink sink) {
        recordingObserver.sink(sink);
    }

    public RecordingRegistration startRecording(String sessionId, String classIdOrName,
                                                String methodName, String methodDescriptor,
                                                String actor) {
        requireOperationalForPublish();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("recording sessionId is required");
        }
        RecordingRegistration existing = activeRecordings.get(sessionId);
        if (existing != null) {
            return existing;
        }
        Method method = loadedClassRepository.resolveMethodTarget(
                classIdOrName, methodName, methodDescriptor);
        SyntheticBridgePolicy.Verdict verdict = syntheticBridgePolicy.evaluate(method);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException("Synthetic and bridge methods cannot be recorded: " + method
                    + "; " + verdict.reason());
        }
        MethodKey methodKey = MethodKey.of(method);
        EnhancementTarget target = recordingTargetOf(method);
        RecordingRegistration registration = new RecordingRegistration(
                sessionId,
                loadedClassRepository.classId(method.getDeclaringClass()),
                method.getDeclaringClass().getName(),
                method.getName(),
                MethodDescriptor.of(method)
        );
        boolean registered = false;
        try {
            recordingObserver.start(methodKey, registration);
            activeRecordings.put(sessionId, registration);
            applyTargetUpdate(method.getDeclaringClass(), null, target);
            registered = true;
            eventBuffer.record("recording.start", actor, null, methodKey.toString(),
                    "Recording session " + sessionId + " started");
            return registration;
        } catch (RuntimeException e) {
            activeRecordings.remove(sessionId);
            recordingObserver.stop(methodKey, sessionId);
            if (registered) {
                applyTargetUpdate(method.getDeclaringClass(), target, null);
            }
            eventBuffer.record("recording.start.failed", actor, null, methodKey.toString(), e.getMessage());
            throw e;
        }
    }

    public RecordingRegistration stopRecording(String sessionId, String actor) {
        RecordingRegistration registration = activeRecordings.remove(sessionId);
        if (registration == null) {
            return null;
        }
        Method method = loadedClassRepository.resolveMethod(
                registration.classId(), registration.methodName(), registration.methodDescriptor());
        MethodKey methodKey = MethodKey.of(method);
        EnhancementTarget target = recordingTargetOf(method);
        recordingObserver.stop(methodKey, sessionId);
        boolean removed = false;
        try {
            applyTargetUpdate(method.getDeclaringClass(), target, null);
            removed = true;
            eventBuffer.record("recording.stop", actor, null, methodKey.toString(),
                    "Recording session " + sessionId + " stopped");
            return registration;
        } catch (RuntimeException e) {
            recordingObserver.start(methodKey, registration);
            activeRecordings.put(sessionId, registration);
            if (removed) {
                applyTargetUpdate(method.getDeclaringClass(), null, target);
            }
            eventBuffer.record("recording.stop.failed", actor, null, methodKey.toString(), e.getMessage());
            throw e;
        }
    }

    public List<RecordingRegistration> recordings() {
        return activeRecordings.values().stream()
                .sorted(Comparator.comparing(RecordingRegistration::sessionId))
                .toList();
    }

    public CompiledMockScript compileScript(String ruleId, long version, String script) {
        CompiledMockScript compiled = scriptCompilerFactory.compileScript(ruleId, version, script);
        eventBuffer.record("script.compile", "api", ruleId, null, "Compiled script " + compiled.scriptHash());
        return compiled;
    }

    public List<ClassInfo> searchClasses(String keyword, int limit) {
        return loadedClassRepository.search(keyword, limit);
    }

    public List<MethodInfo> methods(String classId) {
        return loadedClassRepository.methods(classId);
    }

    public List<MethodInfo> constructors(String classId) {
        return loadedClassRepository.constructors(classId);
    }

    public LoadedClassRepository loadedClassRepository() {
        return loadedClassRepository;
    }

    /** V1.5: the lifecycle-aware ClassLoader registry + ReferenceQueue cleaner. */
    public ClassLoaderRepository classLoaderRepository() {
        return classLoaderRepository;
    }

    /** V1.5 §4.2: the proxy-target analyzer SPI. */
    public ProxyTargetAnalyzer proxyAnalyzer() {
        return proxyAnalyzer;
    }

    /** V1.5 §4.5: module diagnostics + minimal-open redefineModule. */
    public ModuleDiagnostics moduleDiagnostics() {
        return moduleDiagnostics;
    }

    /**
     * V1.5 &sect;4.3: the synthetic/bridge/lambda policy. Defaults refuse bridge and
     * compiler-synthetic methods with a recommendation to enhance the user-declared
     * method; arm {@code allowBridge}/{@code allowSynthetic} for an explicit opt-in.
     */
    public SyntheticBridgePolicy syntheticBridgePolicy() {
        return syntheticBridgePolicy;
    }

    /** V1.5 &sect;4.4: the pending-enhancement registry for not-yet-loaded classes. */
    public PendingEnhancementRegistry pendingRegistry() {
        return pendingRegistry;
    }

    /** V1.5 &sect;4.4: hot-update reconciliation (bytecode-hash drift -> TARGET_DRIFTED). */
    public HotUpdateReconciler hotUpdateReconciler() {
        return hotUpdateReconciler;
    }

    /**
     * V1.5 &sect;4.4: reconcile a class's current bytecode hash against the hash recorded
     * at the last successful apply. Returns a {@link HotUpdateReconciler.Result} whose
     * outcome is DRIFTED when the class was externally redefined; the caller maps that to
     * {@link com.example.kairo.api.ApplyChainStatus#TARGET_DRIFTED} and fails open.
     */
    public HotUpdateReconciler.Result checkHotUpdateDrift(Class<?> type, String currentInputBytecodeHash) {
        return hotUpdateReconciler.reconcile(ClassIdentities.of(type), currentInputBytecodeHash);
    }

    /** Record the input bytecode hash observed when a rule was applied to {@code type}. */
    private void recordAppliedHash(Class<?> type) {
        try {
            ClassIdentity identity = ClassIdentities.of(type);
            String hash = latestInputHash(identity);
            if (hash != null) {
                hotUpdateReconciler.recordApplied(identity, hash);
            }
            // V1.5 §4.4: a successful apply re-anchors Kairo to the current bytes, so any prior
            // drift flag for this class is resolved. The redefine listener does not fire for
            // Kairo's own retransform (gated on Mode.IDLE), so this clear is race-free.
            driftedClasses.remove(type.getName());
        } catch (RuntimeException ignored) {
            // the reconciler must never break the publish path
        }
    }

    private String latestInputHash(ClassIdentity identity) {
        var snapshots = snapshotRepository.metadataFor(identity);
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            if (snapshots.get(i).kind() == com.example.kairo.api.bytecode.BytecodeSnapshotKind.INPUT) {
                return snapshots.get(i).hash();
            }
        }
        return null;
    }

    /**
     * V1.5 &sect;4.4: pre-register a rule for a class that is not yet loaded. The rule
     * is held against the fuzzy {@link ClassSelector}; when a matching class loads,
     * {@link #pollPendingMatches()} materializes it (builds the V1.4 chain against the
     * actual class) and records the resolved {@link ClassIdentity} for audit. Only
     * method-location rules may be pre-registered (call-site rules need a loaded caller).
     */
    public void registerPendingRule(ClassSelector selector, MockRule rule, String actor) {
        if (rule.callSiteSelector() != null) {
            throw new IllegalArgumentException(
                    "Call-site rules cannot be pre-registered; the caller class must be loaded first");
        }
        pendingRegistry.register(selector, rule, actor == null ? "system" : actor, System.currentTimeMillis());
        // V1.5 §4.4: arm first-load observation so the next class load materializes this rule
        // immediately instead of waiting up to 2s for the poll.
        updateFirstLoadObservation();
        eventBuffer.record("rule.pending.registered", actor == null ? "system" : actor, rule.id(),
                selector.className(), "pending rule registered for first-load match");
    }

    /**
     * V1.5 &sect;4.4: scan loaded classes for matches against pending selectors and
     * materialize matching rules. A fuzzy selector that matches more than one loader is
     * refused (audited as ambiguous) unless it declared all-match. Returns the number of
     * rules materialized this pass. Safe to call concurrently; a no-op when nothing is
     * pending.
     */
    public int pollPendingMatches() {
        if (pendingRegistry.pendingCount() == 0) {
            return 0;
        }
        int materialized = 0;
        for (PendingEnhancementRegistry.PendingEntry entry : pendingRegistry.pending()) {
            materialized += materializeEntry(entry);
        }
        updateFirstLoadObservation();
        return materialized;
    }

    /**
     * V1.5 &sect;4.4: materialize pending rules whose selector names exactly {@code binaryName}.
     * Invoked by the first-load observer the instant a matching class loads, so a pending rule
     * takes effect on the first frame rather than the next 2s poll. Runs on the agent cleanup
     * executor (handed off from the class-loading thread), preserving no-reentry.
     */
    public int materializePendingForClass(String binaryName) {
        if (binaryName == null || binaryName.isBlank() || pendingRegistry.pendingCount() == 0) {
            return 0;
        }
        int materialized = 0;
        for (PendingEnhancementRegistry.PendingEntry entry : pendingRegistry.pending()) {
            if (!entry.selector().className().equals(binaryName)) {
                continue;
            }
            materialized += materializeEntry(entry);
        }
        updateFirstLoadObservation();
        return materialized;
    }

    /**
     * Materialize one pending entry against every loaded class that satisfies its selector.
     * Shared by the periodic poll and the first-load observer. Returns the number of rules
     * materialized (0 when the class is not loaded yet, or the selector is ambiguous).
     */
    private int materializeEntry(PendingEnhancementRegistry.PendingEntry entry) {
        ClassSelector selector = entry.selector();
        java.util.List<Class<?>> candidates = loadedClassRepository.findAllByName(selector.className());
        java.util.List<Class<?>> filtered = new java.util.ArrayList<>();
        java.util.List<String> candidateLoaderIds = new java.util.ArrayList<>();
        for (Class<?> type : candidates) {
            ClassMetadata md = ClassIdentities.metadataOf(type, SupportLevel.SUPPORTED);
            String loaderId = md.identity().classLoaderId();
            if (pendingRegistry.matches(selector, type.getName(), loaderId,
                    md.loaderClassName(), md.moduleName(), md.codeSource())) {
                filtered.add(type);
                candidateLoaderIds.add(loaderId);
            }
        }
        if (filtered.isEmpty()) {
            return 0; // not loaded yet
        }
        if (!selector.isExact() && !selector.allMatch() && filtered.size() > 1) {
            pendingRegistry.markAmbiguous(entry.ruleId(), selector.className(),
                    candidateLoaderIds, System.currentTimeMillis());
            eventBuffer.record("rule.pending.ambiguous", entry.actor(), entry.ruleId(),
                    selector.className(),
                    "fuzzy selector matched " + filtered.size() + " loaders; refusing without allMatch");
            return 0;
        }
        int materialized = 0;
        for (Class<?> type : filtered) {
            try {
                Method method = resolveMethodOn(type, entry.rule().target());
                publish(method, entry.rule(), entry.actor());
                ClassIdentity identity = ClassIdentities.of(type);
                pendingRegistry.markResolved(entry.ruleId(), identity, identity.classLoaderId(),
                        java.util.List.of("materialized on first load"), System.currentTimeMillis());
                eventBuffer.record("rule.pending.applied", entry.actor(), entry.ruleId(),
                        type.getName(), "pending rule materialized on first load");
                materialized++;
            } catch (RuntimeException | NoSuchMethodException e) {
                eventBuffer.record("rule.pending.failed", entry.actor(), entry.ruleId(),
                        type.getName(), e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        pendingRegistry.cancel(entry.ruleId());
        return materialized;
    }

    /** Arm/disarm first-load observation based on whether any pending rule remains. */
    private void updateFirstLoadObservation() {
        transformerManager.setFirstLoadObservationEnabled(pendingRegistry.pendingCount() > 0);
    }

    /**
     * V1.5 &sect;4.4: first-load observer callback. Runs on the JVM class-loading thread; must
     * not publish here. Filters to pending selectors that name this class, then hands
     * materialization to the cleanup executor so the class-loading thread never re-enters rule
     * publication / transformation.
     */
    private void onClassFirstLoaded(String internalName, ClassLoader loader) {
        if (pendingRegistry.pendingCount() == 0) {
            return;
        }
        String binaryName = internalName.replace('/', '.');
        if (!pendingRegistry.hasPendingForClass(binaryName)) {
            return;
        }
        try {
            cleanupExecutor.submit(() -> {
                try {
                    materializePendingForClass(binaryName);
                } catch (RuntimeException e) {
                    eventBuffer.record("rule.pending.failed", "system", null, binaryName,
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            });
        } catch (RuntimeException ignored) {
            // executor rejected (agent shutting down) - the 2s poll is the fallback
        }
    }

    /**
     * V1.5 &sect;4.4: redefine listener callback. Runs on the JVM redefine thread for an
     * external redefine of a class Kairo has an active target on (the transformer gates on
     * Mode.IDLE and containsType). Hands the heavy hash+reconcile+revalidate work to the
     * cleanup executor so the redefine thread is not blocked.
     */
    private void onClassRedefinition(ClassIdentity identity, byte[] inputBytes, ClassLoader loader) {
        if (!hotUpdateReconciler.hasRecorded(identity)) {
            return;
        }
        try {
            cleanupExecutor.submit(() -> {
                try {
                    String hash = BytecodeHash.sha256Hex(inputBytes);
                    HotUpdateReconciler.Result result = hotUpdateReconciler.reconcile(identity, hash);
                    if (result.isDrifted()) {
                        driftedClasses.put(identity.binaryClassName(), result.reason());
                        eventBuffer.record("target.drifted", "system", null,
                                identity.binaryClassName(),
                                "bytecode hash changed after external redefine; " + result.reason());
                        revalidateCallSiteRules(identity);
                    } else if (driftedClasses.containsKey(identity.binaryClassName())) {
                        // The class returned to bytes compatible with the anchored hash; clear drift.
                        driftedClasses.remove(identity.binaryClassName());
                    }
                } catch (RuntimeException ignored) {
                    // reconciliation must never break the agent
                }
            });
        } catch (RuntimeException ignored) {
            // executor rejected (agent shutting down)
        }
    }

    /**
     * V1.5 &sect;4.4: re-validate every call-site rule anchored on a drifted class. A drifted
     * declaring class may have moved the call-site fingerprint; re-resolve each call-site rule
     * and record an event when the fingerprint no longer matches, so the operator is told which
     * rules are now stale. Best-effort: a failure to re-resolve one rule does not stop the others.
     */
    private void revalidateCallSiteRules(ClassIdentity identity) {
        String className = identity.binaryClassName();
        for (PublishedRule published : publishedRules.values()) {
            MockRule mock = published.rule();
            if (mock.callSiteSelector() == null) {
                continue;
            }
            if (!className.equals(published.methodKey().className())) {
                continue;
            }
            EnhancementTarget target = targetByRuleId.get(mock.id());
            if (target == null) {
                continue;
            }
            try {
                Class<?> type = loadedClassRepository.findClass(
                        loadedClassRepository.classId(className, identity.classLoaderId())).orElse(null);
                if (type == null) {
                    continue;
                }
                TargetMatchResult result = resolveTarget(type, target);
                if (result.status() != TargetMatchResult.Status.MATCHED) {
                    eventBuffer.record("rule.callsite.drifted", "system", mock.id(),
                            published.methodKey().toString(),
                            "call-site " + result.status() + " after redefine: " + result.reason());
                }
            } catch (RuntimeException ignored) {
                // best-effort revalidation
            }
        }
    }

    /** V1.5 §4.4: whether a class is currently flagged as drifted (external redefine changed its hash). */
    public boolean isClassDrifted(String binaryName) {
        return binaryName != null && driftedClasses.containsKey(binaryName);
    }

    /** V1.5 §4.4: snapshot of currently-drifted classes (className -> reason) for diagnostics. */
    public Map<String, String> driftedClasses() {
        return Map.copyOf(driftedClasses);
    }

    /**
     * V1.7 M1-C &sect;8.3: snapshot of currently-degraded classes (className -> reason). Read by the
     * runtime-state snapshot under the snapshot read lock so it is consistent with the other
     * snapshotted state.
     */
    public Map<String, String> degradedClasses() {
        return Map.copyOf(degradedClasses);
    }

    /**
     * V1.7 M1-C &sect;8.3: the Agent version, the single source also surfaced via {@link JvmInfo}
     * during registration so a snapshot's {@code agentVersion} cannot drift from what the Agent
     * registered.
     */
    public String agentVersion() {
        return "0.1.0-SNAPSHOT";
    }

    /** V1.7 M1-C &sect;8.3: the global disabled flag (true when rule dispatch is globally disabled). */
    public boolean disabled() {
        return !globallyEnabled;
    }

    /**
     * V1.7 M1-C &sect;8.3: a bounded, read-only snapshot of the Agent's in-memory runtime state,
     * captured at one consistent logical point in time under the snapshot read lock. The snapshot
     * reads published rules, published chains, the disabled flag and degraded classes; it never
     * calls enhance, unload, compile, decompile, transform or discover. {@code agentId} and
     * {@code processStartId} are the identity resolved by the caller through the same centralized
     * logic used during Agent registration, so the snapshot cannot carry a drifted identity.
     */
    public com.example.kairo.api.snapshot.AgentRuntimeSnapshot snapshotRuntimeState(String agentId,
                                                                                     String processStartId) {
        snapshotLock.readLock().lock();
        try {
            return RuntimeStateSnapshotBuilder.build(
                    ruleRegistry()::forEachChain,
                    cons -> degradedClasses.forEach(cons),
                    agentVersion(),
                    disabled(),
                    emergencyHeld(),
                    agentId,
                    processStartId);
        } finally {
            snapshotLock.readLock().unlock();
        }
    }

    private Method resolveMethodOn(Class<?> type, MethodSelector selector) throws NoSuchMethodException {
        Class<?>[] params = com.example.kairo.core.MethodDescriptorTypes.parameterTypes(
                selector.methodDescriptor(), type.getClassLoader());
        return type.getDeclaredMethod(selector.methodName(), params);
    }

    public List<RuleInfo> rules() {
        return publishedRules.values().stream()
                .sorted(Comparator.comparing(rule -> rule.rule().id()))
                .map(this::toRuleInfo)
                .toList();
    }

    public List<RuntimeEvent> events() {
        return eventBuffer.snapshot();
    }

    public RuntimeMetrics metrics() {
        int totalRuleCount = publishedRules.size();
        int activeRuleCount = (int) publishedRules.values().stream()
                .filter(rule -> isActive(rule.compiledRule().rule()))
                .count();
        long hits = publishedRules.values().stream().mapToLong(rule -> rule.compiledRule().hits()).sum();
        long errors = publishedRules.values().stream().mapToLong(rule -> rule.compiledRule().errors()).sum();
        return new RuntimeMetrics(
                instrumentation.getAllLoadedClasses().length,
                instrumentationRegistry.typeCount(),
                instrumentationRegistry.methodCount(),
                totalRuleCount,
                activeRuleCount,
                hits,
                errors,
                globallyEnabled
        );
    }

    public JvmInfo jvmInfo() {
        RuntimeMetrics metrics = metrics();
        return new JvmInfo(
                ManagementFactory.getRuntimeMXBean().getName(),
                ProcessHandle.current().pid(),
                hostName(),
                System.getProperty("java.version"),
                startTimeMillis,
                agentVersion(),
                loadMode,
                state.get().name(),
                metrics.enhancedClassCount(),
                metrics.enhancedMethodCount(),
                metrics.activeRuleCount()
        );
    }

    public RuleRegistry ruleRegistry() {
        return ruleRegistry;
    }

    public DefaultInstrumentationRegistry instrumentationRegistry() {
        return instrumentationRegistry;
    }

    /** The JVM instrumentation the agent was started with (exposed for tests / attach paths). */
    public Instrumentation instrumentation() {
        return instrumentation;
    }

    public ByteBuddyTransformerManager transformerManager() {
        return transformerManager;
    }

    public BytecodeSnapshotRepository snapshotRepository() {
        return snapshotRepository;
    }

    public TransformationJournal transformationJournal() {
        return transformationJournal;
    }

    public TransformationPreviewService previewService() {
        return previewService;
    }

    public BytecodeCaptureService captureService() {
        return captureService;
    }

    public BytecodeDiffService diffService() {
        return diffService;
    }

    public DecompilerService decompilerService() {
        return decompilerService;
    }

    public ScriptSessionManager scriptSessionManager() {
        return scriptSessionManager;
    }

    /** The ClassLoader-aware compiler factory shared by rule publishing and the script-compile command. */
    public AgentScriptCompilerFactory scriptCompilerFactory() {
        return scriptCompilerFactory;
    }

    @Override
    public void close() {
        // V1.7 M1-C §8.3: coordinate with the snapshot write lock so a snapshot read never observes a
        // half-closed registry/publishedRules set. Held only for the in-memory clear; the heavy
        // subsystem close() calls run after the lock is released so a snapshot is not blocked by them.
        snapshotLock.writeLock().lock();
        try {
            state.set(AgentState.STOPPING);
            KairoBridge.uninstall();
            ruleRegistry.clear();
            publishedRules.clear();
            targetByRuleId.clear();
            degradedClasses.clear();
            globallyEnabled = false;
        } finally {
            snapshotLock.writeLock().unlock();
        }
        scriptSessionManager.close();
        activeRecordings.clear();
        recordingObserver.clear();
        transformerManager.close();
        decompilerService.close();
        snapshotRepository.close();
        ruleDispatcher.close();
        scriptCompilerFactory.close();
        cleanupExecutor.shutdownNow();
        state.set(AgentState.STOPPED);
        eventBuffer.record("agent.stop", "system", null, null, "Kairo agent stopped");
    }

    // ------------------------------------------------------------------ ScriptSessionHost

    @Override
    public CompiledRule applyTrialRule(Method targetMethod, MockRule rule, String actor) {
        return publish(targetMethod, rule, actor);
    }

    @Override
    public void revertTrialRule(String ruleId, String actor) {
        remove(ruleId, actor);
    }

    @Override
    public void recordSessionEvent(String type, String actor, String sessionId, String target, String message) {
        eventBuffer.record(type, actor, sessionId, target, message);
    }

    /** Resolve a {@link MethodSelector} to a live method plus its stable class id for a session. */
    private ScriptSessionTarget resolveScriptSessionTarget(MethodSelector target) {
        Method method;
        if (target.classLoaderId() != null && !target.classLoaderId().isBlank()) {
            String classId = loadedClassRepository.classId(target.className(), target.classLoaderId());
            method = loadedClassRepository.resolveMethod(classId,
                    target.methodName(), target.methodDescriptor());
        } else {
            method = loadedClassRepository.resolveMethodTarget(
                    target.className(), target.methodName(), target.methodDescriptor());
        }
        return new ScriptSessionTarget(method,
                loadedClassRepository.classId(method.getDeclaringClass()),
                method.getDeclaringClass().getName());
    }

    /**
     * Build the authoritative V1.3 target for a method rule from the live method
     * (so the selector carries the real class loader id the registry matches on)
     * and the rule's effective location / call-site selector.
     */
    private EnhancementTarget enhancementTargetOf(Method method, MockRule rule) {
        MethodSelector selector = new MethodSelector(
                method.getDeclaringClass().getName(),
                ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()),
                method.getName(),
                MethodDescriptor.of(method));
        EnhancementLocation location = rule.effectiveLocation();
        if (rule.callSiteSelector() != null) {
            return EnhancementTarget.callSite(selector, location, rule.callSiteSelector());
        }
        return EnhancementTarget.of(selector, location);
    }

    /**
     * Build the authoritative target for a constructor rule. Constructor rules must
     * carry an explicit constructor location; a legacy phase projected onto a method
     * location is rejected so a constructor is never woven with method Advice.
     */
    private EnhancementTarget constructorTargetOf(Constructor<?> constructor, MockRule rule) {
        MethodSelector selector = new MethodSelector(
                constructor.getDeclaringClass().getName(),
                ClassLoaderIdentity.idOf(constructor.getDeclaringClass().getClassLoader()),
                "<init>",
                MethodDescriptor.of(constructor));
        EnhancementLocation location = rule.effectiveLocation();
        if (!location.isConstructorLocation()) {
            throw new IllegalArgumentException(
                    "Constructor rule must use a constructor location, got " + location);
        }
        return EnhancementTarget.of(selector, location);
    }

    /** A method recording weaves the same enter/exit method Advice as a METHOD_ENTER rule. */
    private EnhancementTarget recordingTargetOf(Method method) {
        MethodSelector selector = new MethodSelector(
                method.getDeclaringClass().getName(),
                ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()),
                method.getName(),
                MethodDescriptor.of(method));
        return EnhancementTarget.of(selector, EnhancementLocation.METHOD_ENTER);
    }

    /**
     * Reject methods and classes that V1.3 cannot enhance with a clear diagnostic rather
     * than silently failing to weave: native/abstract methods and JVM-unmodifiable classes.
     */
    private void rejectUnenhanceableMethod(Method method) {
        int mods = method.getModifiers();
        if (Modifier.isNative(mods)) {
            throw new IllegalArgumentException("Native methods cannot be mocked: " + method);
        }
        if (Modifier.isAbstract(mods)) {
            throw new IllegalArgumentException("Abstract methods cannot be mocked: " + method);
        }
        rejectUnmodifiable(method.getDeclaringClass());
    }

    private void rejectUnmodifiable(Class<?> declaringClass) {
        if (!instrumentation.isModifiableClass(declaringClass)) {
            throw new IllegalArgumentException(
                    "Class is not modifiable by the JVM and cannot be enhanced: " + declaringClass.getName());
        }
    }

    /**
     * Resolve a call-site rule against live bytecode before publishing: reject unsupported
     * opcodes (invokedynamic), reject when the occurrence is absent, reject when the
     * surrounding-instruction fingerprint has drifted, and otherwise return the rule with
     * the freshly captured fingerprint baked into its selector so a later recompilation
     * can be detected.
     */
    private MockRule resolveCallSiteRule(Method callerMethod, MockRule rule) {
        CallSiteSelector selector = rule.callSiteSelector();
        if (!selector.opcode().isSupported()) {
            throw new IllegalArgumentException(
                    "Unsupported invoke opcode for call-site enhancement: " + selector.opcode());
        }
        MethodSelector caller = new MethodSelector(
                callerMethod.getDeclaringClass().getName(),
                ClassLoaderIdentity.idOf(callerMethod.getDeclaringClass().getClassLoader()),
                callerMethod.getName(),
                MethodDescriptor.of(callerMethod));
        TargetMatchResult result = callSiteScanner.resolveCallSite(
                callerMethod.getDeclaringClass(), caller, selector);
        if (result.status() == TargetMatchResult.Status.NOT_FOUND) {
            throw new IllegalArgumentException("Call site not found: " + selector
                    + " (" + result.reason() + ")");
        }
        if (result.status() == TargetMatchResult.Status.DRIFTED) {
            throw new IllegalArgumentException("Call site drifted and will not be enhanced: " + selector
                    + " (" + result.reason() + ")");
        }
        if (result.status() == TargetMatchResult.Status.REJECTED) {
            throw new IllegalArgumentException("Call site rejected: " + selector
                    + " (" + result.reason() + ")");
        }
        CallSiteSelector withFingerprint = CallSiteSelector.builder()
                .owner(selector.owner())
                .name(selector.name())
                .descriptor(selector.descriptor())
                .opcode(selector.opcode())
                .occurrenceIndex(selector.occurrenceIndex())
                .fingerprint(result.resolvedIdentity().selector().fingerprint())
                .build();
        return rule.toBuilder().callSiteSelector(withFingerprint).build();
    }

    /**
     * Resolve an enhancement target against live bytecode / reflection. Used by the
     * platform before saving a rule so a drifted or unenhanceable target is refused
     * rather than silently woven. Method and constructor targets are checked by
     * reflection; call-site targets by the {@link CallSiteScanner}.
     */
    public TargetMatchResult resolveTarget(Class<?> declaringClass, EnhancementTarget target) {
        // V1.5 §4.4: if the declaring class was externally redefined since the last successful
        // apply (hash drift detected by the redefine listener), refuse to resolve so the platform
        // surfaces TARGET_DRIFTED instead of weaving a stale target. A fresh successful apply
        // (recordAppliedHash) or a redefine back to compatible bytes clears the flag.
        if (declaringClass != null && isClassDrifted(declaringClass.getName())) {
            return TargetMatchResult.drifted(
                    "class " + declaringClass.getName()
                            + " drifted since last apply: " + driftedClasses.get(declaringClass.getName()),
                    null);
        }
        EnhancementLocation location = target.location();
        if (location.isCallSiteLocation()) {
            if (target.callSiteSelector() == null) {
                return TargetMatchResult.rejected("call-site target has no selector");
            }
            if (!target.callSiteSelector().opcode().isSupported()) {
                return TargetMatchResult.rejected("unsupported opcode: " + target.callSiteSelector().opcode());
            }
            return callSiteScanner.resolveCallSite(declaringClass, target.method(), target.callSiteSelector());
        }
        if (location.isConstructorLocation()) {
            return resolveConstructorTarget(declaringClass, target);
        }
        return resolveMethodTarget(declaringClass, target);
    }

    private TargetMatchResult resolveMethodTarget(Class<?> declaringClass, EnhancementTarget target) {
        Method method;
        try {
            Class<?>[] params = com.example.kairo.core.MethodDescriptorTypes.parameterTypes(
                    target.method().methodDescriptor(), declaringClass.getClassLoader());
            method = declaringClass.getDeclaredMethod(target.method().methodName(), params);
        } catch (NoSuchMethodException e) {
            return TargetMatchResult.notFound("method not found: " + target.method().methodName()
                    + target.method().methodDescriptor());
        } catch (RuntimeException e) {
            return TargetMatchResult.rejected(e.getMessage());
        }
        int mods = method.getModifiers();
        if (Modifier.isNative(mods) || Modifier.isAbstract(mods)) {
            return TargetMatchResult.rejected("native or abstract method: " + method);
        }
        SyntheticBridgePolicy.Verdict verdict = syntheticBridgePolicy.evaluate(method);
        if (!verdict.isAllowed()) {
            return TargetMatchResult.rejected("synthetic or bridge method: " + method
                    + "; " + verdict.reason());
        }
        return TargetMatchResult.matched(1);
    }

    private TargetMatchResult resolveConstructorTarget(Class<?> declaringClass, EnhancementTarget target) {
        Constructor<?> constructor;
        try {
            Class<?>[] params = com.example.kairo.core.MethodDescriptorTypes.parameterTypes(
                    target.method().methodDescriptor(), declaringClass.getClassLoader());
            constructor = declaringClass.getDeclaredConstructor(params);
        } catch (NoSuchMethodException e) {
            return TargetMatchResult.notFound("constructor not found: " + target.method().methodDescriptor());
        } catch (RuntimeException e) {
            return TargetMatchResult.rejected(e.getMessage());
        }
        if (Modifier.isNative(constructor.getModifiers())) {
            return TargetMatchResult.rejected("native constructor: " + constructor);
        }
        return TargetMatchResult.matched(1);
    }

    /**
     * Read-only enumeration of call-site candidates inside a caller method, for the
     * target-discovery API. Each candidate carries its occurrence index and a freshly
     * captured fingerprint so the platform can present choices and persist a stable
     * identity.
     */
    public List<CallSiteIdentity> listCallSites(Class<?> callerClass, String callerMethodName,
                                                String callerDescriptor, String calleeOwner, String calleeName,
                                                String calleeDescriptor, InvokeOpcode opcode) {
        MethodSelector caller = new MethodSelector(
                callerClass.getName(),
                ClassLoaderIdentity.idOf(callerClass.getClassLoader()),
                callerMethodName,
                callerDescriptor);
        return callSiteScanner.scan(callerClass, caller, calleeOwner, calleeName, calleeDescriptor, opcode);
    }

    /** The call-site scanner, exposed for tests and the platform command layer. */
    public CallSiteScanner callSiteScanner() {
        return callSiteScanner;
    }

    /**
     * Swap the registered target for one rule id from {@code previous} to {@code next},
     * retransforming only when the weave footprint of the affected member changes. When
     * the two targets are equal the registry is left untouched, so republishing a rule
     * with the same target does not leak a refcount. Returns whether the registry was
     * mutated (so callers can roll back).
     */
    private boolean applyTargetUpdate(Class<?> declaringClass, EnhancementTarget previous, EnhancementTarget next) {
        if (Objects.equals(previous, next)) {
            return false;
        }
        if (previous != null) {
            applyTargetTransition(declaringClass, previous, false);
        }
        if (next != null) {
            applyTargetTransition(declaringClass, next, true);
        }
        return true;
    }

    /**
     * Register or unregister a single target and retransform its declaring class when
     * the footprint (method Advice / constructor Advice / call-site keys) for that
     * member changes. This preserves V1.2 behaviour: a second rule on an already
     * instrumented method does not re-weave, while the first or last one does.
     */
    private void applyTargetTransition(Class<?> declaringClass, EnhancementTarget target, boolean add) {
        String className = declaringClass.getName();
        String classLoaderId = ClassLoaderIdentity.idOf(declaringClass.getClassLoader());
        String memberName = target.method().methodName();
        String descriptor = target.method().methodDescriptor();
        DefaultInstrumentationRegistry.WeaveFootprint before =
                instrumentationRegistry.footprintOf(className, classLoaderId, memberName, descriptor);
        if (add) {
            instrumentationRegistry.register(target);
        } else {
            instrumentationRegistry.unregister(target);
        }
        DefaultInstrumentationRegistry.WeaveFootprint after =
                instrumentationRegistry.footprintOf(className, classLoaderId, memberName, descriptor);
        if (!before.equals(after)) {
            transformerManager.retransform(declaringClass);
        }
    }

    private boolean isActive(MockRule rule) {
        return rule.enabled()
                && rule.percentage() > 0
                && (rule.expireAt() <= 0 || rule.expireAt() > System.currentTimeMillis());
    }

    private PublishedRule requireRule(String ruleId) {
        PublishedRule publishedRule = publishedRules.get(ruleId);
        if (publishedRule == null) {
            throw new IllegalArgumentException("Rule not found: " + ruleId);
        }
        return publishedRule;
    }

    private boolean matchesClass(PublishedRule publishedRule, String classIdOrName) {
        if (classIdOrName == null || classIdOrName.isBlank()) {
            throw new IllegalArgumentException("classId is required");
        }
        MethodKey methodKey = publishedRule.methodKey();
        String resolvedClassId = loadedClassRepository.classId(methodKey.className(), methodKey.classLoaderId());
        return classIdOrName.equals(resolvedClassId) || classIdOrName.equals(methodKey.className());
    }

    private void cleanupExpiredRules() {
        if (state.get() == AgentState.DEGRADED || state.get() == AgentState.STOPPING
                || state.get() == AgentState.STOPPED) {
            return;
        }
        long now = System.currentTimeMillis();
        publishedRules.values().stream()
                .filter(rule -> rule.rule().expireAt() > 0 && rule.rule().expireAt() <= now)
                .map(rule -> rule.rule().id())
                .toList()
                .forEach(ruleId -> {
                    try {
                        remove(ruleId, "ttl-cleanup");
                    } catch (RuntimeException e) {
                        eventBuffer.record("rule.cleanup.failed", "ttl-cleanup", ruleId, null, e.getMessage());
                    }
                });
    }

    private RuleInfo toRuleInfo(PublishedRule publishedRule) {
        MockRule rule = publishedRule.rule();
        MethodKey methodKey = publishedRule.methodKey();
        return new RuleInfo(
                rule.id(),
                rule.version(),
                rule.name(),
                rule.description(),
                loadedClassRepository.classId(methodKey.className(), methodKey.classLoaderId()),
                methodKey.className(),
                methodKey.classLoaderId(),
                methodKey.methodName(),
                methodKey.methodDescriptor(),
                rule.phase(),
                rule.priority(),
                rule.percentage(),
                rule.maxHits(),
                rule.expireAt(),
                rule.failOpen(),
                rule.enabled(),
                publishedRule.compiledRule().hits(),
                publishedRule.compiledRule().errors(),
                publishedRule.compiledRule().locked(),
                rule.scriptHash()
        );
    }

    private String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "localhost";
        }
    }

    private void requireOperationalForPublish() {
        AgentState current = state.get();
        if (current != AgentState.ACTIVE && current != AgentState.DISABLED) {
            throw new IllegalStateException("Agent does not accept rule publication while state=" + current);
        }
    }

    private void enterDegraded(String message) {
        // V1.7 M1-C §8.3: coordinate with the snapshot write lock (reentrant from the locked mutation
        // paths that call this; start() is the only unlocked caller and runs before any snapshot).
        snapshotLock.writeLock().lock();
        try {
            globallyEnabled = false;
            ruleDispatcher.enabled(false);
            KairoBridge.uninstall();
            state.set(AgentState.DEGRADED);
        } finally {
            snapshotLock.writeLock().unlock();
        }
        eventBuffer.record("agent.degraded", "system", null, null, message);
    }
}
