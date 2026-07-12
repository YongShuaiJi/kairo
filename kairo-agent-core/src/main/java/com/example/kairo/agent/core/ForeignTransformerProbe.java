package com.example.kairo.agent.core;

/**
 * Description of a foreign (non-Kairo) class transformer, registered with
 * {@link ByteBuddyTransformerManager#registerForeignProbe} for the V1.4
 * coexistence test matrix.
 *
 * <p>The probe tells Kairo whether the foreign transformer is installed ahead
 * of Kairo, whether it supports retransformation, and whether it is idempotent
 * (safe to re-run on the original bytes). Retransform re-runs every installed
 * transformer; a non-idempotent foreign transformer ahead of Kairo cannot be
 * safely re-run, so the chain applier returns {@code COEXISTENCE_UNSAFE}
 * instead of corrupting the class.
 *
 * <p>Production deployments register no probes; Kairo is the only transformer
 * and coexistence is assumed safe.
 */
public interface ForeignTransformerProbe {

    /** Human-readable name of the foreign transformer, for diagnostics. */
    String name();

    /** Whether the foreign transformer is installed ahead of Kairo in the transformer chain. */
    boolean installedAheadOfKairo();

    /** Whether the foreign transformer supports JVM retransformation. */
    boolean supportsRetransform();

    /** Whether re-running the foreign transformer on the original bytes is safe (idempotent). */
    boolean idempotent();
}
