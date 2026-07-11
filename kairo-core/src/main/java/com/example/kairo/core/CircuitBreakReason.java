package com.example.kairo.core;

/**
 * Why a {@link CompiledRule} was circuit-broken. Every value fails open &mdash; the original
 * method runs untouched &mdash; but the reason is recorded so operators can tell a broken script
 * apart from a saturated executor or a runaway timeout.
 */
public enum CircuitBreakReason {
    /** The script threw on {@code consecutiveFailureThreshold} consecutive executions. */
    CONSECUTIVE_ERRORS,
    /** The script's error rate exceeded the steady-state threshold over enough executions. */
    ERROR_RATE,
    /** The script completed but ran past the slow-execution watermark. */
    SLOW_EXECUTION,
    /** The script did not finish within the dispatch timeout and its task was cancelled. */
    TIMEOUT,
    /** The executor could not accept the task (pool/queue saturated) so it never ran. */
    SATURATION
}
