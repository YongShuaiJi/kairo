package com.example.kairo.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptCapabilityDtoTest {

    @Test
    void effectiveProfileAlwaysChoosesMostRestrictiveDecision() {
        assertThat(CapabilityProfile.effective(CapabilityProfile.UNRESTRICTED,
                CapabilityProfile.EXTENDED, CapabilityProfile.UNRESTRICTED))
                .isEqualTo(CapabilityProfile.EXTENDED);
        assertThat(CapabilityProfile.effective(CapabilityProfile.SAFE,
                CapabilityProfile.UNRESTRICTED, CapabilityProfile.EXTENDED))
                .isEqualTo(CapabilityProfile.SAFE);
        assertThat(CapabilityProfile.effective(CapabilityProfile.UNRESTRICTED,
                CapabilityProfile.UNRESTRICTED, CapabilityProfile.UNRESTRICTED))
                .isEqualTo(CapabilityProfile.UNRESTRICTED);
        assertThatThrownBy(() -> CapabilityProfile.effective(null,
                CapabilityProfile.SAFE, CapabilityProfile.SAFE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void oldStyleRuleDefaultsToSafeAndThreeFailures() {
        MockRule rule = baseRule().build();

        assertThat(rule.capabilityProfile()).isEqualTo(CapabilityProfile.SAFE);
        assertThat(rule.policyRevision()).isNull();
        assertThat(rule.consecutiveFailureThreshold()).isEqualTo(3);
        assertThat(rule.scriptSessionSource()).isNull();
    }

    @Test
    void toBuilderPreservesNewFieldsAndOldBuilderRemainsCompatible() {
        ScriptPolicyRevision revision = new ScriptPolicyRevision(7, "policy-hash");
        MockRule original = baseRule()
                .capabilityProfile(CapabilityProfile.EXTENDED)
                .policyRevision(revision)
                .consecutiveFailureThreshold(5)
                .scriptSessionSource("session-42")
                .build();

        MockRule copy = original.toBuilder().name("copy").build();
        assertThat(copy.capabilityProfile()).isEqualTo(CapabilityProfile.EXTENDED);
        assertThat(copy.policyRevision()).isEqualTo(revision);
        assertThat(copy.consecutiveFailureThreshold()).isEqualTo(5);
        assertThat(copy.scriptSessionSource()).isEqualTo("session-42");
        assertThat(copy.name()).isEqualTo("copy");
    }

    @Test
    void ruleRejectsInvalidNewConfiguration() {
        assertThatThrownBy(() -> baseRule().capabilityProfile(null).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> baseRule().consecutiveFailureThreshold(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> baseRule().scriptSessionSource(" ").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compilationResultDefensivelyCopiesDiagnostics() {
        ScriptPolicyRevision revision = new ScriptPolicyRevision(1, "hash");
        List<ScriptDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.add(diagnostic(ScriptDiagnostic.Severity.WARNING));
        ScriptCompilationResult result = new ScriptCompilationResult(false, "script-hash",
                CapabilityProfile.SAFE, revision, "groovy-4", "loader-1", diagnostics);

        diagnostics.clear();
        assertThat(result.diagnostics()).hasSize(1);
        assertThatThrownBy(() -> result.diagnostics().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ScriptCompilationResult(true, "script-hash",
                CapabilityProfile.SAFE, revision, "groovy-4", "loader-1",
                List.of(diagnostic(ScriptDiagnostic.Severity.ERROR))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionResultDefensivelyCopiesDiagnosticsAndValidatesBounds() {
        List<ScriptDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.add(diagnostic(ScriptDiagnostic.Severity.INFO));
        ScriptSessionResult result = new ScriptSessionResult("s1", ScriptSessionStatus.CREATED,
                10, 20, 0, diagnostics);
        diagnostics.clear();
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(ScriptSessionStatus.EXPIRED.terminal()).isTrue();
        assertThat(ScriptSessionStatus.APPLIED.terminal()).isFalse();

        assertThatThrownBy(() -> new ScriptSessionResult("s1", ScriptSessionStatus.CREATED,
                10, 10, 0, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScriptSessionResult("s1", ScriptSessionStatus.CREATED,
                10, 20, -1, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestPolicyDiagnosticAndSessionSpecValidateRequiredValues() {
        ScriptPolicyRevision revision = new ScriptPolicyRevision(0, "hash");
        MethodSelector target = target();
        assertThat(new ScriptCompilationRequest("return null", "script-hash",
                CapabilityProfile.SAFE, revision, "loader-1").targetClassLoaderId())
                .isEqualTo("loader-1");
        assertThat(new ScriptSessionSpec("s1", "a1", target, "return null",
                CapabilityProfile.SAFE, revision, 1_000, 1, "alice").maxHits()).isOne();

        assertThatThrownBy(() -> new ScriptPolicyRevision(-1, "hash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScriptCompilationRequest(" ", "hash",
                CapabilityProfile.SAFE, revision, "loader"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScriptSessionSpec("s", "a", target, "code",
                CapabilityProfile.SAFE, revision, 0, 1, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScriptDiagnostic(ScriptDiagnostic.Phase.COMPILATION,
                ScriptDiagnostic.Severity.ERROR, -1, 0, "E", "bad", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ScriptDiagnostic diagnostic(ScriptDiagnostic.Severity severity) {
        return new ScriptDiagnostic(ScriptDiagnostic.Phase.COMPILATION, severity,
                1, 2, "TEST", "message", "loader-1", "fix it");
    }

    private static MockRule.Builder baseRule() {
        return MockRule.builder()
                .id("r1")
                .target(target())
                .phase(InvokePhase.BEFORE)
                .script("return null");
    }

    private static MethodSelector target() {
        return new MethodSelector("com.example.Target", "loader-1", "call", "()V");
    }
}
