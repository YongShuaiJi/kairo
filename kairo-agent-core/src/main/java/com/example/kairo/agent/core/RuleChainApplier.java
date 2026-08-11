package com.example.kairo.agent.core;

import com.example.kairo.api.ApplyChainRequest;
import com.example.kairo.api.ApplyChainResult;
import com.example.kairo.api.ApplyChainStatus;
import com.example.kairo.api.ChainDesiredState;
import com.example.kairo.api.ConflictReport;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.EnhancementTarget;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.ReconcileResult;
import com.example.kairo.api.RuleChainCanonicalizer;
import com.example.kairo.api.RuleChainEntry;
import com.example.kairo.api.RuleChainRevision;
import com.example.kairo.api.RuleChainSpec;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationResult;
import com.example.kairo.api.bytecode.TransformationStatus;
import com.example.kairo.agent.core.bytecode.ClassIdentities;
import com.example.kairo.agent.core.script.AgentScriptCompilerFactory;
import com.example.kairo.core.ChainEntryProjector;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.CompiledRule;
import com.example.kairo.core.ConflictAnalyzer;
import com.example.kairo.core.MethodDescriptor;
import com.example.kairo.core.MethodKey;
import com.example.kairo.core.RuleChainSnapshot;
import com.example.kairo.core.RuleRegistry;
import com.example.kairo.groovy.CompiledMockScript;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V1.4 fenced rule-chain application (&sect;4.2 / &sect;3.3).
 *
 * <p>Compiles the entire desired chain before mutating anything (any compile
 * failure leaves the current snapshot untouched), runs static conflict
 * analysis, retransforms only when the enhancement footprint changes (empty
 * &harr; non-empty or position change), verifies the transformation via the V1.1
 * actual-bytecode result, and only then atomically CAS-replaces the running
 * chain snapshot. Commands are fenced by expected revision (stale &rarr;
 * {@link ApplyChainStatus#STALE_COMMAND}) and de-duplicated by idempotency key
 * (duplicate &rarr; {@link ApplyChainStatus#IDEMPOTENT_REPLAY}).
 *
 * <p>Unload is a command whose desired spec is {@link ChainDesiredState#EMPTY}:
 * the target is unregistered and the class retransformed with the remaining
 * Kairo plan, never a coarse {@code RESET_ALL}.
 */
public final class RuleChainApplier {

    private final RuleRegistry ruleRegistry;
    private final AgentScriptCompilerFactory scriptCompilerFactory;
    private final LoadedClassRepository loadedClassRepository;
    private final DefaultInstrumentationRegistry instrumentationRegistry;
    private final ByteBuddyTransformerManager transformerManager;
    private final RuntimeEventBuffer eventBuffer;
    private final AtomicLong transformationRevision = new AtomicLong();
    private final ConcurrentHashMap<String, ApplyChainResult> resultsByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ApplyChainResult> resultsByCommand = new ConcurrentHashMap<>();
    /** Bound on the idempotency cache: the platform only re-sends within a short retry window. */
    private static final int IDEMPOTENCY_CACHE_LIMIT = 4096;

    public RuleChainApplier(RuleRegistry ruleRegistry,
                            AgentScriptCompilerFactory scriptCompilerFactory,
                            LoadedClassRepository loadedClassRepository,
                            DefaultInstrumentationRegistry instrumentationRegistry,
                            ByteBuddyTransformerManager transformerManager,
                            RuntimeEventBuffer eventBuffer) {
        this.ruleRegistry = Objects.requireNonNull(ruleRegistry, "ruleRegistry");
        this.scriptCompilerFactory = Objects.requireNonNull(scriptCompilerFactory, "scriptCompilerFactory");
        this.loadedClassRepository = Objects.requireNonNull(loadedClassRepository, "loadedClassRepository");
        this.instrumentationRegistry = Objects.requireNonNull(instrumentationRegistry, "instrumentationRegistry");
        this.transformerManager = Objects.requireNonNull(transformerManager, "transformerManager");
        this.eventBuffer = eventBuffer;
    }

    public ApplyChainResult applyChain(ApplyChainRequest request) {
        Objects.requireNonNull(request, "request");
        // 1. Idempotency: a duplicate key returns the previous result.
        ApplyChainResult cached = resultsByKey.get(request.idempotencyKey());
        if (cached != null) {
            return ApplyChainResult.replay(request.commandId(), cached.applied(), cached.actualHash());
        }
        // A duplicate command id (different key) also replays.
        ApplyChainResult byCommand = resultsByCommand.get(request.commandId());
        if (byCommand != null) {
            return ApplyChainResult.replay(request.commandId(), byCommand.applied(), byCommand.actualHash());
        }

        EnhancementTarget target = request.target();
        EnhancementLocation location = target.location();

        // 2. Resolve the live class + method key.
        Class<?> declaringClass;
        MethodKey methodKey;
        try {
            String classId = loadedClassRepository.classId(
                    target.method().className(), target.method().classLoaderId());
            declaringClass = loadedClassRepository.resolveClass(classId);
            if (declaringClass == null) {
                return finish(request, ApplyChainResult.failed(
                        request.commandId(), ApplyChainStatus.TARGET_NOT_FOUND,
                        RuleChainRevision.initial(), "class not loaded: " + target.method().className()));
            }
            String memberName = location.isConstructorLocation() ? "<init>" : target.method().methodName();
            methodKey = new MethodKey(declaringClass, memberName, target.method().methodDescriptor());
        } catch (RuntimeException e) {
            return finish(request, ApplyChainResult.failed(
                    request.commandId(), ApplyChainStatus.TARGET_NOT_FOUND,
                    RuleChainRevision.initial(), e.getMessage()));
        }

        // 3. Fencing: the expected revision must match the actual applied revision.
        RuleChainSnapshot current = ruleRegistry.chain(methodKey, location, target.callSiteSelector());
        if (request.expected().value() != current.revision().value()) {
            return finish(request, ApplyChainResult.stale(request.commandId(), current.revision(),
                    "expected revision " + request.expected().value()
                            + " but actual is " + current.revision().value()));
        }

        RuleChainSpec desired = request.desired();

        // 4. NO_OP when the desired content already matches.
        if (desired.hash().equals(current.hash()) && current.isEmpty() == desired.isEmpty()) {
            return finish(request, new ApplyChainResult(ApplyChainStatus.NO_OP, request.commandId(),
                    current.revision(), current.hash(), "chain already at desired content", null));
        }

        // 5. Verify the carried rules project to the desired hash (content integrity).
        List<MockRule> rules = request.rules();
        List<RuleChainEntry> entries = projectEntries(rules);
        String computedHash = RuleChainCanonicalizer.canonicalHash(target, entries, desired.desiredState());
        if (!computedHash.equals(desired.hash())) {
            return finish(request, ApplyChainResult.failed(request.commandId(), ApplyChainStatus.REJECTED,
                    current.revision(), "desired hash mismatch: computed=" + computedHash
                            + " supplied=" + desired.hash()));
        }

        // 6. Static conflict analysis.
        ConflictReport report = new ConflictAnalyzer().analyze(rules);
        if (report.hasBlocking()) {
            return finish(request, ApplyChainResult.rejected(request.commandId(), current.revision(), report));
        }

        // 7. Coexistence safety: refuse to retransform when a foreign transformer
        //    ahead of Kairo cannot be safely re-run.
        String unsafe = transformerManager.coexistenceUnsafe(declaringClass);
        if (unsafe != null) {
            return finish(request, ApplyChainResult.failed(request.commandId(),
                    ApplyChainStatus.COEXISTENCE_UNSAFE, current.revision(), unsafe));
        }

        // 8. Compile the entire chain before any mutation.
        List<CompiledRule> compiledRules;
        try {
            compiledRules = compileChain(declaringClass, location, rules);
        } catch (RuntimeException e) {
            return finish(request, ApplyChainResult.failed(request.commandId(),
                    ApplyChainStatus.COMPILE_FAILED, current.revision(), e.getMessage()));
        }

        // 9. Footprint change + retransform + V1.1 bytes verification.
        long newTransformationRevision = current.transformationRevision();
        String newTransformationHash = current.transformationHash();
        boolean retransformed = false;
        PlanChange planChange;
        try {
            planChange = applyPlanAndMeasure(
                    declaringClass, target, current.isEmpty(), desired.desiredState());
        } catch (RuntimeException e) {
            releaseCompiledRules(compiledRules);
            return finish(request, ApplyChainResult.failed(request.commandId(),
                    ApplyChainStatus.TRANSFORM_FAILED, current.revision(), e.getMessage()));
        }
        boolean footprintChanged = planChange.footprintChanged();
        if (footprintChanged) {
            try {
                List<TransformationResult> results = transformerManager.retransform(declaringClass);
                TransformationResult result = findResult(results, declaringClass);
                // An EMPTY unload leaves no Kairo target registered, so Kairo's transformer
                // ignores the class during retransform and produces no result entry: that is
                // a successful removal of Kairo advice, not a verification failure. Only an
                // explicit FAILED result (or a thrown exception) rolls back.
                if (result != null && result.status() != TransformationStatus.SUCCEEDED) {
                    releaseCompiledRules(compiledRules);
                    rollbackPlan(target, planChange.mutation());
                    transformerManager.retransform(declaringClass);
                    String msg = result.toString();
                    return finish(request, ApplyChainResult.failed(request.commandId(),
                            ApplyChainStatus.VERIFICATION_FAILED, current.revision(), msg));
                }
                newTransformationRevision = transformationRevision.incrementAndGet();
                newTransformationHash = result != null && result.outputHash() != null
                        ? result.outputHash() : "";
                retransformed = true;
            } catch (RuntimeException e) {
                releaseCompiledRules(compiledRules);
                rollbackPlan(target, planChange.mutation());
                transformerManager.retransform(declaringClass);
                return finish(request, ApplyChainResult.failed(request.commandId(),
                        ApplyChainStatus.TRANSFORM_FAILED, current.revision(), e.getMessage()));
            }
        }

        // 10. Atomically CAS-replace the running snapshot. The snapshot carries the
        // fenced desired hash (the content authority verified in step 5), not a
        // recomputed hash, so Platform desired and Agent actual reconcile by the
        // same content hash.
        RuleChainSnapshot next;
        try {
            next = desired.desiredState() == ChainDesiredState.EMPTY
                    ? RuleChainSnapshot.empty()
                    : new RuleChainSnapshot(
                            new RuleChainRevision(desired.revision(), desired.hash()),
                            desired.chainId(),
                            desired.hash(),
                            compiledRules, target, newTransformationRevision, newTransformationHash,
                            System.currentTimeMillis(), null);
        } catch (RuntimeException e) {
            releaseCompiledRules(compiledRules);
            rollbackPlan(target, planChange.mutation());
            if (footprintChanged) {
                transformerManager.retransform(declaringClass);
            }
            return finish(request, ApplyChainResult.failed(request.commandId(),
                    ApplyChainStatus.TRANSFORM_FAILED, current.revision(), e.getMessage()));
        }
        boolean swapped = ruleRegistry.casReplace(methodKey, target, current, next);
        if (!swapped) {
            // Lost a concurrent apply: always roll back this command's registry
            // ownership, even when another owner kept the weave footprint unchanged.
            releaseCompiledRules(compiledRules);
            rollbackPlan(target, planChange.mutation());
            if (footprintChanged) {
                transformerManager.retransform(declaringClass);
            }
            RuleChainSnapshot now = ruleRegistry.chain(methodKey, location, target.callSiteSelector());
            return finish(request, ApplyChainResult.stale(request.commandId(), now.revision(),
                    "concurrent apply won the CAS"));
        }

        if (eventBuffer != null) {
            eventBuffer.record("chain.apply", "system", desired.chainId(), methodKey.toString(),
                    "Applied chain revision " + desired.revision() + " hash=" + desired.hash().substring(0, 8)
                            + (retransformed ? " (retransformed)" : " (snapshot-only)"));
        }
        return finish(request, ApplyChainResult.applied(request.commandId(), next.revision(), next.hash()));
    }

    /** Reconcile the Agent's actual chain against the Platform's desired revision. */
    public ReconcileResult reconcile(EnhancementTarget target, RuleChainRevision desired) {
        Class<?> declaringClass;
        try {
            String classId = loadedClassRepository.classId(
                    target.method().className(), target.method().classLoaderId());
            declaringClass = loadedClassRepository.resolveClass(classId);
        } catch (RuntimeException e) {
            return ReconcileResult.unknown(desired);
        }
        if (declaringClass == null) {
            return ReconcileResult.unknown(desired);
        }
        String memberName = target.location().isConstructorLocation()
                ? "<init>" : target.method().methodName();
        MethodKey methodKey = new MethodKey(declaringClass, memberName, target.method().methodDescriptor());
        RuleChainSnapshot actual = ruleRegistry.chain(methodKey, target.location(), target.callSiteSelector());
        if (actual.revision().value() == desired.value() && actual.hash().equals(desired.hash())) {
            return ReconcileResult.inSync(actual.revision(), desired);
        }
        if (actual.revision().value() < desired.value()) {
            return ReconcileResult.behind(actual.revision(), desired);
        }
        return ReconcileResult.aheadOrDiverged(actual.revision(), desired);
    }

    /** The Agent's actual snapshot for a target (for diagnostics / reconciliation). */
    public RuleChainSnapshot snapshot(EnhancementTarget target) {
        Class<?> declaringClass;
        try {
            String classId = loadedClassRepository.classId(
                    target.method().className(), target.method().classLoaderId());
            declaringClass = loadedClassRepository.resolveClass(classId);
        } catch (RuntimeException e) {
            return RuleChainSnapshot.empty();
        }
        if (declaringClass == null) {
            return RuleChainSnapshot.empty();
        }
        String memberName = target.location().isConstructorLocation()
                ? "<init>" : target.method().methodName();
        MethodKey methodKey = new MethodKey(declaringClass, memberName, target.method().methodDescriptor());
        return ruleRegistry.chain(methodKey, target.location(), target.callSiteSelector());
    }

    /** Clear the idempotency cache (for tests / reset). */
    public void clearCache() {
        resultsByKey.clear();
        resultsByCommand.clear();
    }

    // -------------------------------------------------------- internals

    private ApplyChainResult finish(ApplyChainRequest request, ApplyChainResult result) {
        // Bound the idempotency cache so a long-lived agent does not accumulate every
        // command key ever sent. The platform's idempotency window is short (re-send on
        // retry/timeout); evicting the oldest quarter when the cap is reached keeps recent
        // replays recognisable while bounding memory.
        if (resultsByKey.size() >= IDEMPOTENCY_CACHE_LIMIT) {
            resultsByKey.clear();
            resultsByCommand.clear();
        }
        resultsByKey.putIfAbsent(request.idempotencyKey(), result);
        resultsByCommand.putIfAbsent(request.commandId(), result);
        return result;
    }

    private List<CompiledRule> compileChain(Class<?> declaringClass, EnhancementLocation location,
                                            List<MockRule> rules) {
        List<CompiledRule> compiled = new ArrayList<>(rules.size());
        try {
            for (MockRule rule : rules) {
                CompiledMockScript script;
                if (location.isConstructorLocation()) {
                    Constructor<?> ctor = resolveConstructor(declaringClass, rule);
                    script = scriptCompilerFactory.compile(ctor, rule);
                } else {
                    Method method = resolveMethod(declaringClass, rule);
                    script = scriptCompilerFactory.compile(method, rule);
                }
                MockRule withHash = rule.toBuilder().scriptHash(script.scriptHash()).build();
                compiled.add(new CompiledRule(withHash, script));
            }
            return compiled;
        } catch (RuntimeException e) {
            releaseCompiledRules(compiled);
            throw e;
        }
    }

    /** Release scripts compiled by an apply attempt that never transferred ownership to the registry. */
    private static void releaseCompiledRules(List<CompiledRule> compiledRules) {
        for (CompiledRule compiledRule : compiledRules) {
            try {
                compiledRule.script().releaseClassLoaderCaches();
            } catch (RuntimeException ignored) {
                // Best-effort cleanup must not replace the command's original failure.
            }
        }
    }

    private Method resolveMethod(Class<?> declaringClass, MockRule rule) {
        Class<?>[] params = com.example.kairo.core.MethodDescriptorTypes.parameterTypes(
                rule.target().methodDescriptor(), declaringClass.getClassLoader());
        try {
            return declaringClass.getDeclaredMethod(rule.target().methodName(), params);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("method not found: " + rule.target().methodName()
                    + rule.target().methodDescriptor(), e);
        }
    }

    private Constructor<?> resolveConstructor(Class<?> declaringClass, MockRule rule) {
        Class<?>[] params = com.example.kairo.core.MethodDescriptorTypes.parameterTypes(
                rule.target().methodDescriptor(), declaringClass.getClassLoader());
        try {
            return declaringClass.getDeclaredConstructor(params);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("constructor not found: " + rule.target().methodDescriptor(), e);
        }
    }

    /**
     * Apply the desired plan change (register for ACTIVE, unregister for EMPTY)
     * and report whether the method's weave footprint changed. The new plan
     * state is kept; callers roll it back on retransform or CAS failure. A
     * content-only ACTIVE-to-ACTIVE update does not mutate the ref-counted
     * registry and therefore does not trigger retransformation.
     */
    private PlanChange applyPlanAndMeasure(Class<?> declaringClass, EnhancementTarget target,
                                           boolean currentEmpty, ChainDesiredState desiredState) {
        String className = declaringClass.getName();
        String classLoaderId = ClassLoaderIdentity.idOf(declaringClass.getClassLoader());
        String memberName = target.location().isConstructorLocation()
                ? "<init>" : target.method().methodName();
        String descriptor = target.method().methodDescriptor();
        DefaultInstrumentationRegistry.WeaveFootprint before =
                instrumentationRegistry.footprintOf(className, classLoaderId, memberName, descriptor);

        // The registry is ref-counted by owner. A chain owns exactly one reference
        // while its snapshot is ACTIVE. Content-only ACTIVE -> ACTIVE updates must
        // not register again, otherwise the final EMPTY unload decrements only one
        // of the leaked references and leaves Kairo Advice woven into the class.
        PlanMutation mutation = PlanMutation.NONE;
        if (!currentEmpty && desiredState == ChainDesiredState.EMPTY) {
            instrumentationRegistry.unregister(target);
            mutation = PlanMutation.UNREGISTERED;
        } else if (currentEmpty && desiredState == ChainDesiredState.ACTIVE) {
            instrumentationRegistry.register(target);
            mutation = PlanMutation.REGISTERED;
        }
        DefaultInstrumentationRegistry.WeaveFootprint after =
                instrumentationRegistry.footprintOf(className, classLoaderId, memberName, descriptor);
        return new PlanChange(mutation, !before.equals(after));
    }

    private void rollbackPlan(EnhancementTarget target, PlanMutation mutation) {
        if (mutation == PlanMutation.UNREGISTERED) {
            instrumentationRegistry.register(target);
        } else if (mutation == PlanMutation.REGISTERED) {
            instrumentationRegistry.unregister(target);
        }
    }

    private enum PlanMutation {
        NONE,
        REGISTERED,
        UNREGISTERED
    }

    private record PlanChange(PlanMutation mutation, boolean footprintChanged) {
    }

    private TransformationResult findResult(List<TransformationResult> results, Class<?> clazz) {
        ClassIdentity identity = ClassIdentities.of(clazz);
        for (TransformationResult result : results) {
            if (result.classIdentity() != null && result.classIdentity().equals(identity)) {
                return result;
            }
        }
        return results.isEmpty() ? null : results.get(0);
    }

    private static List<RuleChainEntry> projectEntries(List<MockRule> rules) {
        List<RuleChainEntry> entries = new ArrayList<>(rules.size());
        for (MockRule rule : rules) {
            entries.add(RuleChainEntry.builder()
                    .ruleId(rule.id())
                    .version(rule.version())
                    .priority(rule.priority())
                    .createdAtMillis(rule.createdAt())
                    .scriptHash(rule.scriptHash() == null ? "" : rule.scriptHash())
                    .mutexGroup(rule.mutexGroup())
                    .build());
        }
        return entries;
    }
}
