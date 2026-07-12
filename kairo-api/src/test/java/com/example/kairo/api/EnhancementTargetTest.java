package com.example.kairo.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnhancementTargetTest {

    private static MethodSelector method(String name, String desc) {
        return new MethodSelector("com.example.Target", "loader-1", name, desc);
    }

    @Test
    void methodTargetHasNoCallSiteSelector() {
        EnhancementTarget target = EnhancementTarget.of(method("call", "()V"), EnhancementLocation.METHOD_ENTER);
        assertThat(target.isCallSite()).isFalse();
        assertThat(target.isConstructor()).isFalse();
        assertThat(target.callSiteSelector()).isNull();
    }

    @Test
    void callSiteTargetRequiresSelector() {
        assertThatThrownBy(() -> EnhancementTarget.callSite(method("caller", "()V"),
                EnhancementLocation.CALL_BEFORE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callSiteSelector is required");
    }

    @Test
    void callSiteTargetBuiltWithSelector() {
        CallSiteSelector selector = CallSiteSelector.builder()
                .owner("com.example.Callee").name("run").descriptor("()V")
                .opcode(InvokeOpcode.INVOKEVIRTUAL).occurrenceIndex(1).build();
        EnhancementTarget target = EnhancementTarget.callSite(method("caller", "()V"),
                EnhancementLocation.CALL_RETURN, selector);
        assertThat(target.isCallSite()).isTrue();
        assertThat(target.callSiteSelector()).isEqualTo(selector);
        assertThat(target.location()).isEqualTo(EnhancementLocation.CALL_RETURN);
    }

    @Test
    void builderRejectsCallSiteSelectorOnMethodLocation() {
        CallSiteSelector selector = CallSiteSelector.builder()
                .owner("com.example.Callee").name("run").descriptor("()V")
                .opcode(InvokeOpcode.INVOKESTATIC).occurrenceIndex(0).build();
        assertThatThrownBy(() -> EnhancementTarget.builder()
                .method(method("call", "()V"))
                .location(EnhancementLocation.METHOD_RETURN)
                .callSiteSelector(selector)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callSiteSelector must be null");
    }

    @Test
    void fromLegacyProjectsPhaseOntoMethodLocation() {
        EnhancementTarget target = EnhancementTarget.fromLegacy(method("call", "()I"), InvokePhase.RETURN);
        assertThat(target.location()).isEqualTo(EnhancementLocation.METHOD_RETURN);
        assertThat(target.isCallSite()).isFalse();
    }

    @Test
    void callSiteSelectorCoreEqualityIgnoresFingerprint() {
        CallSiteSelector a = CallSiteSelector.builder()
                .owner("o").name("n").descriptor("()V")
                .opcode(InvokeOpcode.INVOKEVIRTUAL).occurrenceIndex(0).fingerprint("abc").build();
        CallSiteSelector b = CallSiteSelector.builder()
                .owner("o").name("n").descriptor("()V")
                .opcode(InvokeOpcode.INVOKEVIRTUAL).occurrenceIndex(0).fingerprint("xyz").build();
        assertThat(a.coreEquals(b)).isTrue();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void callSiteIdentityFingerprintMatchesOnlyWhenBothPresent() {
        MethodSelector caller = method("caller", "()V");
        CallSiteSelector recorded = CallSiteSelector.builder()
                .owner("o").name("n").descriptor("()V")
                .opcode(InvokeOpcode.INVOKEVIRTUAL).occurrenceIndex(0).fingerprint("abc").build();
        CallSiteSelector freshSame = CallSiteSelector.builder()
                .owner("o").name("n").descriptor("()V")
                .opcode(InvokeOpcode.INVOKEVIRTUAL).occurrenceIndex(0).fingerprint("abc").build();
        CallSiteSelector freshDrifted = CallSiteSelector.builder()
                .owner("o").name("n").descriptor("()V")
                .opcode(InvokeOpcode.INVOKEVIRTUAL).occurrenceIndex(0).fingerprint("xyz").build();
        CallSiteSelector noFingerprint = CallSiteSelector.builder()
                .owner("o").name("n").descriptor("()V")
                .opcode(InvokeOpcode.INVOKEVIRTUAL).occurrenceIndex(0).build();

        CallSiteIdentity recordedId = new CallSiteIdentity(caller, recorded);
        assertThat(recordedId.fingerprintMatches(new CallSiteIdentity(caller, freshSame))).isTrue();
        assertThat(recordedId.fingerprintMatches(new CallSiteIdentity(caller, freshDrifted))).isFalse();
        assertThat(recordedId.fingerprintMatches(new CallSiteIdentity(caller, noFingerprint))).isTrue();
        assertThat(recordedId.coreEquals(new CallSiteIdentity(caller, freshDrifted))).isTrue();
    }

    @Test
    void invokeOpcodeValuesAreJvmSpecStable() {
        assertThat(InvokeOpcode.INVOKEVIRTUAL.opcode()).isEqualTo(182);
        assertThat(InvokeOpcode.INVOKESPECIAL.opcode()).isEqualTo(183);
        assertThat(InvokeOpcode.INVOKESTATIC.opcode()).isEqualTo(184);
        assertThat(InvokeOpcode.INVOKEINTERFACE.opcode()).isEqualTo(185);
        assertThat(InvokeOpcode.INVOKEDYNAMIC.opcode()).isEqualTo(186);
        assertThat(InvokeOpcode.fromOpcode(185)).isEqualTo(InvokeOpcode.INVOKEINTERFACE);
        assertThat(InvokeOpcode.INVOKEDYNAMIC.isSupported()).isFalse();
        assertThat(InvokeOpcode.INVOKESTATIC.isSupported()).isTrue();
        assertThatThrownBy(() -> InvokeOpcode.fromOpcode(999))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
