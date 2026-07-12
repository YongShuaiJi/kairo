package com.example.kairo.core;

import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.EnhancementTarget;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Frozen, immutable view of every rule chain attached to one method key.
 *
 * <p>The dispatcher reads a {@code MethodChainSnapshot} <em>once</em> at enter
 * and reuses it through exit, so a chain published mid-invocation cannot affect
 * the in-flight execution (&sect;4.1: "每次调用固定读取一个链快照"). Within the
 * bundle, chains are keyed by their authoritative {@link EnhancementTarget};
 * the {@link MethodKey} is only the runtime method-level index used to find the
 * bundle cheaply on the hot path.
 */
public final class MethodChainSnapshot {

    public static final MethodChainSnapshot EMPTY =
            new MethodChainSnapshot(Map.of());

    private final Map<EnhancementTarget, RuleChainSnapshot> chains;

    private MethodChainSnapshot(Map<EnhancementTarget, RuleChainSnapshot> chains) {
        this.chains = chains;
    }

    public static MethodChainSnapshot of(Map<EnhancementTarget, RuleChainSnapshot> chains) {
        if (chains == null || chains.isEmpty()) {
            return EMPTY;
        }
        return new MethodChainSnapshot(Map.copyOf(chains));
    }

    public boolean isEmpty() {
        return chains.isEmpty();
    }

    public Collection<RuleChainSnapshot> snapshots() {
        return chains.values();
    }

    public Collection<EnhancementTarget> targets() {
        return chains.keySet();
    }

    /**
     * The chain for a specific location and (for call-site locations) call-site
     * selector, or {@link RuleChainSnapshot#empty()} when no rule is attached.
     */
    public RuleChainSnapshot chain(EnhancementLocation location, CallSiteSelector selector) {
        for (Map.Entry<EnhancementTarget, RuleChainSnapshot> entry : chains.entrySet()) {
            EnhancementTarget target = entry.getKey();
            if (target.location() != location) {
                continue;
            }
            if (location.isCallSiteLocation()) {
                CallSiteSelector existing = target.callSiteSelector();
                if (existing != null && selector != null && existing.coreEquals(selector)) {
                    return entry.getValue();
                }
            } else {
                return entry.getValue();
            }
        }
        return RuleChainSnapshot.empty();
    }

    public boolean hasChain(EnhancementLocation location, CallSiteSelector selector) {
        return !chain(location, selector).isEmpty();
    }

    /**
     * Replace (or insert/remove) one target's chain, returning a new immutable
     * bundle. Used by {@link RuleRegistry} CAS to publish a whole chain without
     * mutating in place.
     */
    public MethodChainSnapshot with(EnhancementTarget target, RuleChainSnapshot snapshot) {
        Map<EnhancementTarget, RuleChainSnapshot> next = new LinkedHashMap<>(chains);
        if (snapshot == null || snapshot.isEmpty()) {
            next.remove(target);
        } else {
            next.put(target, snapshot);
        }
        return of(next);
    }

    /**
     * Legacy projection: flatten every chain's rules into a single
     * {@link RuleSet} bucketed by location, for compatibility with callers that
     * predate V1.4. New code reads chains directly via {@link #chain}.
     */
    public RuleSet toRuleSet() {
        java.util.List<CompiledRule> all = new java.util.ArrayList<>();
        for (RuleChainSnapshot snapshot : chains.values()) {
            all.addAll(snapshot.rules());
        }
        return new RuleSet(all);
    }

    public List<CompiledRule> allRules() {
        java.util.List<CompiledRule> all = new java.util.ArrayList<>();
        for (RuleChainSnapshot snapshot : chains.values()) {
            all.addAll(snapshot.rules());
        }
        return List.copyOf(all);
    }
}
