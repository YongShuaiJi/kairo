package com.example.kairo.agent.server;

import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.api.bytecode.TransformationRevision;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for the request-parsing helpers, limits and the diagnostic
 * executor. No HTTP server, no Instrumentation, no self-attach.
 */
class BytecodeRoutesUnitTest {

    @Test
    void parseKindAcceptsCaseInsensitiveValues() {
        assertThat(BytecodeRoutes.parseKind("INPUT")).isEqualTo(BytecodeSnapshotKind.INPUT);
        assertThat(BytecodeRoutes.parseKind("planned")).isEqualTo(BytecodeSnapshotKind.PLANNED);
        assertThat(BytecodeRoutes.parseKind(" Applied ")).isEqualTo(BytecodeSnapshotKind.APPLIED);
    }

    @Test
    void parseKindRejectsMissingOrUnknown() {
        assertThatThrownBy(() -> BytecodeRoutes.parseKind(null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> BytecodeRoutes.parseKind("")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> BytecodeRoutes.parseKind("OUTPUT"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("invalid kind");
    }

    @Test
    void parseRevisionAcceptsNonNegativeLongs() {
        assertThat(BytecodeRoutes.parseRevision("0")).isZero();
        assertThat(BytecodeRoutes.parseRevision(" 42 ")).isEqualTo(42L);
        assertThat(BytecodeRoutes.parseRevision("9223372036854775807"))
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void parseRevisionRejectsMissingNegativeAndNonNumeric() {
        assertThatThrownBy(() -> BytecodeRoutes.parseRevision(null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> BytecodeRoutes.parseRevision(""))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("revision is required");
        assertThatThrownBy(() -> BytecodeRoutes.parseRevision("abc"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("invalid revision");
        assertThatThrownBy(() -> BytecodeRoutes.parseRevision("-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(">= 0");
    }

    @Test
    void parseSelectorParsesKindAtRevision() {
        BytecodeRoutes.SnapshotSelector selector = BytecodeRoutes.parseSelector("INPUT@1");
        assertThat(selector.kind()).isEqualTo(BytecodeSnapshotKind.INPUT);
        assertThat(selector.revision()).isEqualTo(TransformationRevision.of(1L));

        BytecodeRoutes.SnapshotSelector zero = BytecodeRoutes.parseSelector("APPLIED@0");
        assertThat(zero.kind()).isEqualTo(BytecodeSnapshotKind.APPLIED);
        assertThat(zero.revision().value()).isZero();
    }

    @Test
    void parseSelectorRejectsMalformedForms() {
        assertThatThrownBy(() -> BytecodeRoutes.parseSelector(null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> BytecodeRoutes.parseSelector("INPUT"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("KIND@revision");
        assertThatThrownBy(() -> BytecodeRoutes.parseSelector("INPUT@"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> BytecodeRoutes.parseSelector("INPUT@-1"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> BytecodeRoutes.parseSelector("BOGUS@1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("kind");
    }

    @Test
    void bytecodeApiLimitsValidateAndProvideDefaults() {
        assertThat(BytecodeApiLimits.STANDARD.maxRequestBodyBytes()).isPositive();
        assertThat(BytecodeApiLimits.STANDARD.maxBytecodeResponseBytes())
                .isGreaterThanOrEqualTo(BytecodeApiLimits.STANDARD.maxRequestBodyBytes());
        assertThat(BytecodeApiLimits.STANDARD.diagnosticTimeoutMillis()).isPositive();
        assertThat(BytecodeApiLimits.STANDARD.diagnosticConcurrency()).isPositive();

        assertThatThrownBy(() -> new BytecodeApiLimits(0, 1, 1L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BytecodeApiLimits(1, 0, 1L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BytecodeApiLimits(1, 1, 0L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BytecodeApiLimits(1, 1, 1L, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void diagnosticExecutorReturnsResultOnSuccess() {
        try (BytecodeDiagnosticExecutor executor = new BytecodeDiagnosticExecutor(2_000L, 2)) {
            assertThat(executor.submitAndAwait(() -> "ok")).isEqualTo("ok");
        }
    }

    @Test
    void diagnosticExecutorTimesOutWithoutRunningOnCallingThread() throws InterruptedException {
        try (BytecodeDiagnosticExecutor executor = new BytecodeDiagnosticExecutor(100L, 1)) {
            assertThatThrownBy(() -> executor.submitAndAwait(() -> {
                Thread.sleep(5_000L);
                return "slow";
            })).isInstanceOf(BytecodeDiagnosticExecutor.DiagnosticTimeoutException.class);
        }
        // The slow task is interrupted on timeout/cancel; give it a moment to unwind so
        // it does not outlive the executor shutdown and leak into the next test.
        Thread.sleep(50L);
    }

    @Test
    void diagnosticExecutorWrapsTaskFailures() {
        try (BytecodeDiagnosticExecutor executor = new BytecodeDiagnosticExecutor(2_000L, 1)) {
            AtomicBoolean ran = new AtomicBoolean();
            assertThatThrownBy(() -> executor.submitAndAwait(() -> {
                ran.set(true);
                throw new IllegalStateException("boom");
            })).isInstanceOf(BytecodeDiagnosticExecutor.DiagnosticFailedException.class)
                    .hasMessageContaining("boom");
            assertThat(ran).isTrue();
        }
    }
}
