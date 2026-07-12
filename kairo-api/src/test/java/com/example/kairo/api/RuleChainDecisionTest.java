package com.example.kairo.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleChainDecisionTest {

    @Test
    void legacyProceedMapsToContinue() {
        RuleChainDecision d = RuleChainDecision.from(MockDecision.proceed());
        assertThat(d.propagationMode()).isEqualTo(PropagationMode.CONTINUE);
        assertThat(d.outcomeState()).isEqualTo(OutcomeState.PROCEEDING);
        assertThat(d.replacesOutcome()).isFalse();
    }

    @Test
    void legacyProceedWithArgsMapsToContinueWithArguments() {
        RuleChainDecision d = RuleChainDecision.from(MockDecision.proceed(new Object[]{"x"}));
        assertThat(d.propagationMode()).isEqualTo(PropagationMode.CONTINUE);
        assertThat(d.hasArguments()).isTrue();
    }

    @Test
    void legacyReturnMapsToTerminateReturning() {
        RuleChainDecision d = RuleChainDecision.from(MockDecision.returnValue("v"));
        assertThat(d.propagationMode()).isEqualTo(PropagationMode.TERMINATE);
        assertThat(d.outcomeState()).isEqualTo(OutcomeState.RETURNING);
        assertThat(d.returnValue()).isEqualTo("v");
    }

    @Test
    void legacyThrowMapsToTerminateThrowing() {
        IllegalStateException err = new IllegalStateException("boom");
        RuleChainDecision d = RuleChainDecision.from(MockDecision.throwException(err));
        assertThat(d.propagationMode()).isEqualTo(PropagationMode.TERMINATE);
        assertThat(d.outcomeState()).isEqualTo(OutcomeState.THROWING);
        assertThat(d.throwable()).isSameAs(err);
    }

    @Test
    void explicitModesAreHonoured() {
        assertThat(RuleChainDecision.from(MockDecision.proceedOriginal()).propagationMode())
                .isEqualTo(PropagationMode.PROCEED_ORIGINAL);
        assertThat(RuleChainDecision.from(MockDecision.failOpen()).propagationMode())
                .isEqualTo(PropagationMode.FAIL_OPEN);
        assertThat(RuleChainDecision.from(MockDecision.failClosed(new IllegalStateException("x"))).propagationMode())
                .isEqualTo(PropagationMode.FAIL_CLOSED);
    }

    @Test
    void replaceReturnValueContinuesAndReplaces() {
        RuleChainDecision d = RuleChainDecision.from(MockDecision.replaceReturnValue("v"));
        assertThat(d.propagationMode()).isEqualTo(PropagationMode.CONTINUE);
        assertThat(d.outcomeState()).isEqualTo(OutcomeState.RETURNING);
        assertThat(d.returnValue()).isEqualTo("v");
        assertThat(d.replacesOutcome()).isTrue();
    }

    @Test
    void replaceThrowableContinuesAndReplaces() {
        IllegalStateException err = new IllegalStateException("boom");
        RuleChainDecision d = RuleChainDecision.from(MockDecision.replaceThrowable(err));
        assertThat(d.propagationMode()).isEqualTo(PropagationMode.CONTINUE);
        assertThat(d.outcomeState()).isEqualTo(OutcomeState.THROWING);
        assertThat(d.replacesOutcome()).isTrue();
    }

    @Test
    void nullDecisionDefaultsToContinue() {
        assertThat(RuleChainDecision.from(null).propagationMode()).isEqualTo(PropagationMode.CONTINUE);
    }
}
