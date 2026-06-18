package com.example.runtimemock.core;

import com.example.runtimemock.api.MockRule;
import com.example.runtimemock.groovy.CompiledMockScript;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CompiledRule {

    private final MockRule rule;
    private final CompiledMockScript script;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong executions = new AtomicLong();
    private final AtomicLong consecutiveErrors = new AtomicLong();
    private final AtomicLong consecutiveSlowExecutions = new AtomicLong();
    private final AtomicBoolean locked = new AtomicBoolean();

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

    public void recordError() {
        errors.incrementAndGet();
        executions.incrementAndGet();
        long consecutive = consecutiveErrors.incrementAndGet();
        if (consecutive >= 3 || (executions.get() >= 20 && errors.get() * 10 > executions.get())) {
            locked.set(true);
        }
    }

    public void recordSuccess(long durationNanos) {
        executions.incrementAndGet();
        consecutiveErrors.set(0);
        long durationMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(durationNanos);
        if (durationMillis > 100) {
            locked.set(true);
        } else if (durationMillis > 10) {
            if (consecutiveSlowExecutions.incrementAndGet() >= 3) {
                locked.set(true);
            }
        } else {
            consecutiveSlowExecutions.set(0);
        }
    }

    public void lock() {
        locked.set(true);
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
