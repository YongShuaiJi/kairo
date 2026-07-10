package com.example.kairo.api.bytecode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BytecodeVisibilityDtoTest {

    @Test
    void classIdentityEqualsOnNameAndLoaderOnly() {
        ClassIdentity a = new ClassIdentity("com.example.Foo", "loader-1");
        ClassIdentity b = new ClassIdentity("com.example.Foo", "loader-1");
        ClassIdentity otherLoader = new ClassIdentity("com.example.Foo", "loader-2");
        ClassIdentity otherName = new ClassIdentity("com.example.Bar", "loader-1");

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(otherLoader);
        assertThat(a).isNotEqualTo(otherName);
        assertThat(a.toString()).isEqualTo("com.example.Foo@loader-1");
    }

    @Test
    void classIdentityRejectsBlank() {
        assertThatThrownBy(() -> new ClassIdentity("", "loader-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassIdentity("com.example.Foo", "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassIdentity(null, "loader-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transformationRevisionValidatesAndAdvances() {
        assertThat(TransformationRevision.INITIAL.value()).isZero();
        assertThat(TransformationRevision.INITIAL.isInitial()).isTrue();
        assertThatThrownBy(() -> TransformationRevision.of(-1L))
                .isInstanceOf(IllegalArgumentException.class);

        TransformationRevision r = TransformationRevision.of(3L);
        assertThat(r.next().value()).isEqualTo(4L);
        assertThat(r.compareTo(TransformationRevision.of(5L))).isNegative();
        assertThat(r.compareTo(TransformationRevision.of(1L))).isPositive();
        assertThat(TransformationRevision.of(3L)).isEqualTo(r);
        assertThat(r.toString()).isEqualTo("r3");
    }

    @Test
    void nextThrowsClearIllegalStateExceptionAtLongMaxValue() {
        TransformationRevision max = TransformationRevision.of(Long.MAX_VALUE);

        assertThatThrownBy(max::next)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overflow");

        // sanity: advancing from a value just below the ceiling still works
        TransformationRevision near = TransformationRevision.of(Long.MAX_VALUE - 1L);
        assertThat(near.next().value()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void metadataValidatesRequiredFields() {
        ClassIdentity c = new ClassIdentity("com.example.Foo", "loader-1");
        assertThatThrownBy(() -> new BytecodeSnapshotMetadata(c, TransformationRevision.of(1),
                BytecodeSnapshotKind.INPUT, "", 1, 0L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BytecodeSnapshotMetadata(c, TransformationRevision.of(1),
                BytecodeSnapshotKind.INPUT, "h", -1, 0L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BytecodeSnapshotMetadata(c, TransformationRevision.of(1),
                BytecodeSnapshotKind.INPUT, "h", 1, -1L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BytecodeSnapshotMetadata(null, TransformationRevision.of(1),
                BytecodeSnapshotKind.INPUT, "h", 1, 0L, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void transformationResultCopiesDiagnosticsDefensively() {
        ClassIdentity c = new ClassIdentity("com.example.Foo", "loader-1");
        List<TransformationDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.add(TransformationDiagnostic.error("E1", "one"));

        TransformationResult result = new TransformationResult(c, TransformationRevision.of(1),
                TransformationStatus.STARTED, "in", null, diagnostics, 1L, 0L);

        diagnostics.add(TransformationDiagnostic.error("E2", "two"));
        assertThat(result.diagnostics()).hasSize(1);
        assertThatThrownBy(() -> result.diagnostics().add(TransformationDiagnostic.error("E3", "three")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void diffResultStructuresMethodDiffsAndIsImmutable() {
        ClassIdentity c = new ClassIdentity("com.example.Foo", "loader-1");
        BytecodeDiffResult.MethodDiff methodDiff = new BytecodeDiffResult.MethodDiff(
                "createOrder", "(Lcom/example/Req;)Lcom/example/Order;",
                BytecodeDiffResult.ChangeType.MODIFIED,
                List.of("INVOKESTATIC added at offset 12"),
                List.of());
        BytecodeDiffResult diff = new BytecodeDiffResult(c,
                TransformationRevision.of(1), TransformationRevision.of(2),
                BytecodeSnapshotKind.INPUT, BytecodeSnapshotKind.APPLIED,
                "hash-1", "hash-2", false, true,
                List.of(methodDiff), List.of("superclass unchanged"), "1 method modified");

        assertThat(diff.identical()).isFalse();
        assertThat(diff.normalized()).isTrue();
        assertThat(diff.methodDiffs()).hasSize(1);
        assertThat(diff.methodDiffs().get(0).changeType()).isEqualTo(BytecodeDiffResult.ChangeType.MODIFIED);
        assertThatThrownBy(() -> diff.methodDiffs().add(methodDiff))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> diff.structuralDiffs().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new BytecodeDiffResult.MethodDiff("", "(V)V",
                BytecodeDiffResult.ChangeType.ADDED, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void diagnosticFactoriesSetSeverityAndExtractThrowable() {
        assertThat(TransformationDiagnostic.error("E", "m").severity())
                .isEqualTo(TransformationDiagnostic.Severity.ERROR);
        assertThat(TransformationDiagnostic.info("I", "m").severity())
                .isEqualTo(TransformationDiagnostic.Severity.INFO);
        assertThat(TransformationDiagnostic.warn("W", "m").severity())
                .isEqualTo(TransformationDiagnostic.Severity.WARN);

        Throwable t = new IllegalStateException("boom");
        TransformationDiagnostic d = TransformationDiagnostic.error("E", "m", t);
        assertThat(d.exceptionClassName()).isEqualTo(IllegalStateException.class.getName());
        assertThat(d.detail()).isEqualTo("boom");
        assertThatThrownBy(() -> TransformationDiagnostic.of(TransformationDiagnostic.Severity.ERROR, "", "m"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
