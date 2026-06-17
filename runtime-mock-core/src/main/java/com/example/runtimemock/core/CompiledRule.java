package com.example.runtimemock.core;

import com.example.runtimemock.api.MockRule;
import com.example.runtimemock.groovy.CompiledMockScript;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class CompiledRule {

    private final MockRule rule;
    private final CompiledMockScript script;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

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
    }

    public boolean isActive(long nowMillis) {
        return rule.enabled()
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
