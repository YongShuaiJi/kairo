package com.example.kairo.core;

import com.example.kairo.api.MockRule;
import com.example.kairo.groovy.CompiledMockScript;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class CompiledRule {

    public enum ExecutionPermit {
        DENIED,
        CLOSED,
        HALF_OPEN
    }

    /** Slow execution (over the configured slow watermark) that tripped the circuit breaker. */
    private static final long SLOW_WATERMARK_MILLIS = 100L;
    private static final long CONSECUTIVE_SLOW_WATERMARK_MILLIS = 10L;
    private static final int CONSECUTIVE_SLOW_THRESHOLD = 3;
    private static final int ERROR_RATE_MIN_EXECUTIONS = 20;

    private final MockRule rule;
    private final CompiledMockScript script;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong executions = new AtomicLong();
    private final AtomicLong consecutiveErrors = new AtomicLong();
    private final AtomicLong consecutiveSlowExecutions = new AtomicLong();
    private final AtomicLong unfinishedTaskCount = new AtomicLong();
    private final AtomicLong lastDurationNanos = new AtomicLong();
    private final AtomicBoolean locked = new AtomicBoolean();
    private final AtomicBoolean manuallyLocked = new AtomicBoolean();
    private final AtomicBoolean halfOpenProbeInFlight = new AtomicBoolean();
    private final AtomicLong circuitOpenedAtMillis = new AtomicLong(-1L);
    private final AtomicReference<CircuitBreakReason> circuitBreakReason = new AtomicReference<>();

    public CompiledRule(MockRule rule, CompiledMockScript script) {
        this.rule = Objects.requireNonNull(rule, "rule");
        this.script = Objects.requireNonNull(script, "script");
    }

    public MockRule rule() {
        return rule;
    }

    public CompiledMockScript script() {
        return script;
    }

    public long hits() {
        return hits.get();
    }

    public long errors() {
        return errors.get();
    }

    public long executions() {
        return executions.get();
    }

    public long unfinishedTaskCount() {
        return unfinishedTaskCount.get();
    }

    /** Duration of the most recent completed execution, in nanoseconds (0 before the first run). */
    public long lastDurationNanos() {
        return lastDurationNanos.get();
    }

    /** Duration of the most recent completed execution, in milliseconds (0 before the first run). */
    public long lastDurationMillis() {
        return TimeUnit.NANOSECONDS.toMillis(lastDurationNanos.get());
    }

    /**
     * Why this rule was circuit-broken, or {@code null} while it remains healthy. Distinguishes
     * consecutive script errors, a high error rate, slow executions, timeouts and executor
     * saturation &mdash; all of which fail open but for different operational reasons.
     */
    public CircuitBreakReason circuitBreakReason() {
        return circuitBreakReason.get();
    }

    public void recordError() {
        recordError(ExecutionPermit.CLOSED);
    }

    public void recordError(ExecutionPermit permit) {
        errors.incrementAndGet();
        executions.incrementAndGet();
        long consecutive = consecutiveErrors.incrementAndGet();
        if (permit == ExecutionPermit.HALF_OPEN && locked.get()) {
            reopenCircuit(CircuitBreakReason.CONSECUTIVE_ERRORS);
            return;
        }
        if (consecutive >= rule.consecutiveFailureThreshold()) {
            circuitBreak(CircuitBreakReason.CONSECUTIVE_ERRORS);
        } else if (executions.get() >= ERROR_RATE_MIN_EXECUTIONS
                && errors.get() * 10 > executions.get()) {
            circuitBreak(CircuitBreakReason.ERROR_RATE);
        }
    }

    public void recordSuccess(long durationNanos) {
        recordSuccess(durationNanos, ExecutionPermit.CLOSED);
    }

    public void recordSuccess(long durationNanos, ExecutionPermit permit) {
        lastDurationNanos.set(durationNanos);
        executions.incrementAndGet();
        consecutiveErrors.set(0);
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(durationNanos);
        if (permit == ExecutionPermit.HALF_OPEN && locked.get()) {
            if (durationMillis > SLOW_WATERMARK_MILLIS) {
                reopenCircuit(CircuitBreakReason.SLOW_EXECUTION);
            } else {
                closeAutomaticCircuit();
            }
            return;
        }
        if (durationMillis > SLOW_WATERMARK_MILLIS) {
            circuitBreak(CircuitBreakReason.SLOW_EXECUTION);
        } else if (durationMillis > CONSECUTIVE_SLOW_WATERMARK_MILLIS) {
            if (consecutiveSlowExecutions.incrementAndGet() >= CONSECUTIVE_SLOW_THRESHOLD) {
                circuitBreak(CircuitBreakReason.SLOW_EXECUTION);
            }
        } else {
            consecutiveSlowExecutions.set(0);
        }
    }

    /**
     * Record that an execution timed out and was cancelled. The task may still be running
     * (Java cannot safely hard-stop an arbitrary thread), so it is counted as unfinished and
     * the rule is circuit-broken so subsequent hits do not pile up behind it.
     */
    public void recordTimeout() {
        recordTimeout(ExecutionPermit.CLOSED);
    }

    public void recordTimeout(ExecutionPermit permit) {
        unfinishedTaskCount.incrementAndGet();
        circuitBreak(CircuitBreakReason.TIMEOUT, permit);
    }

    public void lock() {
        manuallyLocked.set(true);
        circuitBreak(null);
    }

    /**
     * Circuit-break the rule for a specific operational reason, or unconditionally when no
     * reason is supplied. Idempotent: the first reason wins so diagnostics reflect the
     * original trigger rather than a later side-effect.
     */
    public void circuitBreak(CircuitBreakReason reason) {
        circuitBreak(reason, ExecutionPermit.CLOSED);
    }

    public void circuitBreak(CircuitBreakReason reason, ExecutionPermit permit) {
        if (reason == null) {
            manuallyLocked.set(true);
        }
        boolean newlyOpened = locked.compareAndSet(false, true);
        circuitBreakReason.compareAndSet(null, reason);
        if (newlyOpened || permit == ExecutionPermit.HALF_OPEN) {
            circuitOpenedAtMillis.set(System.currentTimeMillis());
        }
        if (newlyOpened || permit == ExecutionPermit.HALF_OPEN) {
            halfOpenProbeInFlight.set(false);
        }
    }

    /**
     * Acquire permission to execute this rule. Healthy rules pass immediately. An automatically
     * opened circuit admits exactly one half-open probe after the configured delay, preventing a
     * transient timeout or scheduler stall from disabling a long-lived enhancement forever.
     */
    public ExecutionPermit tryAcquireExecution(long nowMillis, long recoveryDelayMillis) {
        if (!rule.enabled() || rule.percentage() <= 0
                || (rule.expireAt() > 0 && rule.expireAt() <= nowMillis)) {
            return ExecutionPermit.DENIED;
        }
        if (!locked.get()) {
            return ExecutionPermit.CLOSED;
        }
        if (manuallyLocked.get() || circuitBreakReason.get() == null) {
            return ExecutionPermit.DENIED;
        }
        long openedAt = circuitOpenedAtMillis.get();
        if (openedAt < 0 || nowMillis - openedAt < recoveryDelayMillis) {
            return ExecutionPermit.DENIED;
        }
        return halfOpenProbeInFlight.compareAndSet(false, true)
                ? ExecutionPermit.HALF_OPEN : ExecutionPermit.DENIED;
    }

    /** Release a half-open permit when sampling, hit limits or re-entry skip execution. */
    public void releaseExecutionPermit(ExecutionPermit permit) {
        if (permit == ExecutionPermit.HALF_OPEN && locked.get()) {
            halfOpenProbeInFlight.set(false);
        }
    }

    private void reopenCircuit(CircuitBreakReason reason) {
        circuitBreakReason.compareAndSet(null, reason);
        locked.set(true);
        circuitOpenedAtMillis.set(System.currentTimeMillis());
        halfOpenProbeInFlight.set(false);
    }

    private void closeAutomaticCircuit() {
        if (manuallyLocked.get()) {
            halfOpenProbeInFlight.set(false);
            return;
        }
        consecutiveErrors.set(0);
        consecutiveSlowExecutions.set(0);
        circuitBreakReason.set(null);
        circuitOpenedAtMillis.set(-1L);
        locked.set(false);
        halfOpenProbeInFlight.set(false);
    }

    public boolean locked() {
        return locked.get();
    }

    public boolean hasExecuted() {
        return executions.get() > 0;
    }

    public boolean isActive(long nowMillis) {
        return rule.enabled()
                && !locked.get()
                && rule.percentage() > 0
                && (rule.expireAt() <= 0 || rule.expireAt() > nowMillis);
    }

    public boolean tryClaimHit() {
        long maxHits = rule.maxHits();
        if (maxHits <= 0) {
            hits.incrementAndGet();
            return true;
        }
        while (true) {
            long current = hits.get();
            if (current >= maxHits) {
                return false;
            }
            if (hits.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }
}
