package com.example.kairo.api;

import java.util.Objects;

/**
 * A revision + content-hash pair used for command fencing.
 *
 * <p>Every APPLY/REMOVE/RECONCILE command carries an <em>expected</em>
 * {@code RuleChainRevision} (what the caller believes the Agent currently holds)
 * and a <em>desired</em> revision. The Agent accepts a transition only when the
 * expected revision matches its actual applied revision; otherwise it returns
 * {@code STALE_COMMAND}. The hash lets the Agent further verify the desired
 * content matches what the Platform canonicalized.
 *
 * @param value monotonic per-chain revision number
 * @param hash   canonical content hash at this revision
 */
public record RuleChainRevision(long value, String hash) {

    public RuleChainRevision {
        Objects.requireNonNull(hash, "hash");
    }

    public static RuleChainRevision initial() {
        return new RuleChainRevision(0L, "");
    }

    /** Whether this revision is the empty/initial sentinel (revision 0, blank hash). */
    public boolean isInitial() {
        return value == 0L && hash.isEmpty();
    }
}
