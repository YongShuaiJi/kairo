package com.example.kairo.core;

import com.example.kairo.api.CallSiteSelector;
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
 * can reconcile desired &harr; actual via {@link #revision()}, the
 * transformation revision/hash so the JVM bytecode layer can be reconciled
 * separately, and the {@code chainId} the Platform assigned to the desired chain
 * (V1.7 M1-C) so the actual snapshot reports the exact chain identifier rather
 * than a partial target-derived label. Legacy chains that do not carry an
 * explicit chainId use the deterministic {@link #chainIdOf(EnhancementTarget)}
 * fallback built from the complete target identity.
 */
public final class RuleChainSnapshot {

    public static final RuleChainSnapshot EMPTY =
            new RuleChainSnapshot(RuleChainRevision.initial(), "", "", List.of(),
                    null, 0L, "", 0L, null);

    private final RuleChainRevision revision;
    private final String chainId;
    private final String hash;
    private final List<CompiledRule> rules;
    private final EnhancementTarget target;
    private final long transformationRevision;
    private final String transformationHash;
    private final long applyTimeMillis;
    private final String degradedReason;

    public RuleChainSnapshot(RuleChainRevision revision, String chainId, String hash, List<CompiledRule> rules,
                             EnhancementTarget target, long transformationRevision,
                             String transformationHash, long applyTimeMillis, String degradedReason) {
        this.revision = Objects.requireNonNull(revision, "revision");
        this.chainId = chainId == null ? "" : chainId;
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
     * the same content. The {@code chainId} is the Platform-assigned chain id
     * (or the deterministic target-derived fallback for legacy chains).
     */
    public static RuleChainSnapshot of(String chainId, RuleChainRevision revision, List<CompiledRule> rules,
                                       EnhancementTarget target, long transformationRevision,
                                       String transformationHash, long applyTimeMillis) {
        List<RuleChainEntry> entries = ChainEntryProjector.project(rules);
        ChainDesiredState state = ChainEntryProjector.desiredStateFor(rules);
        String hash = target == null ? "" : RuleChainCanonicalizer.hash(target, entries, state);
        return new RuleChainSnapshot(revision, chainId, hash, rules, target, transformationRevision,
                transformationHash, applyTimeMillis, null);
    }

    public RuleChainRevision revision() {
        return revision;
    }

    /** The stable chain identifier (Platform-assigned, or the target-derived fallback for legacy chains). */
    public String chainId() {
        return chainId;
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
        return new RuleChainSnapshot(revision, chainId, hash, newRules, target, transformationRevision,
                transformationHash, applyTimeMillis, degradedReason);
    }

    public RuleChainSnapshot withDegradedReason(String reason) {
        return new RuleChainSnapshot(revision, chainId, hash, rules, target, transformationRevision,
                transformationHash, applyTimeMillis, reason);
    }

    /**
     * The deterministic fallback chain id for a legacy chain that did not carry an explicit
     * Platform-assigned {@code chainId}. Built from the <em>complete</em> target identity so two
     * chains on different loaders, overloads or call-sites never collide: class, loader, method,
     * descriptor, location and the complete call-site selector.
     */
    public static String chainIdOf(EnhancementTarget target) {
        if (target == null) {
            return "";
        }
        StringBuilder id = new StringBuilder()
                .append(target.method().className());
        String loaderId = target.method().classLoaderId();
        id.append('@').append(loaderId == null ? "bootstrap" : loaderId);
        id.append('#').append(target.method().methodName())
                .append(target.method().methodDescriptor());
        id.append('#').append(target.location().name());
        CallSiteSelector selector = target.callSiteSelector();
        if (selector != null) {
            id.append('[').append(selector.owner()).append('.').append(selector.name())
                    .append(selector.descriptor())
                    .append('@').append(selector.opcode().name())
                    .append('#').append(selector.occurrenceIndex());
            if (selector.fingerprint() != null) {
                id.append(':').append(selector.fingerprint());
            }
            id.append(']');
        }
        return id.toString();
    }
}
