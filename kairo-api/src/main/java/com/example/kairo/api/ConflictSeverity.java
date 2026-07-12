package com.example.kairo.api;

/**
 * Severity of a static conflict finding.
 *
 * <p>{@link #ERROR} blocks the chain from being applied;
 * {@link #WARNING} is surfaced but does not block;
 * {@link #POTENTIAL} means business-condition overlap could not be decided
 * statically and the user must explicitly confirm before the chain proceeds.
 * The analyzer never pretends to have precisely proved a {@link #POTENTIAL}
 * conflict &mdash; it surfaces the uncertainty instead.
 */
public enum ConflictSeverity {
    ERROR,
    WARNING,
    POTENTIAL
}
