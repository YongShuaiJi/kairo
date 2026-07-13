package com.example.kairo.agent.core;

import com.example.kairo.api.MockRule;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.ClassSelector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V1.5 &sect;4.4: pre-registration of enhancement rules for classes that are not yet
 * loaded.
 *
 * <p>A rule may target a class the JVM has not loaded yet. There is no stable
 * {@code classLoaderId} to carry because the defining loader does not exist yet, so the
 * rule is pre-registered against a fuzzy {@link ClassSelector} (binary name plus optional
 * loader-class / module / code-source narrowing). When the agent later observes a class
 * whose identity satisfies the selector, the rule is <em>materialized</em>: the V1.4 rule
 * chain is built for the actual loaded class and the real {@link ClassIdentity} is
 * reported for audit (&sect;4.4: "Transformer 在首次加载匹配类型时生成 V1.4 规则链并
 * 报告实际 identity").
 *
 * <p>A fuzzy selector (no {@code classLoaderId}) may match classes in more than one
 * ClassLoader. In that case the registry refuses to materialize unless the selector
 * declared {@link ClassSelector#allMatch()}, recording an {@link AmbiguousMatch} instead
 * (&sect;4.1 / &sect;4.4: "匹配多项时按策略拒绝或显式 all-match"). The agent never
 * silently enhances the wrong target.
 *
 * <p>This registry is the bookkeeping half; {@code AgentRuntime.pollPendingMatches()} is
 * the driver that scans loaded classes, asks the registry what matches, materializes the
 * rule and records the resolved identity. Running materialization on the agent's cleanup
 * executor (not the JVM class-loading thread) keeps the transformer free of re-entrancy.
 */
public final class PendingEnhancementRegistry {

    /** A rule pre-registered against a fuzzy selector, awaiting first load. */
    public record PendingEntry(String ruleId, ClassSelector selector, MockRule rule,
                               String actor, long registeredAtMillis) {
        public PendingEntry {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(rule, "rule");
            actor = actor == null ? "system" : actor;
        }
    }

    /** Audit record that a pending rule materialized against a concrete loaded class. */
    public record ResolvedEntry(String ruleId, ClassIdentity actualIdentity,
                                String actualClassLoaderId, List<String> notes,
                                long resolvedAtMillis) {
    }

    /** Audit record that a fuzzy selector matched more than one loader and was refused. */
    public record AmbiguousMatch(String ruleId, String className, List<String> candidateLoaderIds,
                                 long observedAtMillis) {
    }

    private final ConcurrentHashMap<String, PendingEntry> pending = new ConcurrentHashMap<>();
    private final List<ResolvedEntry> resolved = Collections.synchronizedList(new ArrayList<>());
    private final List<AmbiguousMatch> ambiguous = Collections.synchronizedList(new ArrayList<>());

    /** Pre-register a rule against a selector. A duplicate rule id replaces the prior entry. */
    public void register(ClassSelector selector, MockRule rule, String actor, long registeredAtMillis) {
        PendingEntry entry = new PendingEntry(rule.id(), selector, rule, actor, registeredAtMillis);
        pending.put(rule.id(), entry);
    }

    /** Cancel a pending rule (e.g. after it materialized or was unloaded). Returns true if it was present. */
    public boolean cancel(String ruleId) {
        return ruleId != null && pending.remove(ruleId) != null;
    }

    /** All pending rules awaiting first load. */
    public List<PendingEntry> pending() {
        return List.copyOf(pending.values());
    }

    /** Audit log of rules that materialized against a concrete class. */
    public List<ResolvedEntry> resolved() {
        synchronized (resolved) {
            return List.copyOf(resolved);
        }
    }

    /** Audit log of fuzzy selectors that matched more than one loader and were refused. */
    public List<AmbiguousMatch> ambiguousMatches() {
        synchronized (ambiguous) {
            return List.copyOf(ambiguous);
        }
    }

    /** Number of rules still awaiting first load. */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * V1.5 &sect;4.4: whether any pending selector names exactly {@code binaryName}. Used by the
     * first-load observer to skip the (rare) hand-off to the cleanup executor when the just-loaded
     * class matches no pending rule, keeping the class-load hot path cheap while pending rules exist.
     */
    public boolean hasPendingForClass(String binaryName) {
        if (binaryName == null || pending.isEmpty()) {
            return false;
        }
        for (PendingEntry entry : pending.values()) {
            if (binaryName.equals(entry.selector().className())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code selector} matches a loaded class described by its observed identity
     * fields. The binary name must always match; the optional selector fields narrow the
     * match by loader id, loader class, module and code source.
     */
    public boolean matches(ClassSelector selector, String binaryName, String classLoaderId,
                           String loaderClassName, String moduleName, String codeSource) {
        if (selector == null || !selector.className().equals(binaryName)) {
            return false;
        }
        if (selector.classLoaderId() != null && !selector.classLoaderId().equals(classLoaderId)) {
            return false;
        }
        if (selector.loaderClassName() != null && !selector.loaderClassName().equals(loaderClassName)) {
            return false;
        }
        if (selector.moduleName() != null && !selector.moduleName().equals(moduleName)) {
            return false;
        }
        if (selector.codeSource() != null && !selector.codeSource().equals(codeSource)) {
            return false;
        }
        return true;
    }

    /** Record that {@code ruleId} materialized against {@code actualIdentity} (audit). */
    public void markResolved(String ruleId, ClassIdentity actualIdentity,
                             String actualClassLoaderId, List<String> notes, long resolvedAtMillis) {
        resolved.add(new ResolvedEntry(ruleId, actualIdentity, actualClassLoaderId,
                notes == null ? List.of() : List.copyOf(notes), resolvedAtMillis));
    }

    /** Record that {@code ruleId}'s fuzzy selector matched multiple loaders and was refused. */
    public void markAmbiguous(String ruleId, String className, List<String> candidateLoaderIds,
                              long observedAtMillis) {
        ambiguous.add(new AmbiguousMatch(ruleId, className,
                List.copyOf(candidateLoaderIds), observedAtMillis));
    }

    /** Find the pending entry for a rule id, if any. */
    public java.util.Optional<PendingEntry> pendingFor(String ruleId) {
        return java.util.Optional.ofNullable(ruleId == null ? null : pending.get(ruleId));
    }

    /** Clear all state; used by tests and agent close. */
    public void clear() {
        pending.clear();
        synchronized (resolved) {
            resolved.clear();
        }
        synchronized (ambiguous) {
            ambiguous.clear();
        }
    }
}
