package com.example.kairo.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockRuleEnhancementLocationTest {

    private static MethodSelector target(String name, String desc) {
        return new MethodSelector("com.example.Target", "loader-1", name, desc);
    }

    private static MockRule.Builder baseRule() {
        return MockRule.builder()
                .id("r1")
                .target(target("call", "()V"))
                .script("return null");
    }

    @Test
    void legacyRuleWithoutLocationDerivesMethodLocationFromPhase() {
        MockRule rule = baseRule().phase(InvokePhase.RETURN).build();
        assertThat(rule.location()).isNull();
        assertThat(rule.callSiteSelector()).isNull();
        assertThat(rule.effectiveLocation()).isEqualTo(EnhancementLocation.METHOD_RETURN);
        assertThat(rule.enhancementTarget().location()).isEqualTo(EnhancementLocation.METHOD_RETURN);
        assertThat(rule.enhancementTarget().isCallSite()).isFalse();
    }

    @Test
    void ruleWithFinallyLocationDerivesLegacyPhaseReturn() {
        MockRule rule = baseRule().location(EnhancementLocation.METHOD_FINALLY).build();
        assertThat(rule.phase()).isEqualTo(InvokePhase.RETURN);
        assertThat(rule.effectiveLocation()).isEqualTo(EnhancementLocation.METHOD_FINALLY);
    }

    @Test
    void ruleWithConstructorLocationDerivesLegacyPhase() {
        MockRule afterSuper = baseRule()
                .target(target("<init>", "()V"))
                .location(EnhancementLocation.CONSTRUCTOR_AFTER_SUPER).build();
        assertThat(afterSuper.phase()).isEqualTo(InvokePhase.BEFORE);
        assertThat(afterSuper.effectiveLocation()).isEqualTo(EnhancementLocation.CONSTRUCTOR_AFTER_SUPER);
        assertThat(afterSuper.enhancementTarget().isConstructor()).isTrue();
    }

    @Test
    void callSiteRuleRequiresSelector() {
        assertThatThrownBy(() -> baseRule().location(EnhancementLocation.CALL_BEFORE).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callSiteSelector is required");
    }

    @Test
    void callSiteRuleBuildsWithSelector() {
        CallSiteSelector selector = CallSiteSelector.builder()
                .owner("com.example.Callee").name("run").descriptor("()V")
                .opcode(InvokeOpcode.INVOKEVIRTUAL).occurrenceIndex(1).build();
        MockRule rule = baseRule()
                .location(EnhancementLocation.CALL_RETURN)
                .callSiteSelector(selector).build();
        assertThat(rule.phase()).isEqualTo(InvokePhase.RETURN);
        assertThat(rule.effectiveLocation()).isEqualTo(EnhancementLocation.CALL_RETURN);
        assertThat(rule.enhancementTarget().isCallSite()).isTrue();
        assertThat(rule.enhancementTarget().callSiteSelector()).isEqualTo(selector);
    }

    @Test
    void callSiteSelectorWithoutLocationRejected() {
        CallSiteSelector selector = CallSiteSelector.builder()
                .owner("o").name("n").descriptor("()V")
                .opcode(InvokeOpcode.INVOKESTATIC).occurrenceIndex(0).build();
        assertThatThrownBy(() -> baseRule().phase(InvokePhase.BEFORE).callSiteSelector(selector).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("call-site location");
    }

    @Test
    void neitherPhaseNorLocationRejected() {
        assertThatThrownBy(() -> baseRule().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phase or location must be set");
    }

    @Test
    void toBuilderPreservesLocationAndCallSiteSelector() {
        CallSiteSelector selector = CallSiteSelector.builder()
                .owner("o").name("n").descriptor("()V")
                .opcode(InvokeOpcode.INVOKESTATIC).occurrenceIndex(0).fingerprint("fp").build();
        MockRule rule = baseRule()
                .location(EnhancementLocation.CALL_THROW)
                .callSiteSelector(selector).build();
        MockRule rebuilt = rule.toBuilder().build();
        assertThat(rebuilt.location()).isEqualTo(EnhancementLocation.CALL_THROW);
        assertThat(rebuilt.callSiteSelector()).isEqualTo(selector);
        assertThat(rebuilt.phase()).isEqualTo(InvokePhase.THROWS);
    }
}
