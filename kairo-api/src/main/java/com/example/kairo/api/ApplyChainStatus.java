package com.example.kairo.api;

/**
 * Outcome of an {@link ApplyChainRequest} on the Agent.
 *
 * <p>The fencing contract (&sect;3.3) requires that an out-of-order command be
 * rejected as {@link #STALE_COMMAND} and a duplicate command return the prior
 * result as {@link #IDEMPOTENT_REPLAY}. The remaining statuses distinguish the
 * failure modes that leave the current snapshot untouched: compile failure,
 * transformation failure, bytecode verification failure, and unsafe
 * coexistence with another agent.
 */
public enum ApplyChainStatus {

    /** The chain was compiled, transformed, verified and the running snapshot was atomically swapped. */
    APPLIED,

    /** The expected revision did not match the Agent's actual applied revision; the command was rejected. */
    STALE_COMMAND,

    /** A duplicate command (same idempotency key); the Agent returns the previous result. */
    IDEMPOTENT_REPLAY,

    /** The desired revision is behind the Agent's actual revision; the command was rejected. */
    STALE_DESIRED_REVISION,

    /** Compiling one or more scripts failed; the current snapshot is unchanged. */
    COMPILE_FAILED,

    /** Retransformation failed; the current snapshot is unchanged. */
    TRANSFORM_FAILED,

    /** V1.1 actual-bytecode verification failed after transformation; the snapshot is unchanged. */
    VERIFICATION_FAILED,

    /** Retransformation cannot be safely ordered against another agent; the snapshot is unchanged. */
    COEXISTENCE_UNSAFE,

    /** The target class/method could not be resolved on this Agent. */
    TARGET_NOT_FOUND,

    /**
     * V1.5 &sect;4.4: the target's bytecode hash changed (redefine/retransform/hot
     * update) and the call-site fingerprint no longer matches. The Agent keeps the
     * current snapshot and fails open rather than enhancing a drifted target.
     */
    TARGET_DRIFTED,

    /** The conflict report blocked the chain, or the desired hash did not match the spec. */
    REJECTED,

    /** Applied but the running snapshot carries a degraded reason (e.g. fail-open fallback). */
    DEGRADED,

    /** No change was needed: the Agent already holds this exact chain. */
    NO_OP
}
