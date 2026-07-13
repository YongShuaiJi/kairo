package com.example.kairo.agent.core;

import com.example.kairo.api.ApplyChainStatus;
import com.example.kairo.api.bytecode.ClassIdentity;

import java.util.concurrent.ConcurrentHashMap;

/**
 * V1.5 &sect;4.4: hot-update reconciliation.
 *
 * <p>When a target class is externally redefined or retransformed (an IDE hot swap, another
 * agent, or a container reload), the bytecode Kairo anchored its rule against may change.
 * The reconciler compares the bytecode hash observed at the last successful apply to the
 * hash observed now and decides whether the rule is still compatible:
 * <ul>
 *   <li><b>COMPATIBLE</b> &mdash; the hash is unchanged (or no prior hash exists, i.e. first
 *       apply). The rule is re-applied at a new transformation revision.</li>
 *   <li><b>DRIFTED</b> &mdash; the hash changed. The agent keeps the current snapshot and
 *       <em>fails open</em> rather than re-enhancing a target whose call-site fingerprint
 *       may have moved; the apply chain is marked {@link ApplyChainStatus#TARGET_DRIFTED}
 *       (&sect;4.4: "不兼容则保持 fail-open 并标记 TARGET_DRIFTED").</li>
 * </ul>
 *
 * <p>For call-site rules the call-site fingerprint is re-verified separately by
 * {@code CallSiteScanner} (which already produces {@code TargetMatchResult.DRIFTED}); this
 * reconciler is the bytecode-hash layer that fires even for non-call-site rules whose
 * declaring class body changed.
 *
 * <p>The reconciler is deliberately self-contained (its own identity &rarr; hash map) so it
 * can be unit-tested without a live agent, and so the snapshot repository remains the
 * authoritative byte-level record. {@code AgentRuntime} records the input hash at apply
 * time and consults the reconciler on the next apply.
 */
public final class HotUpdateReconciler {

    /** Outcome of reconciling a class's current bytecode hash against the last applied one. */
    public enum Outcome {
        /** Hash unchanged or first apply; the rule may be re-applied at a new revision. */
        COMPATIBLE,
        /** Hash changed; keep the current snapshot and fail open with TARGET_DRIFTED. */
        DRIFTED
    }

    /** Reconciliation result. {@link #previousHash()} is null on a first apply. */
    public record Result(Outcome outcome, String reason, String previousHash, String currentHash) {
        public boolean isDrifted() {
            return outcome == Outcome.DRIFTED;
        }
    }

    private final ConcurrentHashMap<ClassIdentity, String> lastAppliedInputHash = new ConcurrentHashMap<>();

    /** Record the bytecode hash observed when a rule was last applied to {@code identity}. */
    public void recordApplied(ClassIdentity identity, String inputBytecodeHash) {
        if (identity != null && inputBytecodeHash != null && !inputBytecodeHash.isBlank()) {
            lastAppliedInputHash.put(identity, inputBytecodeHash);
        }
    }

    /**
     * Reconcile {@code identity}'s current bytecode hash against the last applied one.
     * A null/blank current hash is treated as "unobserved" and scores COMPATIBLE (the
     * caller could not read the bytes, so drift is not asserted).
     */
    public Result reconcile(ClassIdentity identity, String currentInputBytecodeHash) {
        if (identity == null || currentInputBytecodeHash == null || currentInputBytecodeHash.isBlank()) {
            return new Result(Outcome.COMPATIBLE, "current hash unobserved; no drift asserted", null, currentInputBytecodeHash);
        }
        String previous = lastAppliedInputHash.get(identity);
        if (previous == null) {
            return new Result(Outcome.COMPATIBLE, "first apply; no prior hash to compare", null, currentInputBytecodeHash);
        }
        if (previous.equals(currentInputBytecodeHash)) {
            return new Result(Outcome.COMPATIBLE, "bytecode hash unchanged", previous, currentInputBytecodeHash);
        }
        return new Result(Outcome.DRIFTED,
                "bytecode hash changed: " + previous + " -> " + currentInputBytecodeHash,
                previous, currentInputBytecodeHash);
    }

    /** Map a reconciliation result to the apply-chain status the agent reports. */
    public ApplyChainStatus toStatus(Result result) {
        return result.isDrifted() ? ApplyChainStatus.TARGET_DRIFTED : ApplyChainStatus.APPLIED;
    }

    /** Forget the recorded hash for {@code identity} (e.g. after the rule is unloaded). */
    public void forget(ClassIdentity identity) {
        if (identity != null) {
            lastAppliedInputHash.remove(identity);
        }
    }

    /** Whether a hash has been recorded for {@code identity}. */
    public boolean hasRecorded(ClassIdentity identity) {
        return identity != null && lastAppliedInputHash.containsKey(identity);
    }
}
