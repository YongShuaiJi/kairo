package com.example.kairo.core;

import com.example.kairo.api.ChainDesiredState;
import com.example.kairo.api.EnhancementTarget;
import com.example.kairo.api.RuleChainCanonicalizer;
import com.example.kairo.api.RuleChainEntry;
import com.example.kairo.api.RuleChainRevision;

import java.util.List;
import java.util.Objects;

/**
 * The Agent's immutable actual rule-chain state for one enhancement target.
 *
 * <p>Business threads read a snapshot once per invocation and never participate
 * in compilation, transformation, database or network work. The
 * {@link RuleRegistry} holds snapshots behind atomic references and replaces
 * them wholesale on apply; a snapshot, once published, never mutates its
 * composition (the {@link CompiledRule} telemetry counters are live runtime
 * state, not chain composition).
 *
 * <p>The snapshot carries the applied revision and content hash so the Platform
 * can reconcile desired &harr; actual via {@link #revision()}, and the
 * transformation revision/hash so the JVM bytecode layer can be reconciled
 * separately.
 */
public final class RuleChainSnapshot {

    public static final RuleChainSnapshot EMPTY =
            new RuleChainSnapshot(RuleChainRevision.initial(), "", List.of(),
                    null, 0L, "", 0L, null);

    private final RuleChainRevision revision;
    private final String hash;
    private final List<CompiledRule> rules;
    private final EnhancementTarget target;
    private final long transformationRevision;
    private final String transformationHash;
    private final long applyTimeMillis;
    private final String degradedReason;

    public RuleChainSnapshot(RuleChainRevision revision, String hash, List<CompiledRule> rules,
                             EnhancementTarget target, long transformationRevision,
                             String transformationHash, long applyTimeMillis, String degradedReason) {
        this.revision = Objects.requireNonNull(revision, "revision");
        this.hash = hash == null ? "" : hash;
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        this.target = target;
        this.transformationRevision = transformationRevision;
        this.transformationHash = transformationHash == null ? "" : transformationHash;
        this.applyTimeMillis = applyTimeMillis;
        this.degradedReason = degradedReason;
    }

    public static RuleChainSnapshot empty() {
        return EMPTY;
    }

    /**
     * Build a snapshot from a compiled-rule list, computing the canonical hash
     * from the projected entries so it matches the Platform's desired hash for
     * the same content.
     */
    public static RuleChainSnapshot of(RuleChainRevision revision, List<CompiledRule> rules,
                                       EnhancementTarget target, long transformationRevision,
                                       String transformationHash, long applyTimeMillis) {
        List<RuleChainEntry> entries = ChainEntryProjector.project(rules);
        ChainDesiredState state = ChainEntryProjector.desiredStateFor(rules);
        String hash = target == null ? "" : RuleChainCanonicalizer.hash(target, entries, state);
        return new RuleChainSnapshot(revision, hash, rules, target, transformationRevision,
                transformationHash, applyTimeMillis, null);
    }

    public RuleChainRevision revision() {
        return revision;
    }

    public String hash() {
        return hash;
    }

    public List<CompiledRule> rules() {
        return rules;
    }

    public EnhancementTarget target() {
        return target;
    }

    public long transformationRevision() {
        return transformationRevision;
    }

    public String transformationHash() {
        return transformationHash;
    }

    public long applyTimeMillis() {
        return applyTimeMillis;
    }

    public String degradedReason() {
        return degradedReason;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public boolean isDegraded() {
        return degradedReason != null;
    }

    /** A non-empty snapshot with the supplied telemetry rules but unchanged chain identity. */
    public RuleChainSnapshot withRules(List<CompiledRule> newRules) {
        return new RuleChainSnapshot(revision, hash, newRules, target, transformationRevision,
                transformationHash, applyTimeMillis, degradedReason);
    }

    public RuleChainSnapshot withDegradedReason(String reason) {
        return new RuleChainSnapshot(revision, hash, rules, target, transformationRevision,
                transformationHash, applyTimeMillis, reason);
    }
}
