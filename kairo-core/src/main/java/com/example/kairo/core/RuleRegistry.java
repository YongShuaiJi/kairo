package com.example.kairo.core;

import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.EnhancementTarget;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.RuleChainRevision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authoritative rule-chain store, keyed at runtime by {@link MethodKey} with
 * each method holding an immutable {@link MethodChainSnapshot} behind an atomic
 * reference.
 *
 * <p>V1.4 promotes the authoritative logical key from a bare {@code MethodKey}
 * to an {@link EnhancementTarget} (method + location + call-site selector): each
 * chain within a method's bundle is addressed by its target, carries its own
 * revision and content hash, and is replaced wholesale via CAS &mdash; never
 * mutated rule-by-rule in place. The {@code MethodKey} remains only as the
 * runtime method-level index so the dispatcher can find the bundle in one
 * {@code ConcurrentHashMap} lookup.
 *
 * <p>Legacy {@code rules(MethodKey)} / {@code addRule} / {@code removeRule}
 * accessors are retained as a compatibility adapter that projects the immutable
 * chains onto the V1.2 {@link RuleSet} shape; new code reads
 * {@link #methodChains(MethodKey)} and {@link #chain(MethodKey, EnhancementLocation,
 * CallSiteSelector)} directly.
 */
public final class RuleRegistry {

    private final ConcurrentHashMap<MethodKey, AtomicReference<MethodChainSnapshot>> methods = new ConcurrentHashMap<>();
    private final AtomicLong localRevision = new AtomicLong();

    // -------------------------------------------------------- V1.4 chain access

    /** Frozen per-method bundle read once per invocation by the dispatcher. */
    public MethodChainSnapshot methodChains(MethodKey methodKey) {
        AtomicReference<MethodChainSnapshot> ref = methods.get(methodKey);
        return ref == null ? MethodChainSnapshot.EMPTY : ref.get();
    }

    /** The chain for one target within a method's bundle, or empty. */
    public RuleChainSnapshot chain(MethodKey methodKey, EnhancementLocation location, CallSiteSelector selector) {
        return methodChains(methodKey).chain(location, selector);
    }

    /**
     * CAS-replace one target's chain with a fully-built snapshot. Returns
     * {@code false} when the expected snapshot no longer matches the current
     * chain (revision conflict), so the caller can retry or report
     * {@code STALE_COMMAND}.
     */
    public boolean casReplace(MethodKey methodKey, EnhancementTarget target,
                              RuleChainSnapshot expected, RuleChainSnapshot next) {
        AtomicReference<MethodChainSnapshot> ref = methods.computeIfAbsent(methodKey,
                ignored -> new AtomicReference<>(MethodChainSnapshot.EMPTY));
        while (true) {
            MethodChainSnapshot current = ref.get();
            RuleChainSnapshot currentChain = current.chain(target.location(), target.callSiteSelector());
            if (expected != null && !sameChain(currentChain, expected)) {
                return false;
            }
            MethodChainSnapshot updated = current.with(target, next);
            if (ref.compareAndSet(current, updated)) {
                if (updated.isEmpty()) {
                    methods.remove(methodKey, ref);
                }
                return true;
            }
        }
    }

    /** Unconditional replace of one target's chain (management / rollback path). */
    public void replace(MethodKey methodKey, EnhancementTarget target, RuleChainSnapshot snapshot) {
        casReplace(methodKey, target, null, snapshot);
    }

    /** Replace the whole method bundle (bulk reset path). */
    public void replaceMethod(MethodKey methodKey, MethodChainSnapshot snapshot) {
        AtomicReference<MethodChainSnapshot> ref = methods.computeIfAbsent(methodKey,
                ignored -> new AtomicReference<>(MethodChainSnapshot.EMPTY));
        MethodChainSnapshot next = snapshot == null ? MethodChainSnapshot.EMPTY : snapshot;
        ref.set(next);
        if (next.isEmpty()) {
            methods.remove(methodKey, ref);
        }
    }

    /** All method bundles, for reconciliation and diagnostics. */
    public Map<MethodKey, MethodChainSnapshot> snapshot() {
        Map<MethodKey, MethodChainSnapshot> out = new LinkedHashMap<>();
        methods.forEach((key, ref) -> out.put(key, ref.get()));
        return Map.copyOf(out);
    }

    /** All chains across all methods, keyed by target. */
    public Map<EnhancementTarget, RuleChainSnapshot> allChains() {
        Map<EnhancementTarget, RuleChainSnapshot> out = new LinkedHashMap<>();
        methods.forEach((key, ref) -> ref.get().targets().forEach(t -> out.put(t, ref.get().chain(t.location(), t.callSiteSelector()))));
        return out;
    }

    public void clear() {
        methods.clear();
    }

    /**
     * V1.5 &sect;3.2: remove every chain whose {@link MethodKey} belongs to the
     * collected loader. Called by the {@code ClassLoaderRepository} cleaner after
     * the loader is garbage-collected, so a loader that is reclaimed after its
     * rules were unloaded does not leave orphan chain state behind. Rules that
     * are still active hold a strong {@code Class} reference via {@link MethodKey}
     * and therefore prevent the loader from being collected in the first place,
     * so this path only ever clears already-unloaded residual state.
     *
     * @return the number of method keys removed
     */
    public int clearForLoader(String classLoaderId) {
        if (classLoaderId == null) {
            return 0;
        }
        int removed = 0;
        var it = methods.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (classLoaderId.equals(entry.getKey().classLoaderId())) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    // -------------------------------------------------------- legacy compat adapter

    /** Flattened V1.2 {@link RuleSet} view of a method's chains. */
    public RuleSet rules(MethodKey methodKey) {
        return methodChains(methodKey).toRuleSet();
    }

    /**
     * Add or replace one rule within its target's chain, rebuilding the chain
     * canonically. Compatibility adapter for callers that predate the fenced
     * {@code applyRuleChain} path; assigns a local revision.
     */
    public RuleSet addRule(MethodKey methodKey, CompiledRule compiledRule) {
        EnhancementTarget target = targetOf(methodKey, compiledRule.rule());
        AtomicReference<MethodChainSnapshot> ref = methods.computeIfAbsent(methodKey,
                ignored -> new AtomicReference<>(MethodChainSnapshot.EMPTY));
        while (true) {
            MethodChainSnapshot current = ref.get();
            RuleChainSnapshot currentChain = current.chain(target.location(), target.callSiteSelector());
            List<CompiledRule> rules = new ArrayList<>(currentChain.rules().stream()
                    .filter(r -> !r.rule().id().equals(compiledRule.rule().id()))
                    .toList());
            rules.add(compiledRule);
            RuleChainSnapshot next = buildSnapshot(rules, target, currentChain);
            MethodChainSnapshot updated = current.with(target, next);
            if (ref.compareAndSet(current, updated)) {
                return updated.toRuleSet();
            }
        }
    }

    public RuleSet removeRule(MethodKey methodKey, String ruleId) {
        AtomicReference<MethodChainSnapshot> ref = methods.get(methodKey);
        if (ref == null) {
            return RuleSet.empty();
        }
        while (true) {
            MethodChainSnapshot current = ref.get();
            boolean changed = false;
            Map<EnhancementTarget, RuleChainSnapshot> nextChains = new LinkedHashMap<>();
            for (EnhancementTarget target : current.targets()) {
                RuleChainSnapshot chain = current.chain(target.location(), target.callSiteSelector());
                List<CompiledRule> filtered = chain.rules().stream()
                        .filter(r -> !r.rule().id().equals(ruleId))
                        .toList();
                if (filtered.size() != chain.rules().size()) {
                    changed = true;
                    RuleChainSnapshot rebuilt = filtered.isEmpty()
                            ? RuleChainSnapshot.empty()
                            : buildSnapshot(filtered, target, chain);
                    nextChains.put(target, rebuilt);
                } else {
                    nextChains.put(target, chain);
                }
            }
            if (!changed) {
                return current.toRuleSet();
            }
            MethodChainSnapshot updated = rebuildBundle(current, nextChains);
            if (ref.compareAndSet(current, updated)) {
                if (updated.isEmpty()) {
                    methods.remove(methodKey, ref);
                }
                return updated.toRuleSet();
            }
        }
    }

    public RuleSet restoreRule(MethodKey methodKey, String ruleId, CompiledRule previousRule) {
        if (previousRule == null) {
            return removeRule(methodKey, ruleId);
        }
        AtomicReference<MethodChainSnapshot> ref = methods.computeIfAbsent(methodKey,
                ignored -> new AtomicReference<>(MethodChainSnapshot.EMPTY));
        EnhancementTarget target = targetOf(methodKey, previousRule.rule());
        while (true) {
            MethodChainSnapshot current = ref.get();
            RuleChainSnapshot currentChain = current.chain(target.location(), target.callSiteSelector());
            List<CompiledRule> rules = new ArrayList<>(currentChain.rules().stream()
                    .filter(r -> !r.rule().id().equals(ruleId))
                    .toList());
            rules.add(previousRule);
            RuleChainSnapshot next = buildSnapshot(rules, target, currentChain);
            MethodChainSnapshot updated = current.with(target, next);
            if (ref.compareAndSet(current, updated)) {
                return updated.toRuleSet();
            }
        }
    }

    /** Legacy whole-method replace from a {@link RuleSet}. */
    public void replace(MethodKey methodKey, RuleSet ruleSet) {
        if (ruleSet == null || ruleSet.isEmpty()) {
            AtomicReference<MethodChainSnapshot> ref = methods.get(methodKey);
            if (ref != null) {
                ref.set(MethodChainSnapshot.EMPTY);
                methods.remove(methodKey, ref);
            }
            return;
        }
        Map<EnhancementLocation, List<CompiledRule>> byLocation = new LinkedHashMap<>();
        for (CompiledRule rule : ruleSet.all()) {
            byLocation.computeIfAbsent(rule.rule().effectiveLocation(), k -> new ArrayList<>()).add(rule);
        }
        Map<EnhancementTarget, RuleChainSnapshot> chains = new LinkedHashMap<>();
        for (Map.Entry<EnhancementLocation, List<CompiledRule>> entry : byLocation.entrySet()) {
            EnhancementLocation location = entry.getKey();
            List<CompiledRule> rules = canonicalize(entry.getValue());
            EnhancementTarget target = targetOf(methodKey, location, rules.get(0).rule().callSiteSelector());
            RuleChainSnapshot snapshot = RuleChainSnapshot.of(
                    new RuleChainRevision(localRevision.incrementAndGet(), ""),
                    rules, target, 0L, "", 0L);
            chains.put(target, snapshot);
        }
        replaceMethod(methodKey, MethodChainSnapshot.of(chains));
    }

    // -------------------------------------------------------- internals

    private RuleChainSnapshot buildSnapshot(List<CompiledRule> rules, EnhancementTarget target,
                                            RuleChainSnapshot previous) {
        List<CompiledRule> canonical = canonicalize(rules);
        if (canonical.isEmpty()) {
            return RuleChainSnapshot.empty();
        }
        RuleChainRevision revision = new RuleChainRevision(localRevision.incrementAndGet(), "");
        return RuleChainSnapshot.of(revision, canonical, target,
                previous.transformationRevision(), previous.transformationHash(), 0L);
    }

    private MethodChainSnapshot rebuildBundle(MethodChainSnapshot current,
                                              Map<EnhancementTarget, RuleChainSnapshot> nextChains) {
        Map<EnhancementTarget, RuleChainSnapshot> merged = new LinkedHashMap<>();
        for (EnhancementTarget target : current.targets()) {
            RuleChainSnapshot override = nextChains.get(target);
            merged.put(target, override != null ? override
                    : current.chain(target.location(), target.callSiteSelector()));
        }
        return MethodChainSnapshot.of(merged);
    }

    private static boolean sameChain(RuleChainSnapshot a, RuleChainSnapshot b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.revision().equals(b.revision());
    }

    private static List<CompiledRule> canonicalize(List<CompiledRule> rules) {
        List<CompiledRule> copy = new ArrayList<>(rules);
        copy.sort(Comparator
                .comparingInt((CompiledRule r) -> r.rule().priority()).reversed()
                .thenComparingLong(r -> r.rule().createdAt())
                .thenComparing(r -> r.rule().id()));
        return List.copyOf(copy);
    }

    private static EnhancementTarget targetOf(MethodKey key, com.example.kairo.api.MockRule rule) {
        return targetOf(key, rule.effectiveLocation(), rule.callSiteSelector());
    }

    private static EnhancementTarget targetOf(MethodKey key, EnhancementLocation location, CallSiteSelector selector) {
        MethodSelector method = new MethodSelector(key.className(), key.classLoaderId(),
                key.methodName(), key.methodDescriptor());
        return selector != null ? EnhancementTarget.callSite(method, location, selector)
                : EnhancementTarget.of(method, location);
    }
}
