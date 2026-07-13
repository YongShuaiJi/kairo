package com.example.kairo.api;

/**
 * Declared support level for a JVM-compatibility scenario (V1.5 &sect;2).
 *
 * <p>Every compatibility scenario the agent and platform recognize must carry one
 * of these levels so that "Byte Buddy can in principle do this" is never
 * substituted for a Kairo support statement. The level governs whether a scenario
 * is covered by continuous integration, documented as a known limit, gated
 * behind an experimental flag, or refused before release.
 *
 * <p>Levels are ordered from strongest to weakest commitment; {@link #isStable()}
 * is true only for {@link #SUPPORTED}, the single level that enters the stable
 * release promise.
 */
public enum SupportLevel {

    /** Covered by continuous integration; enhancement and unload are committed. */
    SUPPORTED,
    /** A documented limit; verified automatically or manually but not a stable commitment. */
    LIMITED,
    /** Usable but not part of the stable promise; may change or be withdrawn. */
    EXPERIMENTAL,
    /** Refused before release with a stated reason; the agent never silently attempts it. */
    UNSUPPORTED;

    /** Whether this level carries a stable release promise (only {@link #SUPPORTED}). */
    public boolean isStable() {
        return this == SUPPORTED;
    }

    /** Whether the agent may attempt the scenario at all (everything but {@link #UNSUPPORTED}). */
    public boolean isAttemptable() {
        return this != UNSUPPORTED;
    }

    /** A short human-readable label suitable for the Web console and matrix report. */
    public String label() {
        return name();
    }
}
