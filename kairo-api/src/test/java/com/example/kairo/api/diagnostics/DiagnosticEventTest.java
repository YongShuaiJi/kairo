package com.example.kairo.api.diagnostics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosticEventTest {

    @Test
    void formatsSearchableBoundedSingleLineEvents() {
        String event = DiagnosticEvent.format("operation.transition",
                "operationId", "op-1", "status", "SUCCEEDED", "durationMs", 17);

        assertThat(event).isEqualTo(
                "event=\"operation.transition\" operationId=\"op-1\" status=\"SUCCEEDED\" durationMs=17");
    }

    @Test
    void redactsSensitiveFieldsAndSecretsEmbeddedInFailures() {
        String event = DiagnosticEvent.format("request.failed",
                "authorization", "Bearer abc.def", "script", "return 1",
                "reason", "token=top-secret api_key=hidden\nnext-line");

        assertThat(event).doesNotContain("abc.def", "return 1", "top-secret", "hidden", "\n")
                .contains("authorization=\"[REDACTED]\"")
                .contains("script=\"[REDACTED]\"")
                .contains("token=[REDACTED]")
                .contains("api_key=[REDACTED]");
    }

    @Test
    void preservesNonSensitiveCorrelationFieldsThatContainSourceOrScriptWords() {
        String event = DiagnosticEvent.format("operation.started",
                "identitySource", "mcp", "resourceId", "rule-17",
                "scriptSessionId", "ss-3", "scriptSource", "return 1");

        assertThat(event)
                .contains("identitySource=\"mcp\"")
                .contains("resourceId=\"rule-17\"")
                .contains("scriptSessionId=\"ss-3\"")
                .contains("scriptSource=\"[REDACTED]\"")
                .doesNotContain("return 1");
    }

    @Test
    void summarizesCauseChainWithoutUnboundedOutput() {
        RuntimeException failure = new RuntimeException("outer",
                new IllegalStateException("password=hunter2"));

        assertThat(DiagnosticEvent.failureSummary(failure))
                .contains("RuntimeException: outer", "IllegalStateException", "password=[REDACTED]")
                .doesNotContain("hunter2");
        assertThat(DiagnosticEvent.stackSummary(failure))
                .contains("IllegalStateException", "DiagnosticEventTest")
                .doesNotContain("hunter2");
    }

    @Test
    void rejectsOddFieldList() {
        assertThatThrownBy(() -> DiagnosticEvent.format("bad", "key"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprintsWithoutDisclosingOriginalValue() {
        assertThat(DiagnosticEvent.fingerprint("private-idempotency-key"))
                .hasSize(16)
                .doesNotContain("private");
    }

    @Test
    void boundsWholeEventWithoutCuttingAField() {
        Object[] fields = new Object[40];
        for (int i = 0; i < fields.length; i += 2) {
            fields[i] = "field" + i;
            fields[i + 1] = "x".repeat(600);
        }

        String event = DiagnosticEvent.format("large.event", fields);

        assertThat(event).hasSizeLessThanOrEqualTo(DiagnosticEvent.MAX_LOG_LINE_LENGTH)
                .endsWith("truncated=true")
                .doesNotEndWith("\\");
    }
}
