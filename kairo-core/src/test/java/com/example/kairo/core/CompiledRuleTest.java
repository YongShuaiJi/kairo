package com.example.kairo.core;

import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockDecision;
import com.example.kairo.api.MockRule;
import com.example.kairo.groovy.CompiledMockScript;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CompiledRuleTest {

    @Test
    void consecutiveErrorsCircuitBreakAtConfiguredThreshold() {
        CompiledRule rule = rule(MockRule.builder().consecutiveFailureThreshold(3));

        rule.recordError();
        assertThat(rule.locked()).isFalse();
        rule.recordError();
        assertThat(rule.locked()).isFalse();
        rule.recordError();

        assertThat(rule.locked()).isTrue();
        assertThat(rule.circuitBreakReason()).isEqualTo(CircuitBreakReason.CONSECUTIVE_ERRORS);
        assertThat(rule.errors()).isEqualTo(3);
    }

    @Test
    void thresholdIsReadFromTheRuleNotHardcoded() {
        CompiledRule tight = rule(MockRule.builder().consecutiveFailureThreshold(2));
        tight.recordError();
        tight.recordError();
        assertThat(tight.locked()).isTrue();

        CompiledRule loose = rule(MockRule.builder().consecutiveFailureThreshold(5));
        for (int i = 0; i < 4; i++) {
            loose.recordError();
        }
        assertThat(loose.locked()).isFalse();
        assertThat(loose.errors()).isEqualTo(4);
    }

    @Test
    void errorRateCircuitBreaksWhenErrorsDominateExecutions() {
        // High threshold so the consecutive-errors guard never trips; mix successes in so the
        // error-rate guard is what fires.
        CompiledRule rule = rule(MockRule.builder().consecutiveFailureThreshold(100));
        for (int i = 0; i < 18; i++) {
            rule.recordSuccess(TimeUnit.MILLISECONDS.toNanos(1));
        }
        for (int i = 0; i < 3; i++) {
            rule.recordError();
        }

        assertThat(rule.locked()).isTrue();
        assertThat(rule.circuitBreakReason()).isEqualTo(CircuitBreakReason.ERROR_RATE);
        assertThat(rule.executions()).isEqualTo(21);
    }

    @Test
    void slowExecutionCircuitBreaksImmediately() {
        CompiledRule rule = rule(MockRule.builder().consecutiveFailureThreshold(100));

        rule.recordSuccess(TimeUnit.MILLISECONDS.toNanos(101));

        assertThat(rule.locked()).isTrue();
        assertThat(rule.circuitBreakReason()).isEqualTo(CircuitBreakReason.SLOW_EXECUTION);
    }

    @Test
    void repeatedBorderlineSlowExecutionsCircuitBreak() {
        CompiledRule rule = rule(MockRule.builder().consecutiveFailureThreshold(100));

        rule.recordSuccess(TimeUnit.MILLISECONDS.toNanos(11));
        assertThat(rule.locked()).isFalse();
        rule.recordSuccess(TimeUnit.MILLISECONDS.toNanos(11));
        assertThat(rule.locked()).isFalse();
        rule.recordSuccess(TimeUnit.MILLISECONDS.toNanos(11));

        assertThat(rule.locked()).isTrue();
        assertThat(rule.circuitBreakReason()).isEqualTo(CircuitBreakReason.SLOW_EXECUTION);
    }

    @Test
    void recordTimeoutCountsUnfinishedTaskAndBreaksCircuit() {
        CompiledRule rule = rule(MockRule.builder().consecutiveFailureThreshold(100));

        assertThat(rule.unfinishedTaskCount()).isZero();
        rule.recordTimeout();
        rule.recordTimeout();

        assertThat(rule.unfinishedTaskCount()).isEqualTo(2);
        assertThat(rule.locked()).isTrue();
        assertThat(rule.circuitBreakReason()).isEqualTo(CircuitBreakReason.TIMEOUT);
    }

    @Test
    void saturationCircuitBreaks() {
        CompiledRule rule = rule(MockRule.builder().consecutiveFailureThreshold(100));

        rule.circuitBreak(CircuitBreakReason.SATURATION);

        assertThat(rule.locked()).isTrue();
        assertThat(rule.circuitBreakReason()).isEqualTo(CircuitBreakReason.SATURATION);
    }

    @Test
    void lastDurationIsTrackedOnSuccess() {
        CompiledRule rule = rule(MockRule.builder().consecutiveFailureThreshold(100));

        assertThat(rule.lastDurationMillis()).isZero();
        rule.recordSuccess(TimeUnit.MILLISECONDS.toNanos(7));

        assertThat(rule.lastDurationMillis()).isEqualTo(7);
    }

    @Test
    void successResetsConsecutiveErrors() {
        CompiledRule rule = rule(MockRule.builder().consecutiveFailureThreshold(3));

        rule.recordError();
        rule.recordError();
        rule.recordSuccess(TimeUnit.MILLISECONDS.toNanos(1));
        rule.recordError();
        rule.recordError();

        assertThat(rule.locked()).isFalse();
        assertThat(rule.errors()).isEqualTo(4);
    }

    @Test
    void circuitBreakReasonIsNullWhileHealthy() {
        CompiledRule rule = rule(MockRule.builder().consecutiveFailureThreshold(3));

        assertThat(rule.locked()).isFalse();
        assertThat(rule.circuitBreakReason()).isNull();
    }

    @Test
    void firstReasonWinsOnCircuitBreak() {
        CompiledRule rule = rule(MockRule.builder().consecutiveFailureThreshold(1));

        rule.recordTimeout();
        rule.circuitBreak(CircuitBreakReason.SATURATION);

        assertThat(rule.circuitBreakReason()).isEqualTo(CircuitBreakReason.TIMEOUT);
    }

    @Test
    void tryClaimHitRespectsMaxHits() {
        MockRule mockRule = MockRule.builder()
                .id("r")
                .target(target())
                .phase(InvokePhase.BEFORE)
                .script("return null")
                .maxHits(2)
                .build();
        CompiledRule rule = new CompiledRule(mockRule, stubScript());

        assertThat(rule.tryClaimHit()).isTrue();
        assertThat(rule.tryClaimHit()).isTrue();
        assertThat(rule.tryClaimHit()).isFalse();
        assertThat(rule.hits()).isEqualTo(2);
    }

    private static CompiledRule rule(MockRule.Builder builder) {
        MockRule mockRule = builder
                .id("r")
                .target(target())
                .phase(InvokePhase.BEFORE)
                .script("return null")
                .build();
        return new CompiledRule(mockRule, stubScript());
    }

    private static MethodSelector target() {
        return new MethodSelector("com.example.Target", "loader-1", "call", "()V");
    }

    private static CompiledMockScript stubScript() {
        return new CompiledMockScript() {
            @Override
            public String ruleId() {
                return "r";
            }

            @Override
            public long version() {
                return 1;
            }

            @Override
            public String scriptHash() {
                return "hash";
            }

            @Override
            public MockDecision execute(InvocationContext context) {
                return MockDecision.proceed();
            }
        };
    }
}
