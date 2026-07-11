package com.example.kairo.platform.command;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BytecodeDiagnosticExchangeTest {
    @Test
    void transientInputIsOnlyAddedAtPollTimeAndRemovedAfterCompletion() {
        BytecodeDiagnosticExchange exchange = new BytecodeDiagnosticExchange();
        exchange.register("c1", new byte[]{1, 2, 3});
        Map<String, Object> persisted = Map.of("commandType", "BYTECODE_PREVIEW", "classId", "id");
        assertThat(persisted).doesNotContainKey("bytecodeBase64Url");
        assertThat(exchange.enrichPayload("c1", persisted)).containsKey("bytecodeBase64Url");
        exchange.complete("c1", Map.of("changed", true));
        assertThat(exchange.await("c1", Duration.ofSeconds(1))).containsEntry("changed", true);
        assertThat(exchange.pendingCount()).isZero();
    }

    @Test
    void binaryResultIsStrippedBeforePersistenceButAvailableToWaitingRequest() {
        BytecodeDiagnosticExchange exchange = new BytecodeDiagnosticExchange();
        exchange.register("c2", null);
        Map<String, Object> result = Map.of("hash", "h", "bytecodeBase64Url", "AQID",
                "decompilation", Map.of("status", "SUCCESS", "sourceCode", "class Secret {}"));
        exchange.complete("c2", result);
        assertThat(exchange.sanitizeForPersistence(result)).containsEntry("hash", "h")
                .doesNotContainKey("bytecodeBase64Url");
        assertThat(((Map<?, ?>) exchange.sanitizeForPersistence(result).get("decompilation"))
                .containsKey("sourceCode")).isFalse();
        assertThat(exchange.await("c2", Duration.ofSeconds(1))).containsKey("bytecodeBase64Url");
    }

    @Test
    void timeoutAndOversizedInputAlwaysCleanUp() {
        BytecodeDiagnosticExchange exchange = new BytecodeDiagnosticExchange();
        exchange.register("c3", null);
        assertThatThrownBy(() -> exchange.await("c3", Duration.ofMillis(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(exchange.pendingCount()).isZero();
        assertThatThrownBy(() -> exchange.register("large", new byte[1024 * 1024 + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
